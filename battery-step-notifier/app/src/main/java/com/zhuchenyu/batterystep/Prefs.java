package com.zhuchenyu.batterystep;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String FILE = "battery_step_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STEP = "step";
    private static final String KEY_HIDE_RECENTS = "hide_recents";

    private Prefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static int getStep(Context context) {
        return Math.max(1, Math.min(20, prefs(context).getInt(KEY_STEP, 1)));
    }

    public static void setStep(Context context, int step) {
        prefs(context).edit().putInt(KEY_STEP, Math.max(1, Math.min(20, step))).apply();
    }

    public static boolean hideFromRecents(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_RECENTS, false);
    }

    public static void setHideFromRecents(Context context, boolean hide) {
        prefs(context).edit().putBoolean(KEY_HIDE_RECENTS, hide).apply();
    }
}
