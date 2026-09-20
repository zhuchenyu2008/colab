package com.zhuchenyu.batterystep;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private static final String FILE = "battery_step_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STEP = "step";
    private static final String KEY_HIDE_RECENTS = "hide_recents";
    private static final String KEY_BT_AUTO = "bt_auto";
    private static final String KEY_BT_ADDRESS = "bt_address";
    private static final String KEY_BT_NAME = "bt_name";
    private static final String KEY_PAUSE_QUIET = "pause_quiet";
    private static final String KEY_RUNTIME_LEVEL = "runtime_level";
    private static final String KEY_RUNTIME_CHARGING = "runtime_charging";
    private static final String KEY_RUNTIME_NEXT_TARGET = "runtime_next_target";
    private static final String KEY_RUNTIME_UPDATED_AT = "runtime_updated_at";

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

    public static boolean isBluetoothAutoEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BT_AUTO, false);
    }

    public static void setBluetoothAutoEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_BT_AUTO, enabled).apply();
    }

    public static String getBluetoothAddress(Context context) {
        return prefs(context).getString(KEY_BT_ADDRESS, "");
    }

    public static String getBluetoothName(Context context) {
        return prefs(context).getString(KEY_BT_NAME, "未选择设备");
    }

    public static void setBluetoothDevice(Context context, String address, String name) {
        prefs(context).edit()
                .putString(KEY_BT_ADDRESS, address == null ? "" : address)
                .putString(KEY_BT_NAME, name == null || name.isEmpty() ? "未知设备" : name)
                .apply();
    }

    public static boolean pauseOnQuietMode(Context context) {
        return prefs(context).getBoolean(KEY_PAUSE_QUIET, false);
    }

    public static void setPauseOnQuietMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PAUSE_QUIET, enabled).apply();
    }

    public static boolean shouldKeepServiceRunning(Context context) {
        return isEnabled(context) || isBluetoothAutoEnabled(context);
    }

    public static void setRuntimeState(Context context, int level, boolean charging, int nextTarget) {
        prefs(context).edit()
                .putInt(KEY_RUNTIME_LEVEL, level)
                .putBoolean(KEY_RUNTIME_CHARGING, charging)
                .putInt(KEY_RUNTIME_NEXT_TARGET, nextTarget)
                .putLong(KEY_RUNTIME_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public static int getRuntimeLevel(Context context) {
        return prefs(context).getInt(KEY_RUNTIME_LEVEL, -1);
    }

    public static boolean getRuntimeCharging(Context context) {
        return prefs(context).getBoolean(KEY_RUNTIME_CHARGING, false);
    }

    public static int getRuntimeNextTarget(Context context) {
        return prefs(context).getInt(KEY_RUNTIME_NEXT_TARGET, -1);
    }

    public static long getRuntimeUpdatedAt(Context context) {
        return prefs(context).getLong(KEY_RUNTIME_UPDATED_AT, 0L);
    }
}
