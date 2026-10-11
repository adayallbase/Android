package com.dbzbanten.floatweb;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;

public class FloatService extends Service {

    private static final String HOME_URL =
            "https://script.google.com/macros/s/AKfycbwUvjDgI4hQOYv8mp3B5BjutAf-hz_dd3-e7DxEjZJokb0hjDQnV4V-17BOfyiieFet/exec";
    private static final String BROWSER_HOME = "https://www.google.com";
    private static final String SEARCH_ENGINE = "https://www.google.com/search?q=";
    private static final String PREFS_NAME = "dbzfloat_prefs";
    private static final String KEY_LAST_URL = "last_url";
    private static final String BLANK_URL = "about:blank";

    private static final int MIN_WIDTH_DP = 240;
    private static final int MIN_HEIGHT_DP = 360;
    private static final int BUBBLE_SIZE_DP = 60;
    private static final int MAX_TABS = 2;

    private static final int TAB_HOME = 0;
    private static final int TAB_BROWSER = 1;

    // Window
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private View root;
    private LinearLayout mainContent;
    private FrameLayout handleBox;
    private FrameLayout webViewContainer;
    private Button bubbleBtn;

    // Header
    private LinearLayout tabBar;
    private Button pasteBtn;
    private Button minBtn;
    private Button defaultBtn;
    private Button closeBtn;

    // Nav bar
    private Button backBtn;
    private Button forwardBtn;
    private Button reloadBtn;
    private EditText addressBar;
    private Button loadBtn;
    private Button autoBtn;
    private ProgressBar progressBar;

    // Tabs
    private final ArrayList<Tab> tabs = new ArrayList<>();
    private int activeTabIndex = 0;

    // State
    private boolean minimized = false;
    private boolean autoRefresh = false;
    private int autoInterval = 10;
    private Handler handler;
    private Runnable autoRunnable;

    private int ow, oh;
    private boolean booting = true;

    // Flag: kalau true, service tidak boleh restart
    private static volatile boolean killing = false;

