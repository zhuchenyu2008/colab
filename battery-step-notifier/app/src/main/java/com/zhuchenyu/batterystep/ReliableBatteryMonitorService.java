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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.lang.reflect.Method;
import java.util.List;

public class ReliableBatteryMonitorService extends Service {
    public static final String ACTION_REFRESH = "com.zhuchenyu.batterystep.REFRESH_V2";
    public static final String ACTION_TEST = "com.zhuchenyu.batterystep.TEST_ALERT";

    private static final String CHANNEL_MONITOR = "battery_monitor";
    private static final String CHANNEL_ALERT = "battery_alert";
    private static final int MONITOR_ID = 1001;
    private static final int ALERT_BASE = 3000;
    private static final long CHARGING_POLL_MS = 15_000L;
    private static final long IDLE_POLL_MS = 60_000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private boolean charging;
    private boolean quietPaused;
    private int currentLevel = -1;
    private int sessionStart = -1;
    private int nextTarget = -1;
    private int step = 1;
    private PowerManager.WakeLock wakeLock;

    private final Runnable pollRunnable = this::sampleBatteryDirect;

    private final BroadcastReceiver systemReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)
                    || Intent.ACTION_POWER_CONNECTED.equals(action)
                    || Intent.ACTION_POWER_DISCONNECTED.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                sampleBatteryDirect();
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
                updateQuietPauseState();
                syncPollLoop();
                updateForeground();
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

    private static void startWithAction(Context context, String action) {
        Intent intent = new Intent(context, ReliableBatteryMonitorService.class);
        if (action != null) intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void applyConfig(Context context) {
        if (Prefs.shouldKeepServiceRunning(context)) refresh(context);
        else stop(context);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ReliableBatteryMonitorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        createWakeLock();
        step = Prefs.getStep(this);
        quietPaused = isQuietModeBlocking();
        startInForeground();
        registerSystemReceiver();
        checkSelectedDeviceNow();
        sampleBatteryDirect();

        handler.postDelayed(() -> {
            if (Prefs.isBluetoothAutoEnabled(this)) checkSelectedDeviceNow();
            sampleBatteryDirect();
        }, 1500L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TEST.equals(intent.getAction())) {
            sendAlertNotification(999, currentLevel, "测试通知", "后台提醒通道工作正常");
        }

        if (!Prefs.shouldKeepServiceRunning(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        step = Prefs.getStep(this);
        updateQuietPauseState();
        if (Prefs.isBluetoothAutoEnabled(this)) checkSelectedDeviceNow();
        sampleBatteryDirect();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseWakeLock();
        if (receiverRegistered) {
            try {
                unregisterReceiver(systemReceiver);
            } catch (IllegalArgumentException ignored) {
            }
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

    private void sampleBatteryDirect() {
        handler.removeCallbacks(pollRunnable);

        BatteryManager battery = getSystemService(BatteryManager.class);
        int level = -1;
        boolean nowCharging = charging;
        if (battery != null) {
            try {
                level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                nowCharging = battery.isCharging();
            } catch (RuntimeException ignored) {
            }
        }

        if (level < 0 || level > 100) {
            Intent sticky = null;
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
                } else {
                    sticky = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                }
            } catch (RuntimeException ignored) {
            }
            if (sticky != null) {
                int raw = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (raw >= 0 && scale > 0) level = Math.round(raw * 100f / scale);
                int status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                int plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                nowCharging = plugged != 0
                        || status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        }

        if (level >= 0 && level <= 100) processBatterySample(level, nowCharging);
        else syncPollLoop();
    }

    private void processBatterySample(int level, boolean nowCharging) {
        int previousLevel = currentLevel;
        currentLevel = level;
        step = Prefs.getStep(this);
        updateQuietPauseState();

        if (nowCharging && !charging) {
            charging = true;
            if (monitoringAllowed()) beginSession(level);
        } else if (!nowCharging && charging) {
            charging = false;
            clearSessionTargets();
        } else {
            charging = nowCharging;
        }

        if (!monitoringAllowed()) {
            clearSessionTargets();
        } else if (charging && nextTarget <= 0 && level < 100) {
            beginSession(level);
        }

        if (charging && monitoringAllowed() && nextTarget > 0 && level >= nextTarget) {
            while (nextTarget > 0 && nextTarget <= level && nextTarget <= 100) {
                notifyThreshold(nextTarget, level);
                nextTarget += step;
                if (nextTarget > 100) nextTarget = -1;
            }
        }

        // If the vendor reports a temporary backwards jump, never reset the target.
        if (previousLevel >= 0 && level < previousLevel && charging) {
            // Keep nextTarget unchanged.
        }

        syncPollLoop();
        updateForeground();
    }

    private void syncPollLoop() {
        handler.removeCallbacks(pollRunnable);
        if (!Prefs.shouldKeepServiceRunning(this)) {
            releaseWakeLock();
            return;
        }

        if (charging && monitoringAllowed()) {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean screenOff = power != null && !power.isInteractive();
            if (screenOff) refreshWakeLock();
            else releaseWakeLock();
            handler.postDelayed(pollRunnable, CHARGING_POLL_MS);
        } else {
            releaseWakeLock();
            handler.postDelayed(pollRunnable, IDLE_POLL_MS);
        }
    }

    private void createWakeLock() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    getPackageName() + ":reliable_charging_monitor"
            );
            wakeLock.setReferenceCounted(false);
        }
    }

    private void refreshWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (RuntimeException ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isWakeLockHeld() {
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
        if (!connected) clearSessionTargets();
        else if (charging && monitoringAllowed() && currentLevel >= 0) beginSession(currentLevel);
        sampleBatteryDirect();
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
                        && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
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

    private void updateQuietPauseState() {
        boolean newPaused = isQuietModeBlocking();
        if (newPaused == quietPaused) return;
        quietPaused = newPaused;
        if (quietPaused) clearSessionTargets();
        else if (Prefs.isEnabled(this) && charging && currentLevel >= 0) beginSession(currentLevel);
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

    private boolean monitoringAllowed() {
        return Prefs.isEnabled(this) && !quietPaused;
    }

    private void beginSession(int level) {
        sessionStart = level;
        step = Prefs.getStep(this);
        int target = level + step;
        nextTarget = target <= 100 ? target : -1;
    }

    private void clearSessionTargets() {
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

        String title;
        String text;
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
            String next = nextTarget > 0 ? "下次 " + nextTarget + "% 提醒" : "等待下一档";
            text = "直读 " + currentLevel + "% · " + next
                    + (isWakeLockHeld() ? " · 锁屏可靠监测" : "");
        } else {
            title = "电量提醒已待机";
            text = "系统直读 " + (currentLevel >= 0 ? currentLevel + "%" : "未知") + " · 未充电";
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
        int gained = sessionStart >= 0 ? Math.max(0, threshold - sessionStart) : step;
        sendAlertNotification(
                ALERT_BASE + threshold,
                observed,
                "电量达到 " + threshold + "%",
                "当前 " + observed + "% · 本次充电已提升 " + gained + "%"
        );
    }

    private void sendAlertNotification(int id, int observed, String title, String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, id, new Intent(this, MainActivity.class),
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
