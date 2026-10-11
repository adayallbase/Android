package com.dbzbanten.floatweb;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.net.URLEncoder;
import java.util.ArrayList;

public class FloatService extends Service {

    private static final String DEFAULT_URL =
            "https://script.google.com/macros/s/AKfycbwUvjDgI4hQOYv8mp3B5BjutAf-hz_dd3-e7DxEjZJokb0hjDQnV4V-17BOfyiieFet/exec";
    private static final String SEARCH_ENGINE = "https://www.google.com/search?q=";
    private static final String PREFS_NAME = "dbzfloat_prefs";
    private static final String KEY_LAST_URL = "last_url";
    private static final String BLANK_URL = "about:blank";

    private static final int MIN_WIDTH_DP = 240;
    private static final int MIN_HEIGHT_DP = 360;
    private static final int BUBBLE_SIZE_DP = 60;
    private static final int MAX_TABS = 2;

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
    private Button defaultBtn;
    private Button closeBtn;

    // Nav bar
    private Button backBtn;
    private Button forwardBtn;
    private Button reloadBtn;
    private EditText addressBar;
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
        if (root != null) return START_STICKY;

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

            // === Buka 2 tab default ===
            // Tab 1: Home (Google Script)
            addNewTabSilent(DEFAULT_URL);
            // Tab 2: Browser (blank)
            addNewTabSilent(BLANK_URL);

