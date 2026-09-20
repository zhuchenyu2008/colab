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
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private TextView statusText;
    private Button bluetoothButton;
    private Button batteryOptimizationButton;
    private Button exactAlarmButton;
    private Button dndButton;
    private boolean syncingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("电量阶梯提醒");
        setContentView(buildUi());
        bindState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindState();
        BatteryMonitorService.applyConfig(this);
        AlarmMonitor.applyConfig(this);
        RecentsHelper.setExcluded(this, Prefs.hideFromRecents(this));
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("电量阶梯提醒", 28), matchWrap());
        root.addView(text(
                "v11：充电提醒改由 Android 系统 AlarmManager 驱动。即使 App 后台线程被 ColorOS / OxygenOS 冻结，系统仍可按时唤醒接收器读取电量。\n",
                14
        ), matchWrap());

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("总开关");
        enabledSwitch.setTextSize(19);
        enabledSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncingUi) return;
            Prefs.setEnabled(this, checked);
            if (checked) {
                requestNotificationPermission();
                if (!AlarmMonitor.canScheduleExact(this)) requestExactAlarmAccess();
            }
            AlarmMonitor.clearSession(this);
            AlarmMonitor.applyConfig(this);
            BatteryMonitorService.applyConfig(this);
            renderStatus();
        });
        root.addView(enabledSwitch, matchWrap());

        hideRecentsSwitch = new Switch(this);
        hideRecentsSwitch.setText("在最近任务页面隐藏");
        hideRecentsSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncingUi) return;
            Prefs.setHideFromRecents(this, checked);
            RecentsHelper.setExcluded(this, checked);
        });
        root.addView(hideRecentsSwitch, matchWrap());

        root.addView(text("\n自动化", 20), matchWrap());

        bluetoothSwitch = new Switch(this);
        bluetoothSwitch.setText("指定手表连接=开启，断开=关闭");
        bluetoothSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncingUi) return;
            if (checked && !hasBluetoothPermission()) {
                requestBluetoothPermission();
                return;
            }
            Prefs.setBluetoothAutoEnabled(this, checked);
            if (checked && Prefs.getBluetoothAddress(this).isEmpty()) showBluetoothPicker();
            AlarmMonitor.clearSession(this);
            AlarmMonitor.applyConfig(this);
            BatteryMonitorService.applyConfig(this);
            renderStatus();
        });
        root.addView(bluetoothSwitch, matchWrap());

        bluetoothButton = new Button(this);
        bluetoothButton.setOnClickListener(v -> {
            if (!hasBluetoothPermission()) requestBluetoothPermission();
            else showBluetoothPicker();
        });
        root.addView(bluetoothButton, matchWrap());

        quietSwitch = new Switch(this);
        quietSwitch.setText("免打扰或静音时自动暂停");
        quietSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncingUi) return;
            Prefs.setPauseOnQuietMode(this, checked);
            if (checked && !hasDndAccess()) openDndSettings();
            AlarmMonitor.clearSession(this);
            AlarmMonitor.applyConfig(this);
            BatteryMonitorService.applyConfig(this);
            renderStatus();
        });
        root.addView(quietSwitch, matchWrap());

        dndButton = new Button(this);
        dndButton.setOnClickListener(v -> openDndSettings());
        root.addView(dndButton, matchWrap());

        root.addView(text("\n提醒间隔", 20), matchWrap());
        stepText = text("1%", 28);
        root.addView(stepText, matchWrap());
        stepSeek = new SeekBar(this);
        stepSeek.setMax(19);
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
                AlarmMonitor.clearSession(DashboardActivity.this);
                AlarmMonitor.applyConfig(DashboardActivity.this);
                BatteryMonitorService.applyConfig(DashboardActivity.this);
                Toast.makeText(DashboardActivity.this,
                        "已保存：每提升 " + value + "% 提醒",
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(stepSeek, matchWrap());

        root.addView(text("\n后台可靠性", 20), matchWrap());

        exactAlarmButton = new Button(this);
        exactAlarmButton.setOnClickListener(v -> requestExactAlarmAccess());
        root.addView(exactAlarmButton, matchWrap());
        root.addView(text(
                "这是 v11 的关键权限：允许系统在 App 被后台冻结时，仍按约 30 秒间隔唤醒一次进行充电电量检查。\n",
                12
        ), matchWrap());

        Button autoStartButton = new Button(this);
        autoStartButton.setText("打开自启动管理（OPPO / 一加）");
        autoStartButton.setOnClickListener(v -> openAutoStartSettings());
        root.addView(autoStartButton, matchWrap());
        root.addView(text(
                "请在系统页面手动允许“自启动/自动启动”。App 同时监听开机与解锁系统事件。\n",
                12
        ), matchWrap());

        batteryOptimizationButton = new Button(this);
        batteryOptimizationButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        root.addView(batteryOptimizationButton, matchWrap());

        Button test = new Button(this);
        test.setText("发送测试通知");
        test.setOnClickListener(v -> {
            requestNotificationPermission();
            BatteryMonitorService.sendTestAlert(this);
            Toast.makeText(this, "已请求发送测试通知", Toast.LENGTH_SHORT).show();
        });
        root.addView(test, matchWrap());

        Button appSettings = new Button(this);
        appSettings.setText("打开应用后台 / 电池详情");
        appSettings.setOnClickListener(v -> openAppDetails());
        root.addView(appSettings, matchWrap());

        statusText = text("", 14);
        statusText.setPadding(0, dp(18), 0, dp(18));
        root.addView(statusText, matchWrap());

        root.addView(text(
                "说明：充电时主要由系统精确闹钟每 30 秒唤醒一次 Receiver，直接读取 BatteryManager。前台服务仍保留作状态展示和蓝牙/静音辅助，但提醒不再依赖它自己的 Handler 定时器。",
                12
        ), matchWrap());

        return scroll;
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
        bluetoothButton.setText("选择蓝牙设备 · " + Prefs.getBluetoothName(this));
        dndButton.setText(hasDndAccess() ? "免打扰权限：已授权" : "免打扰权限：点此授权");
        batteryOptimizationButton.setText(isIgnoringBatteryOptimizations()
                ? "电池优化：已忽略（后台更可靠）"
                : "电池优化：点此允许忽略");
        exactAlarmButton.setText(AlarmMonitor.canScheduleExact(this)
                ? "闹钟和提醒：已授权 ✅"
                : "闹钟和提醒：点此授权（必需）");
        syncingUi = false;
        renderStatus();
    }

    private void renderStatus() {
        if (statusText == null) return;
        BatteryManager battery = getSystemService(BatteryManager.class);
        int level = battery == null ? -1
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = battery != null && battery.isCharging();
        StringBuilder s = new StringBuilder();
        s.append(Prefs.isEnabled(this) ? "总开关：开启" : "总开关：关闭");
        s.append(" · 系统直读电量：")
                .append(level >= 0 && level <= 100 ? level + "%" : "未知");
        s.append(charging ? " · 正在充电" : " · 未充电");
        if (Prefs.isBluetoothAutoEnabled(this)) {
            s.append(" · 联动设备：").append(Prefs.getBluetoothName(this));
        }
        if (!AlarmMonitor.canScheduleExact(this)) {
            s.append("\n⚠ 未授权“闹钟和提醒”，v11 后台系统唤醒无法工作");
        }
        if (!isIgnoringBatteryOptimizations()) {
            s.append("\n⚠ 电池优化尚未忽略");
        }
        statusText.setText(s.toString());
    }

    private void requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < 31 || AlarmMonitor.canScheduleExact(this)) {
            Toast.makeText(this, "闹钟和提醒权限已可用", Toast.LENGTH_SHORT).show();
            AlarmMonitor.applyConfig(this);
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
            } catch (Exception ignored) {
                openAppDetails();
            }
        }
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
                BluetoothDevice d = devices.get(i);
                labels[i] = safeName(d) + "\n" + d.getAddress();
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择联动设备")
                    .setItems(labels, (dialog, which) -> {
                        BluetoothDevice d = devices.get(which);
                        Prefs.setBluetoothDevice(this, d.getAddress(), safeName(d));
                        Prefs.setBluetoothAutoEnabled(this, true);
                        AlarmMonitor.clearSession(this);
                        AlarmMonitor.applyConfig(this);
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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
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
                Toast.makeText(this,
                        "需要附近设备权限才能识别手表",
                        Toast.LENGTH_LONG).show();
            }
            bindState();
        }
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
                    Toast.makeText(this,
                            "请找到“电量阶梯提醒”并允许自启动",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        openAppDetails();
        Toast.makeText(this,
                "系统未开放直达入口，请在应用详情/电池中寻找“自启动、自动启动或允许后台活动”",
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
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())
            ));
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

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
