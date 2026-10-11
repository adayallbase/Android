package com.dbzbanten.floatweb;

import android.webkit.WebView;

/**
 * Data satu tab browser.
 */
public class Tab {
    public final WebView webView;
    public String url;
    public String title;

    public Tab(WebView webView, String url) {
        this.webView = webView;
        this.url = url;
        this.title = "New Tab";
    }
}