            // Aktifkan tab 1 (Home)
            switchTab(0);

        } catch (Throwable t) {
            Toast.makeText(this, "Error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
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

        // === HEADER dengan tab ===
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xFF1A1A1E);
        header.setPadding(dp(8), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        container.addView(header, headerLp);

        // Tab bar
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        header.addView(tabBar, tabLp);

        // Tombol D
        defaultBtn = makeBtn("D", 0xFFFF6600);
        header.addView(defaultBtn, new LinearLayout.LayoutParams(dp(34), dp(28)));

        // Tombol X
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
        navBar.addView(backBtn, new LinearLayout.LayoutParams(dp(36), dp(36)));

        forwardBtn = makeNavBtn("▶");
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        fLp.leftMargin = dp(2);
        navBar.addView(forwardBtn, fLp);

        reloadBtn = makeNavBtn("⟳");
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        rLp.leftMargin = dp(2);
        navBar.addView(reloadBtn, rLp);

        addressBar = new EditText(this);
        addressBar.setHint("Cari atau ketik URL");
        addressBar.setHintTextColor(0xFF777777);
        addressBar.setTextColor(0xFFFFFFFF);
        addressBar.setTextSize(12);
        addressBar.setSingleLine(true);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setBackgroundColor(0xFF1A1A1E);
        addressBar.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        addrLp.leftMargin = dp(4);
        addrLp.rightMargin = dp(4);
        navBar.addView(addressBar, addrLp);

        autoBtn = makeBtn("10s", 0xFF333334);
        navBar.addView(autoBtn, new LinearLayout.LayoutParams(dp(48), dp(36)));

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
        backBtn.setOnClickListener(v -> {
            Tab t = getActiveTab();
            if (t != null && t.webView.canGoBack()) t.webView.goBack();
        });

        forwardBtn.setOnClickListener(v -> {
            Tab t = getActiveTab();
            if (t != null && t.webView.canGoForward()) t.webView.goForward();
        });

        reloadBtn.setOnClickListener(v -> {
            stopAuto();
            Tab t = getActiveTab();
            if (t != null) t.webView.reload();
        });

        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                String input = addressBar.getText().toString();
                String url = parseInput(input);
                if (url != null) {
                    Tab t = getActiveTab();
                    if (t != null) t.webView.loadUrl(url);
                }
                return true;
            }
            return false;
        });

        FocusToggle toggle = new FocusToggle(this);
        addressBar.setOnTouchListener(toggle);
        addressBar.setOnFocusChangeListener(toggle);

        autoBtn.setOnClickListener(v -> showIntervalDialog());

        defaultBtn.setOnClickListener(v -> {
            Tab t = getActiveTab();
            if (t != null) {
                t.webView.loadUrl(DEFAULT_URL);
                addressBar.setText(DEFAULT_URL);
            }
        });

        closeBtn.setOnClickListener(v -> {
            close();
            stopSelf();
        });

        return frameRoot;
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
        b.setTextSize(12);
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

    /**
     * Tambah tab tanpa rebuild tab bar — khusus untuk setup awal.
     */
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

    private void addNewTab(String url) {
        if (tabs.size() >= MAX_TABS) {
            Toast.makeText(this, "Maksimal " + MAX_TABS + " tab", Toast.LENGTH_SHORT).show();
            return;
        }

        WebView wv = createWebView();
        Tab tab = new Tab(wv, url);
        tabs.add(tab);
        webViewContainer.addView(wv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        activeTabIndex = tabs.size() - 1;
        wv.loadUrl(url);

        rebuildTabBar();
        updateActiveTabVisibility();
        updateNavButtons();
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
        return wv;
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeTabIndex = index;
        rebuildTabBar();
        updateActiveTabVisibility();
        updateNavButtons();
        Tab t = getActiveTab();
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

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;

        // Tab 1 = Home, tidak bisa ditutup
        if (index == 0) {
            Toast.makeText(this, "Tab Home tidak bisa ditutup", Toast.LENGTH_SHORT).show();
            return;
        }

        // Kalau cuma 1 tab (harusnya gak mungkin karena tab 0 gak bisa ditutup),
        // tutup service aja
        if (tabs.size() == 1) {
            close();
            stopSelf();
            return;
        }

        Tab t = tabs.remove(index);
        try {
            t.webView.stopLoading();
            t.webView.destroy();
        } catch (Throwable ignored) {}
        webViewContainer.removeView(t.webView);

        if (activeTabIndex >= tabs.size()) activeTabIndex = tabs.size() - 1;
        else if (activeTabIndex > index) activeTabIndex--;

        rebuildTabBar();
        updateActiveTabVisibility();
        updateNavButtons();
    }

    private void rebuildTabBar() {
        tabBar.removeAllViews();

        // Tab 1 (Home) — fixed, tidak bisa di-close
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Button btn = new Button(this);
            // Tab 1 tampil "H" (Home), tab lain tampil angka
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
                bg.setColor(i == 0 ? 0xFF2E7D32 : 0xFF444444); // tab home hijau
                btn.setTextColor(0xFFFFFFFF);
            }
            bg.setCornerRadius(dp(6));
            btn.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(28));
            lp.rightMargin = dp(3);
            tabBar.addView(btn, lp);

            btn.setOnClickListener(v -> switchTab(idx));

            // Long-press untuk close — hanya tab selain tab 0
            if (i != 0) {
                btn.setOnLongClickListener(v -> {
                    closeTab(idx);
                    return true;
                });
            } else {
                btn.setOnLongClickListener(v -> {
                    Toast.makeText(this, "Tab Home tidak bisa ditutup", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        // Tombol "+" untuk tambah tab (muncul kalau tab < MAX_TABS)
        if (tabs.size() < MAX_TABS) {
            Button addBtn = new Button(this);
            addBtn.setText("+");
            addBtn.setTextSize(14);
            addBtn.setAllCaps(false);
            addBtn.setTextColor(0xFFFFFFFF);
            addBtn.setPadding(0, 0, 0, 0);
            addBtn.setMinWidth(0);
            addBtn.setMinimumWidth(0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2E7D32);
            bg.setCornerRadius(dp(6));
            addBtn.setBackground(bg);
            addBtn.setOnClickListener(v -> addNewTab(BLANK_URL));
            tabBar.addView(addBtn, new LinearLayout.LayoutParams(dp(32), dp(28)));
        }
    }

    private void updateActiveTabVisibility() {
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).webView.setVisibility(i == activeTabIndex ? View.VISIBLE : View.GONE);
        }
    }

    private void updateNavButtons() {
        Tab t = getActiveTab();
        if (t == null) return;
        backBtn.setEnabled(t.webView.canGoBack());
        backBtn.setAlpha(t.webView.canGoBack() ? 1f : 0.4f);
        forwardBtn.setEnabled(t.webView.canGoForward());
        forwardBtn.setAlpha(t.webView.canGoForward() ? 1f : 0.4f);
    }

    // ================== URL PARSER ==================

    private String parseInput(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.isEmpty()) return null;

        if (input.startsWith("http://") || input.startsWith("https://")) {
            return input;
        }

        if (input.contains(".") && !input.contains(" ")) {
            return "https://" + input;
        }

        try {
            return SEARCH_ENGINE + URLEncoder.encode(input, "UTF-8");
        } catch (Exception e) {
            return SEARCH_ENGINE + input;
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
            Tab t = getActiveTab();
            if (t != null && t.webView == view) {
                if (!BLANK_URL.equals(url)) {
                    addressBar.setText(url);
                }
                progressBar.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            Tab t = getActiveTab();
            if (t != null && t.webView == view) {
                if (!TextUtils.isEmpty(url) && !BLANK_URL.equals(url)) {
                    t.url = url;
                    addressBar.setText(url);
                    Prefs.set(FloatService.this, url);
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
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            super.onReceivedTitle(view, title);
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i).webView == view) {
                    tabs.get(i).title = title;
                    rebuildTabBar();
                    break;
                }
            }
        }
    }

    // ================== AUTO REFRESH ==================

    private String loadSavedUrlString() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String url = sp.getString(KEY_LAST_URL, "");
        return TextUtils.isEmpty(url) ? DEFAULT_URL : url;
    }

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
                    Tab t = getActiveTab();
                    if (t != null) t.webView.reload();
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
        // Destroy semua tab + clear list
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

    static class FocusToggle implements View.OnTouchListener, View.OnFocusChangeListener {
        private final FloatService s;
        FocusToggle(FloatService service) { this.s = service; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                s.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                s.wm.updateViewLayout(s.root, s.lp);
            }
            return false;
        }

        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (!hasFocus) {
                s.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                s.wm.updateViewLayout(s.root, s.lp);
            }
        }
    }
}