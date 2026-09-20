package com.zhuchenyu.batterystep;

import android.Manifest;
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
    public static final String ACTION_REFRESH = "com.zhuchenyu.batterystep.REFRESH_V3";
    public static final String ACTION_TEST = "com.zhuchenyu.batterystep.TEST_ALERT";

    private static final String CHANNEL_MONITOR = "battery_monitor";
    private static final String CHANNEL_ALERT = "battery_alert";
    private static final int MONITOR_ID = 1001;
    private static final int ALERT_BASE = 4000;
    private static final long ACTIVE_POLL_SECONDS = 5L;
    private static final long IDLE_POLL_SECONDS = 60L;
    private static final long FOREGROUND_REFRESH_MS = 15_000L;

    private final Object stateLock = new Object();
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollFuture;
    private long scheduledIntervalSeconds = -1L;
    private boolean receiverRegistered;
    private boolean charging;
    private boolean quietPaused;
    private int currentLevel = -1;
    private int sessionStart = -1;
    private int nextTarget = -1;
    private int step = 1;
    private PowerManager.WakeLock wakeLock;
    private long lastForegroundRefresh;
    private int lastForegroundLevel = -1;

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)
                    || Intent.ACTION_POWER_CONNECTED.equals(action)
                    || Intent.ACTION_POWER_DISCONNECTED.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                triggerImmediateSample();
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                    || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                handleBluetoothEvent(intent, BluetoothDevice.ACTION_ACL_CONNECTED.equals(action));
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED
                );
                if (state == BluetoothAdapter.STATE_CONNECTED) handleBluetoothEvent(intent, true);
                if (state == BluetoothAdapter.STATE_DISCONNECTED) handleBluetoothEvent(intent, false);
            } else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)
                    || NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)
                    || NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED.equals(action)) {
                synchronized (stateLock) {
                    updateQuietPauseStateLocked();
                    syncWakeLockLocked();
                    ensurePollingCadenceLocked();
                    updateForegroundLocked(true);
                }
            }
        }
    };

    public static void start(Context context) {
        startWithAction(context, null);
    }

    public static void refresh(Context context) {
        startWithAction(context, ACTION_REFRESH);
    }

    public static void sendTestAlert(Context context) {
        startWithAction(context, ACTION_TEST);
    }

    public static void applyConfig(Context context) {
        if (Prefs.shouldKeepServiceRunning(context)) refresh(context);
        else stop(context);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ReliableBatteryMonitorService.class));
    }

    private static void startWithAction(Context context, String action) {
        Intent intent = new Intent(context, ReliableBatteryMonitorService.class);
        if (action != null) intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        createWakeLock();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BatteryStepPoller");
            t.setDaemon(true);
            return t;
        });
        step = Prefs.getStep(this);
        quietPaused = isQuietModeBlocking();
        startInForeground();
        registerSystemReceiver();
        checkSelectedDeviceNow();
        triggerImmediateSample();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            sendAlertNotification(999, "测试通知", "后台提醒通道工作正常");
        }

        if (!Prefs.shouldKeepServiceRunning(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        synchronized (stateLock) {
            step = Prefs.getStep(this);
            updateQuietPauseStateLocked();
            ensurePollingCadenceLocked();
        }
        if (Prefs.isBluetoothAutoEnabled(this)) checkSelectedDeviceNow();
        triggerImmediateSample();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (stateLock) {
            if (pollFuture != null) pollFuture.cancel(true);
            pollFuture = null;
            scheduledIntervalSeconds = -1L;
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
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
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

    private void triggerImmediateSample() {
        ScheduledExecutorService local = scheduler;
        if (local == null || local.isShutdown()) return;
        local.execute(this::sampleBatteryDirect);
    }

    private void sampleBatteryDirect() {
        BatteryManager battery = getSystemService(BatteryManager.class);
        if (battery == null) return;

        int level;
        boolean nowCharging;
        try {
            level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            nowCharging = battery.isCharging();
        } catch (RuntimeException e) {
            return;
        }
        if (level < 0 || level > 100) return;

        synchronized (stateLock) {
            processBatterySampleLocked(level, nowCharging);
        }
    }

    private void processBatterySampleLocked(int level, boolean nowCharging) {
        currentLevel = level;
        step = Prefs.getStep(this);
        updateQuietPauseStateLocked();

        boolean chargingChanged = charging != nowCharging;
        charging = nowCharging;

        if (!charging || !monitoringAllowedLocked()) {
            clearSessionTargetsLocked();
        } else if (nextTarget <= 0 && level < 100) {
            beginSessionLocked(level);
        }

        if (charging && monitoringAllowedLocked() && nextTarget > 0 && level >= nextTarget) {
            while (nextTarget > 0 && nextTarget <= level && nextTarget <= 100) {
                notifyThreshold(nextTarget, level);
                nextTarget += step;
                if (nextTarget > 100) nextTarget = -1;
            }
        }

        syncWakeLockLocked();
        ensurePollingCadenceLocked();
        updateForegroundLocked(
                chargingChanged
                        || level != lastForegroundLevel
                        || System.currentTimeMillis() - lastForegroundRefresh >= FOREGROUND_REFRESH_MS
        );
    }

    private void ensurePollingCadenceLocked() {
        if (scheduler == null || scheduler.isShutdown()) return;
        long desired = charging && monitoringAllowedLocked()
                ? ACTIVE_POLL_SECONDS : IDLE_POLL_SECONDS;
        if (pollFuture != null
                && !pollFuture.isCancelled()
                && !pollFuture.isDone()
                && scheduledIntervalSeconds == desired) {
            return;
        }
        if (pollFuture != null) pollFuture.cancel(false);
        scheduledIntervalSeconds = desired;
        pollFuture = scheduler.scheduleAtFixedRate(
                this::sampleBatteryDirect,
                desired,
                desired,
                TimeUnit.SECONDS
        );
    }

    private void createWakeLock() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":charging_monitor"
        );
        wakeLock.setReferenceCounted(false);
    }

    private void syncWakeLockLocked() {
        boolean shouldHold = charging && monitoringAllowedLocked();
        if (shouldHold) {
            if (wakeLock != null && !wakeLock.isHeld()) {
                try {
                    wakeLock.acquire();
                } catch (RuntimeException ignored) {
                }
            }
        } else {
            releaseWakeLockLocked();
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

    private void handleBluetoothEvent(Intent intent, boolean connected) {
        if (!Prefs.isBluetoothAutoEnabled(this) || !hasBluetoothPermission()) return;
        BluetoothDevice device = getBluetoothDevice(intent);
        if (device == null) return;
        String selected = Prefs.getBluetoothAddress(this);
        if (selected.isEmpty()) return;

        try {
            if (!selected.equalsIgnoreCase(device.getAddress())) return;
        } catch (SecurityException e) {
            return;
        }

        Prefs.setEnabled(this, connected);
        synchronized (stateLock) {
            if (!connected) clearSessionTargetsLocked();
            updateQuietPauseStateLocked();
            syncWakeLockLocked();
            ensurePollingCadenceLocked();
            updateForegroundLocked(true);
        }
        triggerImmediateSample();
    }

    @SuppressWarnings("deprecation")
    private BluetoothDevice getBluetoothDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
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
                for (BluetoothDevice d : devices) {
                    if (address.equalsIgnoreCase(d.getAddress())) return true;
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

    private boolean monitoringAllowedLocked() {
        return Prefs.isEnabled(this) && !quietPaused;
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
        Notification n;
        synchronized (stateLock) {
            n = buildForegroundNotificationLocked();
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(MONITOR_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(MONITOR_ID, n);
        }
    }

    private Notification buildForegroundNotificationLocked() {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, DashboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title;
        String text;
        String sampleTime = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        if (!Prefs.isEnabled(this)) {
            title = "自动化待机";
            text = Prefs.isBluetoothAutoEnabled(this)
                    ? "等待 " + Prefs.getBluetoothName(this) + " 连接后自动开启"
                    : "电量监测已关闭";
        } else if (quietPaused) {
            title = "免打扰 / 静音中，监测已暂停";
            text = "恢复普通响铃后自动继续";
        } else if (charging) {
            title = "正在监测充电电量";
            text = "直读 " + currentLevel + "% · "
                    + (nextTarget > 0 ? "下次 " + nextTarget + "%" : "暂无下一档")
                    + " · 采样 " + sampleTime
                    + (isWakeLockHeldLocked() ? " · 持续唤醒" : "");
        } else {
            title = "电量提醒已待机";
            text = "系统直读 " + currentLevel + "% · 未充电 · 采样 " + sampleTime;
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

    private void updateForegroundLocked(boolean force) {
        if (!force) return;
        lastForegroundRefresh = System.currentTimeMillis();
        lastForegroundLevel = currentLevel;
        getSystemService(NotificationManager.class).notify(MONITOR_ID, buildForegroundNotificationLocked());
    }

    private void notifyThreshold(int threshold, int observed) {
        int gained;
        synchronized (stateLock) {
            gained = sessionStart >= 0 ? Math.max(0, threshold - sessionStart) : step;
        }
        sendAlertNotification(
                ALERT_BASE + threshold,
                "电量达到 " + threshold + "%",
                "当前约 " + observed + "% · 本次充电已提升 " + gained + "%"
        );
    }

    private void sendAlertNotification(int id, String title, String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, id, new Intent(this, DashboardActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build();
        getSystemService(NotificationManager.class).notify(id, n);
    }
}
