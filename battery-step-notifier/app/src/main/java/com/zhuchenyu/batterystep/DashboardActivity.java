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
        RecentsHelper.setExcluded(this, Prefs.hideFromRecents(this));
    }

    private ScrollView buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("电量阶梯提醒", 28);
        root.addView(title, matchWrap());
        TextView desc = text("充电时按设定百分比提醒。v10 后台电量采用 BatteryManager 系统直读，不再依赖厂商是否及时发送电量广播。\n", 14);
        root.addView(desc, matchWrap());

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("总开关");
        enabledSwitch.setTextSize(19);
        enabledSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (syncingUi) return;
            Prefs.setEnabled(this, checked);
            if (checked) requestNotificationPermission();
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

        TextView autoTitle = text("\n自动化", 20);
        root.addView(autoTitle, matchWrap());

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
            BatteryMonitorService.applyConfig(this);
            renderStatus();
        });
        root.addView(quietSwitch, matchWrap());

        dndButton = new Button(this);
        dndButton.setOnClickListener(v -> openDndSettings());
        root.addView(dndButton, matchWrap());

        TextView stepTitle = text("\n提醒间隔", 20);
        root.addView(stepTitle, matchWrap());
        stepText = text("1%", 28);
        root.addView(stepText, matchWrap());
        stepSeek = new SeekBar(this);
        stepSeek.setMax(19);
        stepSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                stepText.setText((progress + 1) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int value = seekBar.getProgress() + 1;
                Prefs.setStep(DashboardActivity.this, value);
                BatteryMonitorService.applyConfig(DashboardActivity.this);
                Toast.makeText(DashboardActivity.this, "已保存：每提升 " + value + "% 提醒", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(stepSeek, matchWrap());

        TextView reliability = text("\n后台可靠性", 20);
        root.addView(reliability, matchWrap());

        Button autoStartButton = new Button(this);
        autoStartButton.setText("打开自启动管理（OPPO / 一加）");
        autoStartButton.setOnClickListener(v -> openAutoStartSettings());
        root.addView(autoStartButton, matchWrap());

        TextView autoHint = text("请在系统页面手动允许“自启动/自动启动”。App 已包含 BOOT_COMPLETED 接收器，但厂商可额外拦截开机自启。\n", 12);
        root.addView(autoHint, matchWrap());

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

        TextView note = text("说明：充电监测开启时每 15 秒直接读取一次系统 BatteryManager 电量；锁屏充电时使用 partial wake lock 保证定时采样。拔电、关闭总开关、手表断开或进入静音/免打扰后会释放。", 12);
        root.addView(note, matchWrap());
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
        syncingUi = false;
        renderStatus();
    }

    private void renderStatus() {
        if (statusText == null) return;
        BatteryManager battery = getSystemService(BatteryManager.class);
        int level = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = battery != null && battery.isCharging();
        StringBuilder s = new StringBuilder();
        s.append(Prefs.isEnabled(this) ? "总开关：开启" : "总开关：关闭");
        s.append(" · 系统直读电量：").append(level >= 0 && level <= 100 ? level + "%" : "未知");
        s.append(charging ? " · 正在充电" : " · 未充电");
        if (Prefs.isBluetoothAutoEnabled(this)) s.append(" · 联动设备：").append(Prefs.getBluetoothName(this));
        if (!isIgnoringBatteryOptimizations()) s.append("\n⚠ 电池优化尚未忽略");
        statusText.setText(s.toString());
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
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Prefs.setBluetoothAutoEnabled(this, true);
                showBluetoothPicker();
            } else {
                Prefs.setBluetoothAutoEnabled(this, false);
                Toast.makeText(this, "需要附近设备权限才能识别手表", Toast.LENGTH_LONG).show();
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
                    Toast.makeText(this, "请找到“电量阶梯提醒”并允许自启动", Toast.LENGTH_LONG).show();
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        openAppDetails();
        Toast.makeText(this, "系统未开放直达入口，请在应用详情/电池中寻找“自启动、自动启动或允许后台活动”", Toast.LENGTH_LONG).show();
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
