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
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ReliableBatteryMonitorService extends Service {
    public static final String ACTION_REFRESH = "com.zhuchenyu.batterystep.REFRESH_V6";
    public static final String ACTION_TEST = "com.zhuchenyu.batterystep.TEST_ALERT";

    private static final String CHANNEL_MONITOR = "battery_monitor";
    private static final String CHANNEL_ALERT = "battery_alert";
    private static final int MONITOR_ID = 1001;
    private static final int ALERT_BASE = 4000;
    private static final long POLL_MS = 60_000L;
    private static final long SIGNAL_DEDUPE_MS = 30_000L;

    private static volatile ReliableBatteryMonitorService instance;

    private final Object stateLock = new Object();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> watchdogFuture;
    private long watchdogExpectedElapsed;
    private boolean receiverRegistered;
    private boolean charging;
    private boolean quietPaused;
    private int currentLevel = -1;
    private int sessionStart = -1;
    private int nextTarget = -1;
    private int step = 1;
    private PowerManager.WakeLock wakeLock;
    private long lastSampleAt;
    private long lastSampleElapsed;
    private long sampleSequence;

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_TIME_TICK.equals(action)) {
                enqueueSample("系统分钟广播", SIGNAL_DEDUPE_MS);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)
                    || Intent.ACTION_POWER_DISCONNECTED.equals(action)
                    || Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                enqueueSample("系统电源/电量事件", SIGNAL_DEDUPE_MS);
            } else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)
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
            BackgroundLogStore.append(context, "服务", "启动失败：" + describeThrowable(e));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels();
        createWakeLock();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "BatteryMinuteWatchdog");
            thread.setDaemon(false);
            thread.setUncaughtExceptionHandler((t, e) ->
                    BackgroundLogStore.append(
                            ReliableBatteryMonitorService.this,
                            "异常",
                            "后台线程未捕获异常：" + describeThrowable(e)
                    ));
            return thread;
        });
        step = Prefs.getStep(this);
        quietPaused = isQuietModeBlocking();
        startInForeground();
        registerSystemReceiver();
        checkSelectedDeviceNow();
        acquireWakeLock();
        synchronized (stateLock) {
            scheduleNextWatchdogLocked(POLL_MS);
        }
        BackgroundLogStore.append(
                this,
                "服务",
                "前台监测服务启动；主心跳=系统 ACTION_TIME_TICK；兜底=60秒自恢复看门狗"
        );
        enqueueSample("服务启动", 0L);
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
            ensureWatchdogLocked();
            updateForegroundLocked();
        }
        if (Prefs.isBluetoothAutoEnabled(this)) checkSelectedDeviceNow();
        CompanionWatchManager.ensureObservingIfAssociated(this);
        enqueueSample("配置刷新", SIGNAL_DEDUPE_MS);
    }

    @Override
    public void onDestroy() {
        BackgroundLogStore.append(this, "服务", "前台监测服务停止");
        if (instance == this) instance = null;
        synchronized (stateLock) {
            if (watchdogFuture != null) watchdogFuture.cancel(true);
            watchdogFuture = null;
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
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
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

    private void ensureWatchdogLocked() {
        if (scheduler == null || scheduler.isShutdown()) return;
        if (watchdogFuture != null && !watchdogFuture.isCancelled() && !watchdogFuture.isDone()) return;
        scheduleNextWatchdogLocked(POLL_MS);
    }

    private void scheduleNextWatchdogLocked(long delayMs) {
        if (scheduler == null || scheduler.isShutdown()) return;
        long delay = Math.max(1_000L, delayMs);
        watchdogExpectedElapsed = SystemClock.elapsedRealtime() + delay;
        try {
            watchdogFuture = scheduler.schedule(
                    this::runWatchdogSafely,
                    delay,
                    TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException e) {
            BackgroundLogStore.append(this, "异常", "看门狗调度被拒绝：" + describeThrowable(e));
        }
    }

    private void runWatchdogSafely() {
        long expected;
        synchronized (stateLock) {
            expected = watchdogExpectedElapsed;
            watchdogFuture = null;
        }
        long now = SystemClock.elapsedRealtime();
        long lateMs = expected > 0 ? Math.max(0L, now - expected) : 0L;
        try {
            runSampleSafely("60秒看门狗 | 调度延迟=" + lateMs + "ms", SIGNAL_DEDUPE_MS);
        } catch (Throwable t) {
            BackgroundLogStore.append(this, "异常", "看门狗外层异常：" + describeThrowable(t));
        } finally {
            synchronized (stateLock) {
                if (Prefs.shouldKeepServiceRunning(this)
                        && scheduler != null
                        && !scheduler.isShutdown()) {
                    scheduleNextWatchdogLocked(POLL_MS);
                }
            }
        }
    }

    private void enqueueSample(String source, long minimumGapMs) {
        ScheduledExecutorService local = scheduler;
        if (local == null || local.isShutdown()) return;
        try {
            local.execute(() -> runSampleSafely(source, minimumGapMs));
        } catch (RejectedExecutionException e) {
            BackgroundLogStore.append(this, "异常", "检测任务入队失败：" + describeThrowable(e));
        }
    }

    private void runSampleSafely(String source, long minimumGapMs) {
        try {
            long nowElapsed = SystemClock.elapsedRealtime();
            synchronized (stateLock) {
                if (minimumGapMs > 0
                        && lastSampleElapsed > 0
                        && nowElapsed - lastSampleElapsed < minimumGapMs) {
                    return;
                }
            }
            sampleBatteryDirect(source);
        } catch (Throwable t) {
            BackgroundLogStore.append(this, "异常", source + " 执行失败：" + describeThrowable(t));
            synchronized (stateLock) {
                ensureWatchdogLocked();
            }
        }
    }

    private void sampleBatteryDirect(String source) {
        BatteryManager battery = getSystemService(BatteryManager.class);
        if (battery == null) {
            throw new IllegalStateException("BatteryManager unavailable");
        }

        int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean nowCharging = battery.isCharging();
        if (level < 0 || level > 100) {
            throw new IllegalStateException("Invalid battery level " + level);
        }

        synchronized (stateLock) {
            processBatterySampleLocked(level, nowCharging, source);
        }
    }

    private void processBatterySampleLocked(int level, boolean nowCharging, String source) {
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        long gapMs = lastSampleElapsed > 0 ? nowElapsed - lastSampleElapsed : 0L;

        currentLevel = level;
        charging = nowCharging;
        step = Prefs.getStep(this);
        lastSampleAt = nowWall;
        lastSampleElapsed = nowElapsed;
        sampleSequence++;
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
                "#" + sampleSequence
                        + " " + source
                        + " | 距上次=" + (gapMs > 0 ? gapMs + "ms" : "首次")
                        + " | 电量=" + level + "%"
                        + " | 充电=" + (charging ? "是" : "否")
                        + " | 总开关=" + (Prefs.isEnabled(this) ? "开" : "关")
                        + " | 静音暂停=" + (quietPaused ? "是" : "否")
                        + " | 蓝牙联动=" + (Prefs.isBluetoothAutoEnabled(this) ? "开" : "关")
                        + " | 系统伴侣=" + (CompanionWatchManager.isAssociated(this) ? "已关联" : "未关联")
                        + " | 下次提醒=" + (nextTarget > 0 ? nextTarget + "%" : "—")
        );

        acquireWakeLockLocked();
        ensureWatchdogLocked();
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
                BackgroundLogStore.append(this, "服务", "已获取 partial wakelock");
            } catch (RuntimeException e) {
                BackgroundLogStore.append(this, "服务", "获取 wakelock 失败：" + describeThrowable(e));
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
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
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
        if (manager == null) return;
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
                + (isWakeLockHeldLocked() ? " · 双通道心跳" : "");

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
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            manager.notify(MONITOR_ID, buildForegroundNotificationLocked());
        } catch (RuntimeException e) {
            BackgroundLogStore.append(this, "异常", "刷新常驻通知失败：" + describeThrowable(e));
        }
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
        try {
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
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(id, notification);
        } catch (RuntimeException e) {
            BackgroundLogStore.append(this, "异常", "发送提醒失败：" + describeThrowable(e));
        }
    }

    private static String describeThrowable(Throwable throwable) {
        if (throwable == null) return "unknown";
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
    }
}
