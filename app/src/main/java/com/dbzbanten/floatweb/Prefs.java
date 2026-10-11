package com.dbzbanten.floatweb;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {

    private static final String NAME = "dbzfloat_prefs";
    private static final String KEY_LAST_URL = "last_url";

    public static String get(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_LAST_URL, "");
    }

    public static void set(Context ctx, String url) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_LAST_URL, url).apply();
    }
}