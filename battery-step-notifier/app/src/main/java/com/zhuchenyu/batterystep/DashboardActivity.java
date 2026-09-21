package com.zhuchenyu.batterystep;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DashboardActivity extends Activity {
    private static final int REQ_BT = 201;
    private static final int REQ_NOTIFICATIONS = 202;

    private Switch enabledSwitch;
    private Switch hideRecentsSwitch;
    private Switch bluetoothSwitch;
    private Switch quietSwitch;
    private SeekBar stepSeek;
    private TextView stepText;
    private TextView heroStatus;
    private TextView bluetoothSummary;
    private TextView quietSummary;
    private TextView batteryValue;
    private TextView chargingValue;
    private TextView reminderIntervalValue;
    private TextView nextReminderValue;
    private TextView serviceValue;
    private TextView bluetoothValue;
    private TextView quietValue;
    private TextView footerVersion;
    private Button bluetoothButton;
    private Button dndButton;
    private Button notificationButton;
    private Button batteryOptimizationButton;
    private Button autoStartButton;
    private Button companionButton;
    private Button testNotificationButton;
    private Button appSettingsButton;
    private Button backgroundLogButton;
    private boolean syncingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);
        bindViews();
        setupListeners();
        bindState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CompanionWatchManager.ensureObservingIfAssociated(this);
        CompanionSetupNotifier.notifyIfNeeded(this);
        bindState();
        BatteryMonitorService.applyConfig(this);
        RecentsHelper.setExcluded(this, Prefs.hideFromRecents(this));
    }

    private void bindViews() {
        enabledSwitch = findViewById(R.id.enabledSwitch);
        hideRecentsSwitch = findViewById(R.id.hideRecentsSwitch);
        bluetoothSwitch = findViewById(R.id.bluetoothSwitch);
        quietSwitch = findViewById(R.id.quietSwitch);
        stepSeek = findViewById(R.id.stepSeek);
        stepText = findViewById(R.id.stepText);
        heroStatus = findViewById(R.id.heroStatus);
        bluetoothSummary = findViewById(R.id.bluetoothSummary);
        quietSummary = findViewById(R.id.quietSummary);
        batteryValue = findViewById(R.id.batteryValue);
        chargingValue = findViewById(R.id.chargingValue);
        reminderIntervalValue = findViewById(R.id.reminderIntervalValue);
        nextReminderValue = findViewById(R.id.nextReminderValue);
        serviceValue = findViewById(R.id.serviceValue);
        bluetoothValue = findViewById(R.id.bluetoothValue);
        quietValue = findViewById(R.id.quietValue);
        footerVersion = findViewById(R.id.footerVersion);
        bluetoothButton = findViewById(R.id.bluetoothButton);
        dndButton = findViewById(R.id.dndButton);
        notificationButton = findViewById(R.id.notificationButton);
        batteryOptimizationButton = findViewById(R.id.batteryOptimizationButton);
        autoStartButton = findViewById(R.id.autoStartButton);
        companionButton = findViewById(R.id.companionButton);
        testNotificationButton = findViewById(R.id.testNotificationButton);
        appSettingsButton = findViewById(R.id.appSettingsButton);
        backgroundLogButton = findViewById(R.id.backgroundLogButton);
    }

    private void setupListeners() {
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingUi) return;
            Prefs.setEnabled(this, checked);
            BackgroundLogStore.append(this, "设置", "总开关=" + (checked ? "开" : "关"));
            if (checked) requestNotificationPermission();
            BatteryMonitorService.applyConfig(this);
            renderStatus();
        });

        hideRecentsSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingUi) return;
            Prefs.setHideFromRecents(this, checked);
            RecentsHelper.setExcluded(this, checked);
        });

        bluetoothSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingUi) return;
            if (checked && !hasBluetoothPermission()) {
                requestBluetoothPermission();
                return;
            }
            Prefs.setBluetoothAutoEnabled(this, checked);
            BackgroundLogStore.append(this, "设置", "蓝牙联动=" + (checked ? "开" : "关"));
            if (checked && Prefs.getBluetoothAddress(this).isEmpty()) showBluetoothPicker();
            if (checked) CompanionSetupNotifier.notifyIfNeeded(this);
            BatteryMonitorService.applyConfig(this);
            bindState();
        });

        quietSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingUi) return;
            Prefs.setPauseOnQuietMode(this, checked);
            BackgroundLogStore.append(this, "设置", "静音/勿扰暂停提醒=" + (checked ? "开" : "关"));
            if (checked && !hasDndAccess()) openDndSettings();
            BatteryMonitorService.applyConfig(this);
            bindState();
        });

        stepSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                stepText.setText((progress + 1) + "%");
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress() + 1;
                Prefs.setStep(DashboardActivity.this, value);
                BackgroundLogStore.append(DashboardActivity.this, "设置", "提醒间隔=" + value + "%");
                BatteryMonitorService.applyConfig(DashboardActivity.this);
                Toast.makeText(DashboardActivity.this,
                        "已保存：每提升 " + value + "% 提醒",
                        Toast.LENGTH_SHORT).show();
                bindState();
            }
        });

        bluetoothButton.setOnClickListener(v -> {
            if (!hasBluetoothPermission()) requestBluetoothPermission();
            else showBluetoothPicker();
        });
        dndButton.setOnClickListener(v -> openDndSettings());
        notificationButton.setOnClickListener(v -> {
            if (hasNotificationPermission()) {
                Toast.makeText(this, "通知权限已开启", Toast.LENGTH_SHORT).show();
            } else {
                requestNotificationPermission();
            }
        });
        batteryOptimizationButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        autoStartButton.setOnClickListener(v -> openAutoStartSettings());
        companionButton.setOnClickListener(v ->
                startActivity(new Intent(this, CompanionSetupActivity.class)));
        testNotificationButton.setOnClickListener(v -> {
            requestNotificationPermission();
            BatteryMonitorService.sendTestAlert(this);
            Toast.makeText(this, "已请求发送测试通知", Toast.LENGTH_SHORT).show();
        });
        appSettingsButton.setOnClickListener(v -> openAppDetails());
        backgroundLogButton.setOnClickListener(v ->
                startActivity(new Intent(this, BackgroundLogActivity.class)));
    }

    private void bindState() {
        if (enabledSwitch == null) return;
        syncingUi = true;
        enabledSwitch.setChecked(Prefs.isEnabled(this));
        hideRecentsSwitch.setChecked(Prefs.hideFromRecents(this));
        bluetoothSwitch.setChecked(Prefs.isBluetoothAutoEnabled(this));
        quietSwitch.setChecked(Prefs.pauseOnQuietMode(this));
        int step = Prefs.getStep(this);
        stepSeek.setProgress(step - 1);
        stepText.setText(step + "%");
        bluetoothSummary.setText(Prefs.isBluetoothAutoEnabled(this)
                ? Prefs.getBluetoothName(this) + " · 连接开启 / 断开关闭"
                : "未启用");
        quietSummary.setText(Prefs.pauseOnQuietMode(this)
                ? (hasDndAccess() ? "已启用 · 只暂停提醒，检测继续" : "已启用 · 免打扰权限待授权")
                : "未启用");
        bluetoothButton.setText(Prefs.getBluetoothAddress(this).isEmpty()
                ? "选择联动设备"
                : "更换联动设备 · " + Prefs.getBluetoothName(this));
        dndButton.setText(hasDndAccess() ? "免打扰权限 · 已授权" : "免打扰权限 · 需要授权");
        notificationButton.setText(hasNotificationPermission()
                ? "✓  通知权限已开启"
                : "!  通知权限需开启");
        batteryOptimizationButton.setText(isIgnoringBatteryOptimizations()
                ? "✓  电池优化已忽略"
                : "!  电池优化需忽略");
        autoStartButton.setText("自启动管理 · 请确认");
        if (!CompanionWatchManager.isSupported(this)) {
            companionButton.setText("系统手表伴侣 · 当前系统不可用");
            companionButton.setEnabled(false);
        } else if (CompanionWatchManager.isAssociated(this)) {
            companionButton.setText("✓  系统手表伴侣 · 已关联");
            companionButton.setEnabled(true);
        } else {
            companionButton.setText("!  系统手表伴侣 · 未关联");
            companionButton.setEnabled(true);
        }
        footerVersion.setText("版本 " + BuildConfig.VERSION_NAME + " · 电量阶梯提醒");
        syncingUi = false;
        renderStatus();
    }

    private void renderStatus() {
        BatteryManager battery = getSystemService(BatteryManager.class);
        int level = battery == null ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = battery != null && battery.isCharging();
        boolean enabled = Prefs.isEnabled(this);
        boolean quietBlocked = Prefs.pauseOnQuietMode(this) && isQuietModeNow();
        int step = Prefs.getStep(this);

        if (!enabled) {
            setHero("已关闭", R.drawable.bg_status_off, R.color.status_off);
        } else if (quietBlocked) {
            String suffix = level >= 0 && level <= 100 ? " · " + level + "%" : "";
            setHero("每分钟检测中" + suffix + " · 提醒暂停", R.drawable.bg_status_warn, R.color.status_warn);
        } else if (charging) {
            String suffix = level >= 0 && level <= 100 ? " · " + level + "%" : "";
            setHero("每分钟检测中" + suffix + " · 充电中", R.drawable.bg_status_good, R.color.status_good);
        } else {
            String suffix = level >= 0 && level <= 100 ? " · " + level + "%" : "";
            setHero("每分钟检测中" + suffix + " · 未充电", R.drawable.bg_status_good, R.color.status_good);
        }

        batteryValue.setText(level >= 0 && level <= 100 ? level + "%" : "未知");
        chargingValue.setText(charging ? "正在充电" : "未充电");
        reminderIntervalValue.setText(step + "%");

        if (!enabled) {
            nextReminderValue.setText("—");
        } else if (quietBlocked) {
            nextReminderValue.setText("提醒暂停，检测继续");
        } else if (!charging) {
            nextReminderValue.setText("未充电，不触发提醒");
        } else if (level >= 0 && level < 100) {
            nextReminderValue.setText("预计 " + Math.min(100, level + step) + "%");
        } else {
            nextReminderValue.setText("—");
        }

        serviceValue.setText(Prefs.shouldKeepServiceRunning(this)
                ? "系统分钟广播 + 60 秒看门狗"
                : "已停止");
        bluetoothValue.setText(Prefs.isBluetoothAutoEnabled(this)
                ? Prefs.getBluetoothName(this)
                : "未启用");
        quietValue.setText(Prefs.pauseOnQuietMode(this)
                ? (quietBlocked ? "提醒已暂停，检测继续" : "未触发")
                : "未启用");
    }

    private void setHero(String text, int backgroundRes, int colorRes) {
        heroStatus.setText(text);
        heroStatus.setBackgroundResource(backgroundRes);
        heroStatus.setTextColor(getColor(colorRes));
    }

    private boolean isQuietModeNow() {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return true;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.isNotificationPolicyAccessGranted()) {
            int filter = manager.getCurrentInterruptionFilter();
            return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                    && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
        return false;
    }

    private void showBluetoothPicker() {
        if (!hasBluetoothPermission()) return;
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                Toast.makeText(this, "没有已配对设备", Toast.LENGTH_LONG).show();
                return;
            }
            List<BluetoothDevice> devices = new ArrayList<>(bonded);
            devices.sort((a, b) -> safeName(a).compareToIgnoreCase(safeName(b)));
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice device = devices.get(i);
                labels[i] = safeName(device) + "\n" + device.getAddress();
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择联动设备")
                    .setItems(labels, (dialog, which) -> {
                        BluetoothDevice device = devices.get(which);
                        Prefs.setBluetoothDevice(this, device.getAddress(), safeName(device));
                        Prefs.setBluetoothAutoEnabled(this, true);
                        BackgroundLogStore.append(this, "设置", "联动设备=" + safeName(device));
                        CompanionSetupNotifier.notifyIfNeeded(this);
                        BatteryMonitorService.applyConfig(this);
                        bindState();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (SecurityException e) {
            requestBluetoothPermission();
        }
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "未命名设备" : name;
        } catch (SecurityException e) {
            return "蓝牙设备";
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotificationPermission() {
        if (!hasNotificationPermission() && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Prefs.setBluetoothAutoEnabled(this, true);
                showBluetoothPicker();
            } else {
                Prefs.setBluetoothAutoEnabled(this, false);
                Toast.makeText(this, "需要附近设备权限才能识别手表", Toast.LENGTH_LONG).show();
            }
        }
        bindState();
    }

    private void openAutoStartSettings() {
        String[][] candidates = new String[][]{
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity"}
        };
        for (String[] candidate : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(candidate[0], candidate[1]));
                if (getPackageManager().resolveActivity(intent, 0) != null) {
                    startActivity(intent);
                    Toast.makeText(this, "请找到“电量阶梯提醒”并允许自启动", Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        openAppDetails();
        Toast.makeText(this,
                "系统未开放直达入口，请在应用详情/电池中确认自启动和后台活动权限",
                Toast.LENGTH_LONG).show();
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, "已经忽略电池优化", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                openAppDetails();
            }
        }
    }

    private boolean hasDndAccess() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationPolicyAccessGranted();
    }

    private void openDndSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())
            ));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
