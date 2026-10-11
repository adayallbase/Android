package com.dbzbanten.floatweb;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {

    private static final String NAME = "dbzfloat_prefs";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_LAST_TAB = "last_tab";

    // === URL Terakhir ===
    public static String get(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_LAST_URL, "");
    }

    public static void set(Context ctx, String url) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_LAST_URL, url).apply();
    }

    // === Tab Terakhir (0=Home, 1=Browser) ===
    public static int getLastTab(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_LAST_TAB, 0);
    }

    public static void setLastTab(Context ctx, int tab) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_LAST_TAB, tab).apply();
    }
}