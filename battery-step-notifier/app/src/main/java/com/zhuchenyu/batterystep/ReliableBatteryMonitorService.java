package com.zhuchenyu.batterystep;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ReliableBatteryMonitorService extends Service {
    public static final String ACTION_REFRESH = "com.zhuchenyu.batterystep.REFRESH_V5";
    public static final String ACTION_TEST = "com.zhuchenyu.batterystep.TEST_ALERT";

    private static final String CHANNEL_MONITOR = "battery_monitor";
    private static final String CHANNEL_ALERT = "battery_alert";
    private static final int MONITOR_ID = 1001;
    private static final int ALERT_BASE = 4000;
    private static final long POLL_SECONDS = 60L;

    private static volatile ReliableBatteryMonitorService instance;

    private final Object stateLock = new Object();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollFuture;
    private boolean receiverRegistered;
    private boolean charging;
    private boolean quietPaused;
    private int currentLevel = -1;
    private int sessionStart = -1;
    private int nextTarget = -1;
    private int step = 1;
    private PowerManager.WakeLock wakeLock;
    private long lastSampleAt;

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)
                    || NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)
                    || NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED.equals(action)) {
                synchronized (stateLock) {
                    boolean before = quietPaused;
                    updateQuietPauseStateLocked();
                    if (before != quietPaused) {
                        BackgroundLogStore.append(
                                ReliableBatteryMonitorService.this,
                                "静音",
                                quietPaused ? "静音/勿扰已触发：暂停充电提醒，但每分钟检测继续" : "静音/勿扰已解除：恢复充电提醒"
                        );
                    }
                    updateForegroundLocked();
                }
            }
        }
    };

    public static void start(Context context) {
        startWithAction(context, null);
    }

    public static void refresh(Context context) {
        ReliableBatteryMonitorService live = instance;
        if (live != null) {
            live.refreshInProcess();
            return;
        }
        startWithAction(context, ACTION_REFRESH);
    }

    public static void sendTestAlert(Context context) {
        ReliableBatteryMonitorService live = instance;
        if (live != null) {
            live.sendAlertNotification(999, "测试通知", "后台提醒通道工作正常");
            BackgroundLogStore.append(context, "通知", "发送测试通知");
            return;
        }
        startWithAction(context, ACTION_TEST);
    }

    public static void applyConfig(Context context) {
        if (Prefs.shouldKeepServiceRunning(context)) refresh(context);
        else stop(context);
    }

    public static void stop(Context context) {
        ReliableBatteryMonitorService live = instance;
        if (live != null) {
            live.stopSelf();
            return;
        }
        context.stopService(new Intent(context, ReliableBatteryMonitorService.class));
    }

    private static void startWithAction(Context context, String action) {
        Intent intent = new Intent(context, ReliableBatteryMonitorService.class);
        if (action != null) intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException e) {
            BackgroundLogStore.append(
                    context,
                    "服务",
                    "启动失败：" + e.getClass().getSimpleName()
            );
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
        createWakeLock();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "BatteryMinutePoller");
            thread.setDaemon(true);
            return thread;
        });
        step = Prefs.getStep(this);
        quietPaused = isQuietModeBlocking();
        startInForeground();
        registerSystemReceiver();
        checkSelectedDeviceNow();
        acquireWakeLock();
        startMinutePolling();
        BackgroundLogStore.append(this, "服务", "前台监测服务启动；固定每 60 秒检测一次");
        triggerImmediateSample("服务启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            sendAlertNotification(999, "测试通知", "后台提醒通道工作正常");
            BackgroundLogStore.append(this, "通知", "发送测试通知");
        }

        if (!Prefs.shouldKeepServiceRunning(this)) {
            BackgroundLogStore.append(this, "服务", "配置要求停止服务");
            stopSelf();
            return START_NOT_STICKY;
        }

        refreshInProcess();
        return START_STICKY;
    }

    private void refreshInProcess() {
        if (!Prefs.shouldKeepServiceRunning(this)) {
            stopSelf();
            return;
        }
        synchronized (stateLock) {
            step = Prefs.getStep(this);
            updateQuietPauseStateLocked();
            acquireWakeLockLocked();
            ensureMinutePollingLocked();
            updateForegroundLocked();
        }
        if (Prefs.isBluetoothAutoEnabled(this)) checkSelectedDeviceNow();
    }

    @Override
    public void onDestroy() {
        BackgroundLogStore.append(this, "服务", "前台监测服务停止");
        if (instance == this) instance = null;
        synchronized (stateLock) {
            if (pollFuture != null) pollFuture.cancel(true);
            pollFuture = null;
            releaseWakeLockLocked();
        }
        if (scheduler != null) scheduler.shutdownNow();
        if (receiverRegistered) {
            try {
                unregisterReceiver(systemReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerSystemReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        filter.addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(systemReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void startMinutePolling() {
        synchronized (stateLock) {
            ensureMinutePollingLocked();
        }
    }

    private void ensureMinutePollingLocked() {
        if (scheduler == null || scheduler.isShutdown()) return;
        if (pollFuture != null && !pollFuture.isCancelled() && !pollFuture.isDone()) return;
        pollFuture = scheduler.scheduleAtFixedRate(
                () -> sampleBatteryDirect("定时"),
                POLL_SECONDS,
                POLL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private void triggerImmediateSample(String source) {
        ScheduledExecutorService local = scheduler;
        if (local == null || local.isShutdown()) return;
        local.execute(() -> sampleBatteryDirect(source));
    }

    private void sampleBatteryDirect(String source) {
        BatteryManager battery = getSystemService(BatteryManager.class);
        if (battery == null) {
            BackgroundLogStore.append(this, "检测", source + "：BatteryManager 不可用");
            return;
        }

        int level;
        boolean nowCharging;
        try {
            level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            nowCharging = battery.isCharging();
        } catch (RuntimeException e) {
            BackgroundLogStore.append(this, "检测", source + "：读取失败 " + e.getClass().getSimpleName());
            return;
        }
        if (level < 0 || level > 100) {
            BackgroundLogStore.append(this, "检测", source + "：异常电量值 " + level);
            return;
        }

        synchronized (stateLock) {
            processBatterySampleLocked(level, nowCharging, source);
        }
    }

    private void processBatterySampleLocked(int level, boolean nowCharging, String source) {
        currentLevel = level;
        charging = nowCharging;
        step = Prefs.getStep(this);
        lastSampleAt = System.currentTimeMillis();
        updateQuietPauseStateLocked();

        boolean alertsAllowed = Prefs.isEnabled(this) && !quietPaused;

        if (!charging || !alertsAllowed) {
            clearSessionTargetsLocked();
        } else if (nextTarget <= 0 && level < 100) {
            beginSessionLocked(level);
        }

        if (charging && alertsAllowed && nextTarget > 0 && level >= nextTarget) {
            while (nextTarget > 0 && nextTarget <= level && nextTarget <= 100) {
                notifyThreshold(nextTarget, level);
                nextTarget += step;
                if (nextTarget > 100) nextTarget = -1;
            }
        }

        Prefs.setRuntimeState(this, level, charging, nextTarget);

        BackgroundLogStore.append(
                this,
                "检测",
                source
                        + " | 电量=" + level + "%"
                        + " | 充电=" + (charging ? "是" : "否")
                        + " | 总开关=" + (Prefs.isEnabled(this) ? "开" : "关")
                        + " | 静音暂停=" + (quietPaused ? "是" : "否")
                        + " | 蓝牙联动=" + (Prefs.isBluetoothAutoEnabled(this) ? "开" : "关")
                        + " | 下次提醒=" + (nextTarget > 0 ? nextTarget + "%" : "—")
        );

        acquireWakeLockLocked();
        ensureMinutePollingLocked();
        updateForegroundLocked();
    }

    private void createWakeLock() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":minute_monitor"
        );
        wakeLock.setReferenceCounted(false);
    }

    private void acquireWakeLock() {
        synchronized (stateLock) {
            acquireWakeLockLocked();
        }
    }

    @SuppressLint("WakelockTimeout")
    private void acquireWakeLockLocked() {
        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                wakeLock.acquire();
                BackgroundLogStore.append(this, "服务", "已获取 partial wakelock，保证每分钟检测");
            } catch (RuntimeException e) {
                BackgroundLogStore.append(this, "服务", "获取 wakelock 失败：" + e.getClass().getSimpleName());
            }
        }
    }

    private void releaseWakeLockLocked() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isWakeLockHeldLocked() {
        return wakeLock != null && wakeLock.isHeld();
    }

    private void checkSelectedDeviceNow() {
        if (!Prefs.isBluetoothAutoEnabled(this) || !hasBluetoothPermission()) return;
        String address = Prefs.getBluetoothAddress(this);
        if (address.isEmpty()) return;
        Boolean connected = queryDeviceConnected(address);
        if (Boolean.TRUE.equals(connected)) Prefs.setEnabled(this, true);
        else if (Boolean.FALSE.equals(connected)) Prefs.setEnabled(this, false);
    }

    private Boolean queryDeviceConnected(String address) {
        if (!hasBluetoothPermission()) return null;
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            if (manager == null) return null;
            BluetoothAdapter adapter = manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            BluetoothDevice target = adapter.getRemoteDevice(address);

            try {
                Method method = BluetoothDevice.class.getMethod("isConnected");
                Object value = method.invoke(target);
                if (value instanceof Boolean) return (Boolean) value;
            } catch (Exception ignored) {
            }

            try {
                if (Build.VERSION.SDK_INT >= 31
                        && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
                List<BluetoothDevice> devices = manager.getConnectedDevices(BluetoothProfile.GATT);
                for (BluetoothDevice device : devices) {
                    if (address.equalsIgnoreCase(device.getAddress())) return true;
                }
            } catch (SecurityException ignored) {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void updateQuietPauseStateLocked() {
        boolean newPaused = isQuietModeBlocking();
        if (newPaused == quietPaused) return;
        quietPaused = newPaused;
        if (quietPaused) {
            clearSessionTargetsLocked();
        } else if (Prefs.isEnabled(this) && charging && currentLevel >= 0) {
            beginSessionLocked(currentLevel);
        }
    }

    private boolean isQuietModeBlocking() {
        if (!Prefs.pauseOnQuietMode(this)) return false;
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return true;

        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null && notifications.isNotificationPolicyAccessGranted()) {
            int filter = notifications.getCurrentInterruptionFilter();
            return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                    && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
        return false;
    }

    private void beginSessionLocked(int level) {
        sessionStart = level;
        step = Prefs.getStep(this);
        int target = level + step;
        nextTarget = target <= 100 ? target : -1;
    }

    private void clearSessionTargetsLocked() {
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
        Notification notification;
        synchronized (stateLock) {
            notification = buildForegroundNotificationLocked();
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(MONITOR_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(MONITOR_ID, notification);
        }
    }

    private Notification buildForegroundNotificationLocked() {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, DashboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String sampleTime = lastSampleAt > 0
                ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(lastSampleAt))
                : "等待首次检测";
        String title = "每分钟电量检测运行中";
        String text = "电量 " + (currentLevel >= 0 ? currentLevel + "%" : "--")
                + " · " + (charging ? "充电中" : "未充电")
                + " · 上次 " + sampleTime
                + (quietPaused ? " · 提醒暂停" : "")
                + (isWakeLockHeldLocked() ? " · 每分钟唤醒" : "");

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

    private void updateForegroundLocked() {
        getSystemService(NotificationManager.class).notify(MONITOR_ID, buildForegroundNotificationLocked());
    }

    private void notifyThreshold(int threshold, int observed) {
        int gained = sessionStart >= 0 ? Math.max(0, threshold - sessionStart) : step;
        sendAlertNotification(
                ALERT_BASE + threshold,
                "电量达到 " + threshold + "%",
                "当前约 " + observed + "% · 本次充电已提升 " + gained + "%"
        );
        BackgroundLogStore.append(
                this,
                "提醒",
                "触发 " + threshold + "% 提醒；当前 " + observed + "%"
        );
    }

    private void sendAlertNotification(int id, String title, String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, id, new Intent(this, DashboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        getSystemService(NotificationManager.class).notify(id, notification);
    }
}
