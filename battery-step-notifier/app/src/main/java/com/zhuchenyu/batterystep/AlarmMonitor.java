package com.zhuchenyu.batterystep;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

public final class AlarmMonitor {
    private static final String STATE = "alarm_monitor_state";
    private static final String KEY_NEXT = "next_target";
    private static final String KEY_START = "session_start";
    private static final long INTERVAL_MS = 30_000L;
    private static final int REQUEST_CODE = 4401;
    private static final String CHANNEL_MONITOR = "battery_monitor";
    private static final String CHANNEL_ALERT = "battery_alert";
    private static final int MONITOR_ID = 1001;
    private static final int ALERT_BASE = 5000;

    private AlarmMonitor() {}

    public static boolean canScheduleExact(Context context) {
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        if (alarm == null) return false;
        return Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms();
    }

    public static void applyConfig(Context context) {
        if (!Prefs.isEnabled(context)) {
            cancel(context);
            clearSession(context);
            return;
        }
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        if (battery != null && battery.isCharging()) {
            schedule(context, 1000L);
        }
    }

    public static void schedule(Context context, long delayMs) {
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        if (alarm == null) return;
        PendingIntent pi = pendingIntent(context);
        long when = SystemClock.elapsedRealtime() + Math.max(1000L, delayMs);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
                }
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pi);
            }
        } catch (SecurityException ignored) {
        }
    }

    public static void cancel(Context context) {
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        if (alarm != null) alarm.cancel(pendingIntent(context));
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent i = new Intent(context, AlarmMonitorReceiver.class).setAction("com.zhuchenyu.batterystep.ALARM_TICK");
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    public static void handleTick(Context context) {
        createChannels(context);

        if (!Prefs.isEnabled(context)) {
            cancel(context);
            clearSession(context);
            updateMonitorNotification(context, -1, false, "总开关已关闭");
            return;
        }

        BatteryManager battery = context.getSystemService(BatteryManager.class);
        if (battery == null) {
            schedule(context, INTERVAL_MS);
            return;
        }

        int level;
        boolean charging;
        try {
            level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            charging = battery.isCharging();
        } catch (RuntimeException e) {
            schedule(context, INTERVAL_MS);
            return;
        }

        if (level < 0 || level > 100) {
            schedule(context, INTERVAL_MS);
            return;
        }

        if (!charging) {
            clearSession(context);
            updateMonitorNotification(context, level, false, "系统闹钟直读");
            cancel(context);
            return;
        }

        if (isQuietBlocking(context)) {
            clearSession(context);
            updateMonitorNotification(context, level, true, "静音/免打扰暂停");
            schedule(context, 60_000L);
            return;
        }

        SharedPreferences state = context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        int step = Prefs.getStep(context);
        int next = state.getInt(KEY_NEXT, -1);
        int start = state.getInt(KEY_START, -1);

        if (next <= 0) {
            start = level;
            int candidate = level + step;
            next = candidate <= 100 ? candidate : -1;
            state.edit().putInt(KEY_START, start).putInt(KEY_NEXT, next).apply();
        }

        while (next > 0 && next <= level && next <= 100) {
            int gained = start >= 0 ? Math.max(0, next - start) : step;
            sendThreshold(context, next, level, gained);
            next += step;
            if (next > 100) next = -1;
            state.edit().putInt(KEY_NEXT, next).apply();
        }

        updateMonitorNotification(
                context,
                level,
                true,
                next > 0 ? "系统闹钟监测 · 下次 " + next + "%" : "系统闹钟监测"
        );
        schedule(context, INTERVAL_MS);
    }

    public static void resetForPowerDisconnected(Context context) {
        cancel(context);
        clearSession(context);
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        int level = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        updateMonitorNotification(context, level, false, "系统闹钟直读");
    }

    public static void clearSession(Context context) {
        context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
                .edit().remove(KEY_NEXT).remove(KEY_START).apply();
    }

    private static boolean isQuietBlocking(Context context) {
        if (!Prefs.pauseOnQuietMode(context)) return false;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return true;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) {
            int filter = nm.getCurrentInterruptionFilter();
            return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                    && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
        return false;
    }

    private static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR, "电池监测服务", NotificationManager.IMPORTANCE_LOW);
        monitor.setSound(null, null);
        monitor.enableVibration(false);
        nm.createNotificationChannel(monitor);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ALERT, "充电电量提醒", NotificationManager.IMPORTANCE_HIGH));
    }

    private static void updateMonitorNotification(Context context, int level, boolean charging, String mode) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(
                context, 0, new Intent(context, DashboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = (level >= 0 ? level + "%" : "未知") + " · "
                + (charging ? "正在充电" : "未充电") + " · " + mode;
        Notification n = new Notification.Builder(context, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(charging ? "正在监测充电电量" : "电量提醒已待机")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .build();
        nm.notify(MONITOR_ID, n);
    }

    private static void sendThreshold(Context context, int threshold, int observed, int gained) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(
                context, threshold, new Intent(context, DashboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(context, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle("电量达到 " + threshold + "%")
                .setContentText("当前 " + observed + "% · 本次充电已提升 " + gained + "%")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        nm.notify(ALERT_BASE + threshold, n);
    }
}