    // ================== LIFECYCLE ==================

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "dbzfloat", "Floating", NotificationManager.IMPORTANCE_LOW);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(channel);

                Notification notif = new Notification.Builder(this, "dbzfloat")
                        .setContentTitle("DbzFloat")
                        .setContentText("Floating Browser aktif")
                        .setSmallIcon(android.R.drawable.ic_menu_view)
                        .build();
                startForeground(1, notif);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // Kalau sedang proses kill → jangan jalankan apapun
        if (killing) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String urlFromIntent = null;
        if (intent != null) {
            urlFromIntent = intent.getStringExtra("url");
        }

        if (root != null) {
            if (!TextUtils.isEmpty(urlFromIntent)) {
                loadUrlInBrowserTab(urlFromIntent);
            }
            return START_NOT_STICKY;
        }

        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            root = buildUI();

            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            int flagsWin = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;

            lp = new WindowManager.LayoutParams(
                    dp(360), dp(560), type, flagsWin, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 30;
            lp.y = 80;
            ow = lp.width;
            oh = lp.height;
            wm.addView(root, lp);

            addNewTabSilent(HOME_URL);

            String lastUrl = loadSavedUrlFromPrefs();
            if (TextUtils.isEmpty(lastUrl)) {
                addNewTabSilent(BROWSER_HOME);
            } else {
                addNewTabSilent(lastUrl);
            }

            if (!TextUtils.isEmpty(urlFromIntent)) {
                tabs.get(TAB_BROWSER).webView.loadUrl(autofixUrl(urlFromIntent));
                activeTabIndex = TAB_BROWSER;
            } else {
                int lastTab = Prefs.getLastTab(this);
                if (lastTab < 0 || lastTab >= tabs.size()) lastTab = TAB_HOME;
                activeTabIndex = lastTab;
            }

            booting = false;

            rebuildTabBar();
            updateActiveTabVisibility();
            updateAddressBarForTab();
            updateNavButtons();

        } catch (Throwable t) {
            Toast.makeText(this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            stopSelf();
        }
        return START_NOT_STICKY; // ✅ Jangan restart kalau service mati
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Kalau user swipe app dari recent → kill total
        killing = true;
        super.onTaskRemoved(rootIntent);
        killApp();
    }

    // ================== KILL APP TOTAL ==================

    private void killApp() {
        killing = true;

        // 1. Stop auto-refresh
        try { stopAuto(); } catch (Throwable ignored) {}

        // 2. Hapus window dari layar
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
            root = null;
        }

        // 3. Destroy semua WebView
        for (Tab t : tabs) {
            try {
                t.webView.stopLoading();
                t.webView.destroy();
            } catch (Throwable ignored) {}
        }
        tabs.clear();
        activeTabIndex = 0;
        webViewContainer = null;

        // 4. STOP FOREGROUND — WAJIB supaya service bisa mati
        try { stopForeground(true); } catch (Throwable ignored) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}

        // 5. Stop service
        try { stopSelf(); } catch (Throwable ignored) {}
        try { stopSelfResult(1); } catch (Throwable ignored) {}

        // 6. Kill background processes
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(getPackageName());
            }
        } catch (Throwable ignored) {}

        // 7. Kill process SEKARANG (tanpa delay)
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {}
        System.exit(0);
    }

    // ================== CLEAR DATA (KECUALI COOKIES) ==================

    private void clearBrowsingDataKeepCookies() {
        try {
            for (Tab t : tabs) {
                try {
                    t.webView.clearCache(true);
                    t.webView.clearHistory();
                    t.webView.clearFormData();
                    t.webView.clearSslPreferences();
                    t.webView.clearMatches();
                } catch (Throwable ignored) {}
            }

            try {
                WebViewDatabase.getInstance(this).clearFormData();
                WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
            } catch (Throwable ignored) {}

            try {
                File cacheDir = getCacheDir();
                if (cacheDir != null && cacheDir.isDirectory()) {
                    File[] files = cacheDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            deleteRecursive(f);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            try {
                deleteDatabase("webview.db");
                deleteDatabase("webviewCache.db");
            } catch (Throwable ignored) {}

            Prefs.set(this, "");
            Prefs.setLastTab(this, 0);

            try {
                CookieManager.getInstance().flush();
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir == null) return;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        try {
            fileOrDir.delete();
        } catch (Throwable ignored) {}
    }

    // ================== KEYBOARD FOCUS ==================

    private void enableKeyboard() {
        if (lp == null) return;
        if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
        }
    }

    private void disableKeyboard() {
        if (lp == null) return;
        if ((lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try { wm.updateViewLayout(root, lp); } catch (Throwable ignored) {}
        }
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && root != null) {
                imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    // ================== HELPERS ==================

    private String loadSavedUrlFromPrefs() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String url = sp.getString(KEY_LAST_URL, "");
        if (TextUtils.isEmpty(url)) return "";
        if (HOME_URL.equals(url)) return "";
        if (BLANK_URL.equals(url)) return "";
        if (BROWSER_HOME.equals(url)) return "";
        return url;
    }

    private void loadUrlInBrowserTab(String url) {
        if (tabs.size() < 2) return;
        String fixed = autofixUrl(url);
        Tab browserTab = tabs.get(TAB_BROWSER);
        try {
            browserTab.webView.loadUrl(fixed);
            switchTab(TAB_BROWSER);
            if (minimized) toggleBubble();
        } catch (Throwable ignored) {}
    }

    private String autofixUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.isEmpty()) return null;

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        if (url.contains(".") && !url.contains(" ")) {
            return "https://" + url;
        }

        try {
            return SEARCH_ENGINE + URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return SEARCH_ENGINE + url;
        }
    }

    private boolean isHomeTabActive() {
        return activeTabIndex == TAB_HOME;
    }

    private WebView getActiveWebView() {
        Tab t = getActiveTab();
        return t != null ? t.webView : null;
    }

    private void updateAddressBarForTab() {
        if (isHomeTabActive()) {
            addressBar.setText(HOME_URL);
            addressBar.setEnabled(false);
            addressBar.setAlpha(0.5f);
            addressBar.setHint("HOME (locked)");
            loadBtn.setEnabled(false);
            loadBtn.setAlpha(0.5f);
        } else {
            Tab t = getActiveTab();
            addressBar.setEnabled(true);
            addressBar.setAlpha(1f);
            loadBtn.setEnabled(true);
            loadBtn.setAlpha(1f);
            if (t != null) {
                String url = t.webView.getUrl();
                if (url == null || url.equals(BLANK_URL)) {
                    addressBar.setText("");
                    addressBar.setHint("Cari atau ketik URL");
                } else {
                    addressBar.setText(url);
                }
            }
        }
    }

    private void updateNavButtons() {
        WebView wv = getActiveWebView();
        if (wv == null) {
            backBtn.setEnabled(false);
            backBtn.setAlpha(0.4f);
            forwardBtn.setEnabled(false);
            forwardBtn.setAlpha(0.4f);
            return;
        }
        boolean canBack = wv.canGoBack();
        boolean canFwd = wv.canGoForward();
        backBtn.setEnabled(canBack);
        backBtn.setAlpha(canBack ? 1f : 0.4f);
        forwardBtn.setEnabled(canFwd);
        forwardBtn.setAlpha(canFwd ? 1f : 0.4f);
    }

    // ================== UI BUILD ==================

    private View buildUI() {
        LinearLayout container = new LinearLayout(this);
        mainContent = container;
        container.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E1E1E);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(2), 0xFFFF9800);
        container.setBackground(bg);

        // === HEADER ===
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF1A1A1E);
        header.setPadding(dp(8), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        container.addView(header, headerLp);

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        header.addView(tabBar, tabLp);

        pasteBtn = makeBtn("📋", 0xFF00796B);
        header.addView(pasteBtn, new LinearLayout.LayoutParams(dp(34), dp(28)));

        minBtn = makeBtn("−", 0xFF666666);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(dp(34), dp(28));
        mLp.leftMargin = dp(4);
        header.addView(minBtn, mLp);

        defaultBtn = makeBtn("D", 0xFFFF6600);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(dp(34), dp(28));
        dLp.leftMargin = dp(4);
        header.addView(defaultBtn, dLp);

        closeBtn = makeBtn("X", 0xFFCC3333);
        LinearLayout.LayoutParams xLp = new LinearLayout.LayoutParams(dp(34), dp(28));
        xLp.leftMargin = dp(4);
        header.addView(closeBtn, xLp);

        // === NAV BAR ===
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER_VERTICAL);
        navBar.setPadding(dp(4), dp(4), dp(6), dp(4));
        navBar.setBackgroundColor(0xFF2A2A2E);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        container.addView(navBar, navLp);

        backBtn = makeNavBtn("◀");
        navBar.addView(backBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        forwardBtn = makeNavBtn("▶");
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        fLp.leftMargin = dp(2);
        navBar.addView(forwardBtn, fLp);

        reloadBtn = makeNavBtn("⟳");
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        rLp.leftMargin = dp(2);
        navBar.addView(reloadBtn, rLp);

        addressBar = new EditText(this);
        addressBar.setHint("Cari atau ketik URL");
        addressBar.setHintTextColor(0xFF777777);
        addressBar.setTextColor(0xFFFFFFFF);
        addressBar.setTextSize(11);
        addressBar.setSingleLine(true);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setBackgroundColor(0xFF1A1A1E);
        addressBar.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        addrLp.leftMargin = dp(4);
        addrLp.rightMargin = dp(4);
        navBar.addView(addressBar, addrLp);

        loadBtn = makeBtn("GO", 0xFFFF9800);
        loadBtn.setTextColor(0xFF000000);
        navBar.addView(loadBtn, new LinearLayout.LayoutParams(dp(38), dp(34)));

        autoBtn = makeBtn("10s", 0xFF333334);
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(dp(46), dp(34));
        autoLp.leftMargin = dp(4);
        navBar.addView(autoBtn, autoLp);

        // === PROGRESS BAR ===
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        container.addView(progressBar, progLp);

        // === WEBVIEW CONTAINER ===
        webViewContainer = new FrameLayout(this);
        LinearLayout.LayoutParams wvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        container.addView(webViewContainer, wvLp);

        // === FRAME ROOT + HANDLES ===
        FrameLayout frameRoot = new FrameLayout(this);
        FrameLayout.LayoutParams frameLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        frameRoot.addView(container, frameLp);

        handleBox = new FrameLayout(this);
        frameRoot.addView(handleBox, frameLp);

        View moveHandle = new View(this);
        moveHandle.setBackgroundColor(0x33FFAA00);
        FrameLayout.LayoutParams moveLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        moveLp.gravity = Gravity.BOTTOM | Gravity.START;
        handleBox.addView(moveHandle, moveLp);
        moveHandle.setOnTouchListener(new MoveListener(this));

        View resizeHandle = new View(this);
        resizeHandle.setBackgroundColor(0x33FFFFFF);
        FrameLayout.LayoutParams resizeLp = new FrameLayout.LayoutParams(dp(24), dp(24));
        resizeLp.gravity = Gravity.BOTTOM | Gravity.END;
        handleBox.addView(resizeHandle, resizeLp);
        resizeHandle.setOnTouchListener(new ResizeListener(this));

        // Bubble
        bubbleBtn = new Button(this);
        bubbleBtn.setText("DBZ");
        bubbleBtn.setTextColor(0xFFFFFFFF);
        bubbleBtn.setTextSize(12);
        bubbleBtn.setAllCaps(false);
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(0xFFFF6700);
        bubbleBtn.setBackground(bubbleBg);
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        frameRoot.addView(bubbleBtn, bubbleLp);
        bubbleBtn.setVisibility(View.GONE);
        bubbleBtn.setOnTouchListener(new BubbleListener(this));

        // ===== LISTENERS =====

        addressBar.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                enableKeyboard();
            }
            return false;
        });

        backBtn.setOnClickListener(v -> {
            disableKeyboard();
            WebView wv = getActiveWebView();
            if (wv != null && wv.canGoBack()) {
                wv.goBack();
            }
        });

        forwardBtn.setOnClickListener(v -> {
            disableKeyboard();
            WebView wv = getActiveWebView();
            if (wv != null && wv.canGoForward()) {
                wv.goForward();
            }
        });

        reloadBtn.setOnClickListener(v -> {
            disableKeyboard();
            stopAuto();
            WebView wv = getActiveWebView();
            if (wv != null) wv.reload();
        });

        loadBtn.setOnClickListener(v -> {
            doLoad();
            disableKeyboard();
        });

        autoBtn.setOnClickListener(v -> {
            disableKeyboard();
            showIntervalDialog();
        });

        pasteBtn.setOnClickListener(v -> {
            disableKeyboard();
            pasteFromClipboard();
        });

        defaultBtn.setOnClickListener(v -> {
            disableKeyboard();
            resetBrowserTab();
        });

        minBtn.setOnClickListener(v -> {
            disableKeyboard();
            toggleBubble();
        });

        // === CLOSE BUTTON — Langsung clear cache + kill total ===
        closeBtn.setOnClickListener(v -> {
            disableKeyboard();

            // Clear cache dulu (cookies tetap)
            clearBrowsingDataKeepCookies();

            // Kill app
            killApp();
        });

        return frameRoot;
    }

    private void doLoad() {
        String input = addressBar.getText().toString();
        String url = autofixUrl(input);
        if (url == null) return;

        if (isHomeTabActive()) {
            switchTab(TAB_BROWSER);
        }

        Tab t = getActiveTab();
        if (t != null) {
            t.webView.loadUrl(url);
            if (!BLANK_URL.equals(url) && !HOME_URL.equals(url) && !BROWSER_HOME.equals(url)) {
                Prefs.set(FloatService.this, url);
            }
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).getText();
            if (text != null && !TextUtils.isEmpty(text)) {
                String pasted = text.toString();

                if (isHomeTabActive()) {
                    switchTab(TAB_BROWSER);
                }

                addressBar.setText(pasted);
                Tab t = getActiveTab();
                if (t != null) {
                    String url = autofixUrl(pasted);
                    if (url != null) {
                        t.webView.loadUrl(url);
                        if (!BLANK_URL.equals(url) && !HOME_URL.equals(url) && !BROWSER_HOME.equals(url)) {
                            Prefs.set(FloatService.this, url);
                        }
                    }
                }
                Toast.makeText(this, "Pasted: " + pasted, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Toast.makeText(this, "Clipboard kosong", Toast.LENGTH_SHORT).show();
    }

    private void resetBrowserTab() {
        Prefs.set(this, "");

        if (isHomeTabActive()) {
            switchTab(TAB_BROWSER);
        }

        Tab t = tabs.get(TAB_BROWSER);
        if (t != null) {
            t.webView.loadUrl(BROWSER_HOME);
        }
        addressBar.setText(BROWSER_HOME);
        Toast.makeText(this, "Reset ke Google.com", Toast.LENGTH_SHORT).show();
    }

    private Button makeBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        return b;
    }

    private Button makeNavBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF444444);
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        return b;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ================== TAB MANAGEMENT ==================

    private Tab getActiveTab() {
        if (tabs.isEmpty()) return null;
        if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
            activeTabIndex = 0;
        }
        return tabs.get(activeTabIndex);
    }

    private void addNewTabSilent(String url) {
        if (tabs.size() >= MAX_TABS) return;

        WebView wv = createWebView();
        Tab tab = new Tab(wv, url);
        tabs.add(tab);
        webViewContainer.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        wv.loadUrl(url);

        activeTabIndex = tabs.size() - 1;
    }

    private WebView createWebView() {
        WebView wv = new WebView(this);
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);
        wv.setBackgroundColor(0xFF000000);
        wv.setWebViewClient(new TabWebViewClient());
        wv.setWebChromeClient(new TabWebChromeClient());

        wv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                enableKeyboard();
                v.requestFocus();
            }
            return false;
        });

        wv.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                enableKeyboard();
            }
        });

        return wv;
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeTabIndex = index;

        if (!booting) {
            Prefs.setLastTab(this, index);
        }

        rebuildTabBar();
        updateActiveTabVisibility();
        updateAddressBarForTab();
        updateNavButtons();
    }

    private void rebuildTabBar() {
        tabBar.removeAllViews();

        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Button btn = new Button(this);
            btn.setText(i == 0 ? "H" : String.valueOf(i + 1));
            btn.setTextSize(11);
            btn.setAllCaps(false);
            btn.setPadding(0, 0, 0, 0);
            btn.setMinWidth(0);
            btn.setMinimumWidth(0);

            GradientDrawable bg = new GradientDrawable();
            if (idx == activeTabIndex) {
                bg.setColor(0xFFFF9800);
                btn.setTextColor(0xFF000000);
            } else {
                bg.setColor(i == 0 ? 0xFF2E7D32 : 0xFF444444);
                btn.setTextColor(0xFFFFFFFF);
            }
            bg.setCornerRadius(dp(6));
            btn.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(28));
            lp.rightMargin = dp(3);
            tabBar.addView(btn, lp);

            btn.setOnClickListener(v -> {
                disableKeyboard();
                switchTab(idx);
            });
        }
    }

    private void updateActiveTabVisibility() {
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).webView.setVisibility(i == activeTabIndex ? View.VISIBLE : View.GONE);
        }
    }

    // ================== WEBVIEW CLIENT ==================

    private class TabWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return false;
            }
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable ignored) {}
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            Tab active = getActiveTab();
            if (active != null && active.webView == view) {
                if (!isHomeTabActive() && !BLANK_URL.equals(url)) {
                    addressBar.setText(url);
                }
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);

            Tab found = null;
            for (Tab t : tabs) {
                if (t.webView == view) {
                    found = t;
                    break;
                }
            }
            if (found == null) return;

            if (!TextUtils.isEmpty(url) && !BLANK_URL.equals(url)) {
                found.url = url;

                if (!HOME_URL.equals(url) && !BROWSER_HOME.equals(url)) {
                    Prefs.set(FloatService.this, url);
                }
            }

            Tab active = getActiveTab();
            if (active != null && active == found) {
                if (!isHomeTabActive() && !TextUtils.isEmpty(url) && !BLANK_URL.equals(url)) {
                    addressBar.setText(url);
                }
                progressBar.setVisibility(View.GONE);
                updateNavButtons();
            }
        }
    }

    private class TabWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            Tab active = getActiveTab();
            if (active != null && active.webView == view) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }
        }
    }

    // ================== AUTO REFRESH ==================

    public void applyInterval(int seconds) {
        autoInterval = seconds;
        autoRefresh = true;
        if (autoBtn != null) autoBtn.setText(seconds + "s");
        scheduleAuto();
        Toast.makeText(this, "Auto refresh ON (" + seconds + "s)", Toast.LENGTH_SHORT).show();
    }

    private void scheduleAuto() {
        if (!autoRefresh) return;
        if (handler == null) handler = new Handler();
        if (autoRunnable == null) {
            autoRunnable = () -> {
                if (autoRefresh) {
                    WebView wv = getActiveWebView();
                    if (wv != null) wv.reload();
                    scheduleAuto();
                }
            };
        }
        handler.removeCallbacks(autoRunnable);
        handler.postDelayed(autoRunnable, autoInterval * 1000L);
    }

    private void stopAuto() {
        autoRefresh = false;
        if (handler != null && autoRunnable != null) handler.removeCallbacks(autoRunnable);
    }

    public void showIntervalDialog() {
        final int[] options = {5, 10, 15, 30, 60};
        String[] labels = {"5 detik", "10 detik", "15 detik", "30 detik", "60 detik"};

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pilih Interval Auto Refresh")
                .setItems(labels, (d, which) -> applyInterval(options[which]))
                .setNegativeButton("Batal", null)
                .create();

        if (dialog.getWindow() != null) {
            WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            attrs.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            dialog.getWindow().setAttributes(attrs);
        }
        dialog.show();
    }

    // ================== UTILS ==================

    private void close() {
        stopAuto();
        if (root != null && wm != null) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
            root = null;
        }
        for (Tab t : tabs) {
            try {
                t.webView.stopLoading();
                t.webView.destroy();
            } catch (Throwable ignored) {}
        }
        tabs.clear();
        activeTabIndex = 0;
        webViewContainer = null;
        try { stopForeground(true); } catch (Throwable ignored) {}
    }

    private void toggleBubble() {
        if (minimized) {
            minimized = false;
            lp.width = ow;
            lp.height = oh;
            mainContent.setVisibility(View.VISIBLE);
            handleBox.setVisibility(View.VISIBLE);
            bubbleBtn.setVisibility(View.GONE);
        } else {
            minimized = true;
            ow = lp.width;
            oh = lp.height;
            int size = dp(BUBBLE_SIZE_DP);
            lp.width = size;
            lp.height = size;
            mainContent.setVisibility(View.GONE);
            handleBox.setVisibility(View.GONE);
            bubbleBtn.setVisibility(View.VISIBLE);
        }
        wm.updateViewLayout(root, lp);
    }

    // ================== INNER LISTENERS ==================

    static class BubbleListener implements View.OnTouchListener {
        private final FloatService s;
        private float startX, startY;
        private int startParamX, startParamY;

        BubbleListener(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    startParamX = s.lp.x; startParamY = s.lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    s.lp.x = startParamX + (int)(e.getRawX() - startX);
                    s.lp.y = startParamY + (int)(e.getRawY() - startY);
                    s.wm.updateViewLayout(s.root, s.lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(e.getRawX() - startX);
                    float dy = Math.abs(e.getRawY() - startY);
                    if (dx < 10 && dy < 10) s.toggleBubble();
                    return true;
            }
            return false;
        }
    }

    static class MoveListener implements View.OnTouchListener {
        private final FloatService s;
        private float startX, startY;
        private int startParamX, startParamY;

        MoveListener(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    startParamX = s.lp.x; startParamY = s.lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    s.lp.x = startParamX + (int)(e.getRawX() - startX);
                    s.lp.y = startParamY + (int)(e.getRawY() - startY);
                    s.wm.updateViewLayout(s.root, s.lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        }
    }

    static class ResizeListener implements View.OnTouchListener {
        private final FloatService s;
        private float startX, startY;
        private int startW, startH;

        ResizeListener(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX(); startY = e.getRawY();
                    startW = s.lp.width; startH = s.lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int newW = startW + (int)(e.getRawX() - startX);
                    int newH = startH + (int)(e.getRawY() - startY);
                    newW = Math.max(newW, s.dp(MIN_WIDTH_DP));
                    newH = Math.max(newH, s.dp(MIN_HEIGHT_DP));
                    s.lp.width = newW; s.lp.height = newH;
                    s.wm.updateViewLayout(s.root, s.lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        }
    }
}