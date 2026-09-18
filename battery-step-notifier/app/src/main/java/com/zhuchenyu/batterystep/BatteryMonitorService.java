package com.zhuchenyu.batterystep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;

public class BatteryMonitorService extends Service {
    public static final String ACTION_REFRESH = "com.zhuchenyu.batterystep.REFRESH";
    private static final String CHANNEL_MONITOR = "battery_monitor";
    private static final String CHANNEL_ALERT = "battery_alert";
    private static final int MONITOR_ID = 1001;
    private static final int ALERT_BASE = 2000;

    private boolean receiverRegistered;
    private boolean charging;
    private int currentLevel = -1;
    private int sessionStart = -1;
    private int nextTarget = -1;
    private int step = 1;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                handleBatteryChanged(intent);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                charging = true;
                if (currentLevel >= 0) beginSession(currentLevel);
                updateForeground();
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                endSession();
                updateForeground();
            }
        }
    };

    public static void start(Context context) {
        Intent intent = new Intent(context, BatteryMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void refresh(Context context) {
        Intent intent = new Intent(context, BatteryMonitorService.class).setAction(ACTION_REFRESH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, BatteryMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        step = Prefs.getStep(this);
        startInForeground();
        registerBatteryReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            int newStep = Prefs.getStep(this);
            if (newStep != step) {
                step = newStep;
                if (charging && currentLevel >= 0) beginSession(currentLevel);
            }
            updateForeground();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerBatteryReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            sticky = registerReceiver(batteryReceiver, filter);
        }
        receiverRegistered = true;
        if (sticky != null && Intent.ACTION_BATTERY_CHANGED.equals(sticky.getAction())) {
            handleBatteryChanged(sticky);
        }
    }

    private void handleBatteryChanged(Intent intent) {
        int raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (raw < 0 || scale <= 0) return;

        int level = Math.round(raw * 100f / scale);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean nowCharging = plugged != 0
                || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        currentLevel = level;
        step = Prefs.getStep(this);

        if (nowCharging && !charging) {
            charging = true;
            beginSession(level);
        } else if (!nowCharging && charging) {
            endSession();
        }

        if (charging && nextTarget > 0 && level >= nextTarget) {
            while (nextTarget <= level && nextTarget <= 100) {
                notifyThreshold(nextTarget, level);
                nextTarget += step;
            }
        }
        updateForeground();
    }

    private void beginSession(int level) {
        sessionStart = level;
        step = Prefs.getStep(this);
        nextTarget = Math.min(100, level + step);
    }

    private void endSession() {
        charging = false;
        sessionStart = -1;
        nextTarget = -1;
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR, "电池监测服务", NotificationManager.IMPORTANCE_LOW);
        monitor.setSound(null, null);
        monitor.enableVibration(false);
        manager.createNotificationChannel(monitor);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERT, "充电电量提醒", NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(alerts);
    }

    private void startInForeground() {
        Notification n = buildForegroundNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(MONITOR_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(MONITOR_ID, n);
        }
    }

    private Notification buildForegroundNotification() {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = charging ? "正在监测充电电量" : "电量提醒已待机";
        String text;
        if (charging && currentLevel >= 0 && nextTarget > 0) {
            text = "当前 " + currentLevel + "% · 下次 " + nextTarget + "% 提醒";
        } else if (charging) {
            text = "已连接电源 · 等待电量变化";
        } else {
            text = "未充电 · 插电后自动开始监测";
        }
        return new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateForeground() {
        getSystemService(NotificationManager.class).notify(MONITOR_ID, buildForegroundNotification());
    }

    private void notifyThreshold(int threshold, int observed) {
        PendingIntent open = PendingIntent.getActivity(
                this, threshold, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int gained = sessionStart >= 0 ? Math.max(0, threshold - sessionStart) : step;
        Notification n = new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle("电量达到 " + threshold + "%")
                .setContentText("当前约 " + observed + "% · 本次充电已提升 " + gained + "%")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        getSystemService(NotificationManager.class).notify(ALERT_BASE + threshold, n);
    }
}
