package com.dbzbanten.floatweb;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQUEST_OVERLAY = 100;

    private String pendingUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pendingUrl = extractUrl(getIntent());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_OVERLAY);
                Toast.makeText(this, "Izinkan overlay untuk DbzFloat", Toast.LENGTH_LONG).show();
                return;
            }
        }

        startFloatService(pendingUrl);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String url = extractUrl(intent);
        if (url != null) {
            Intent serviceIntent = new Intent(this, FloatService.class);
            serviceIntent.putExtra("url", url);
            startService(serviceIntent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startFloatService(pendingUrl);
            } else {
                Toast.makeText(this, "Izin overlay ditolak", Toast.LENGTH_SHORT).show();
            }
            finish();
        }
    }

    private String extractUrl(Intent intent) {
        if (intent == null) return null;

        String action = intent.getAction();
        if (action == null) return null;

        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) return data.toString();
        }

        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                text = text.trim();
                int start = text.indexOf("http://");
                if (start < 0) start = text.indexOf("https://");
                if (start >= 0) {
                    String url = text.substring(start);
                    int end = url.indexOf(' ');
                    if (end > 0) url = url.substring(0, end);
                    return url;
                }
                return text;
            }
        }

        return null;
    }

    private void startFloatService(String url) {
        Intent serviceIntent = new Intent(this, FloatService.class);
        if (url != null && !url.isEmpty()) {
            serviceIntent.putExtra("url", url);
        }
        startService(serviceIntent);
    }
}