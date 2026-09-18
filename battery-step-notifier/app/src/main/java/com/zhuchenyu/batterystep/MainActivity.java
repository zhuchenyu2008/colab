package com.zhuchenyu.batterystep;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 100;
    private static final int REQ_BLUETOOTH = 101;

    private Switch enabledSwitch;
    private Switch hideRecentsSwitch;
    private Switch bluetoothAutoSwitch;
    private Switch pauseQuietSwitch;
    private SeekBar stepSeek;
    private TextView stepText;
    private TextView statusText;
    private Button bluetoothDeviceButton;
    private Button dndAccessButton;
    private int currentLevel = -1;
    private boolean currentCharging;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryState(intent);
            renderStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("电量阶梯提醒");
        setContentView(buildUi());

        int savedStep = Prefs.getStep(this);
        stepSeek.setProgress(savedStep - 1);
        stepText.setText(savedStep + "%");
        enabledSwitch.setChecked(Prefs.isEnabled(this));
        hideRecentsSwitch.setChecked(Prefs.hideFromRecents(this));
        bluetoothAutoSwitch.setChecked(Prefs.isBluetoothAutoEnabled(this));
        pauseQuietSwitch.setChecked(Prefs.pauseOnQuietMode(this));
        updateBluetoothDeviceButton();
        updateDndAccessButton();

        enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setEnabled(this, isChecked);
            if (isChecked) {
                requestNotificationPermissionIfNeeded();
                Toast.makeText(this, "监测已开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "监测已关闭", Toast.LENGTH_SHORT).show();
            }
            BatteryMonitorService.applyConfig(this);
            renderStatus();
        });

        hideRecentsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setHideFromRecents(this, isChecked);
            RecentsHelper.setExcluded(this, isChecked);
            Toast.makeText(
                    this,
                    isChecked ? "已从最近任务页面隐藏" : "已恢复显示在最近任务页面",
                    Toast.LENGTH_SHORT
            ).show();
        });

        bluetoothAutoSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!hasBluetoothPermission()) {
                    requestBluetoothPermission();
                    return;
                }
                enableBluetoothAutomation();
            } else {
                Prefs.setBluetoothAutoEnabled(this, false);
                BatteryMonitorService.applyConfig(this);
                Toast.makeText(this, "蓝牙自动开启已关闭", Toast.LENGTH_SHORT).show();
            }
            renderStatus();
        });

        pauseQuietSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setPauseOnQuietMode(this, isChecked);
            if (isChecked && !hasDndAccess()) {
                openDndAccessSettings();
                Toast.makeText(this, "请允许免打扰访问；静音检测无需额外权限", Toast.LENGTH_LONG).show();
            }
            BatteryMonitorService.applyConfig(this);
            renderStatus();
        });

        stepSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                stepText.setText((progress + 1) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        RecentsHelper.setExcluded(this, Prefs.hideFromRecents(this));
    }

    private ScrollView buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("电量阶梯提醒");
        title.setTextSize(28);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        TextView description = new TextView(this);
        description.setText("充电时每提升设定的电量百分比就通知一次；不按秒轮询。\n");
        description.setTextSize(15);
        root.addView(description, matchWrap());

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("总开关");
        enabledSwitch.setTextSize(19);
        enabledSwitch.setPadding(0, dp(6), 0, dp(4));
        root.addView(enabledSwitch, matchWrap());

        TextView switchHint = new TextView(this);
        switchHint.setText("打开后监测充电电量；关闭后停止电量提醒。蓝牙自动化可在指定设备连接时重新打开它。\n");
        switchHint.setTextSize(13);
        root.addView(switchHint, matchWrap());

        hideRecentsSwitch = new Switch(this);
        hideRecentsSwitch.setText("在最近任务页面隐藏");
        hideRecentsSwitch.setTextSize(17);
        root.addView(hideRecentsSwitch, matchWrap());

        TextView automationTitle = new TextView(this);
        automationTitle.setText("\n自动化");
        automationTitle.setTextSize(20);
        root.addView(automationTitle, matchWrap());

        bluetoothAutoSwitch = new Switch(this);
        bluetoothAutoSwitch.setText("指定蓝牙设备连接时自动开启");
        bluetoothAutoSwitch.setTextSize(17);
        root.addView(bluetoothAutoSwitch, matchWrap());

        bluetoothDeviceButton = new Button(this);
        bluetoothDeviceButton.setOnClickListener(v -> {
            if (!hasBluetoothPermission()) {
                requestBluetoothPermission();
            } else {
                showPairedDevicePicker();
            }
        });
        root.addView(bluetoothDeviceButton, matchWrap());

        TextView bluetoothHint = new TextView(this);
        bluetoothHint.setText("只读取系统已经配对的设备，不进行蓝牙扫描。设备连接后会把总开关自动打开；断开时不会强制关闭。\n");
        bluetoothHint.setTextSize(13);
        root.addView(bluetoothHint, matchWrap());

        pauseQuietSwitch = new Switch(this);
        pauseQuietSwitch.setText("免打扰或静音时自动暂停监测");
        pauseQuietSwitch.setTextSize(17);
        root.addView(pauseQuietSwitch, matchWrap());

        dndAccessButton = new Button(this);
        dndAccessButton.setOnClickListener(v -> openDndAccessSettings());
        root.addView(dndAccessButton, matchWrap());

        TextView quietHint = new TextView(this);
        quietHint.setText("静音模式可直接检测；免打扰需要一次系统授权。恢复普通响铃后会自动继续，并从恢复时的电量重新计算，不补发暂停期间的提醒。\n");
        quietHint.setTextSize(13);
        root.addView(quietHint, matchWrap());

        TextView stepLabel = new TextView(this);
        stepLabel.setText("每提升多少电量提醒");
        stepLabel.setTextSize(16);
        root.addView(stepLabel, matchWrap());

        stepText = new TextView(this);
        stepText.setTextSize(30);
        root.addView(stepText, matchWrap());

        stepSeek = new SeekBar(this);
        stepSeek.setMax(19);
        root.addView(stepSeek, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, dp(18), 0, dp(18));
        root.addView(statusText, matchWrap());

        Button save = new Button(this);
        save.setText("保存提醒间隔");
        save.setOnClickListener(v -> saveStep());
        root.addView(save, matchWrap());

        Button settings = new Button(this);
        settings.setText("打开后台 / 电池设置");
        settings.setOnClickListener(v -> openAppSettings());
        root.addView(settings, matchWrap());

        TextView note = new TextView(this);
        note.setText("\n说明：总开关开启，或蓝牙自动开启功能正在等待设备时，会保留一个低优先级前台服务通知，以减少系统清理后台导致漏触发。服务不使用网络、定位或 WakeLock。");
        note.setTextSize(12);
        root.addView(note, matchWrap());
        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky;
        if (Build.VERSION.SDK_INT >= 33) {
            sticky = registerReceiver(batteryReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            sticky = registerReceiver(batteryReceiver, filter);
        }
        if (sticky != null) updateBatteryState(sticky);
        RecentsHelper.setExcluded(this, Prefs.hideFromRecents(this));
        renderStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDndAccessButton();
        updateBluetoothDeviceButton();
        if (Prefs.pauseOnQuietMode(this) || Prefs.isBluetoothAutoEnabled(this)) {
            BatteryMonitorService.applyConfig(this);
        }
        if (enabledSwitch != null && enabledSwitch.isChecked() != Prefs.isEnabled(this)) {
            enabledSwitch.setChecked(Prefs.isEnabled(this));
        }
        renderStatus();
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onStop();
    }

    private void saveStep() {
        int step = stepSeek.getProgress() + 1;
        Prefs.setStep(this, step);
        BatteryMonitorService.applyConfig(this);
        Toast.makeText(this, "提醒间隔已保存：" + step + "%", Toast.LENGTH_SHORT).show();
        renderStatus();
    }

    private void enableBluetoothAutomation() {
        Prefs.setBluetoothAutoEnabled(this, true);
        BatteryMonitorService.applyConfig(this);
        if (Prefs.getBluetoothAddress(this).isEmpty()) {
            showPairedDevicePicker();
        } else {
            Toast.makeText(this, "蓝牙自动开启已启用", Toast.LENGTH_SHORT).show();
        }
        updateBluetoothDeviceButton();
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
        } else {
            showPairedDevicePicker();
        }
    }

    private void showPairedDevicePicker() {
        if (!hasBluetoothPermission()) return;
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) {
                Toast.makeText(this, "此设备不支持蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!adapter.isEnabled()) {
                Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }

            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                Toast.makeText(this, "没有找到已配对设备，请先在系统蓝牙中完成配对", Toast.LENGTH_LONG).show();
                return;
            }

            List<BluetoothDevice> devices = new ArrayList<>(bonded);
            devices.sort((a, b) -> safeDeviceName(a).compareToIgnoreCase(safeDeviceName(b)));
            String[] labels = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                BluetoothDevice device = devices.get(i);
                labels[i] = safeDeviceName(device) + "\n" + device.getAddress();
            }

            new AlertDialog.Builder(this)
                    .setTitle("选择要联动的蓝牙设备")
                    .setItems(labels, (dialog, which) -> {
                        BluetoothDevice device = devices.get(which);
                        Prefs.setBluetoothDevice(this, device.getAddress(), safeDeviceName(device));
                        Prefs.setBluetoothAutoEnabled(this, true);
                        if (!bluetoothAutoSwitch.isChecked()) bluetoothAutoSwitch.setChecked(true);
                        updateBluetoothDeviceButton();
                        BatteryMonitorService.applyConfig(this);
                        Toast.makeText(this, "已选择：" + safeDeviceName(device), Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (SecurityException e) {
            requestBluetoothPermission();
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.trim().isEmpty() ? "未命名设备" : name;
        } catch (SecurityException e) {
            return "蓝牙设备";
        }
    }

    private void updateBluetoothDeviceButton() {
        if (bluetoothDeviceButton == null) return;
        String name = Prefs.getBluetoothName(this);
        bluetoothDeviceButton.setText("选择蓝牙设备 · " + name);
    }

    private boolean hasDndAccess() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationPolicyAccessGranted();
    }

    private void updateDndAccessButton() {
        if (dndAccessButton == null) return;
        dndAccessButton.setText(hasDndAccess() ? "免打扰检测权限：已授权" : "免打扰检测权限：点此授权");
    }

    private void openDndAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean isQuietModeNow() {
        if (!Prefs.pauseOnQuietMode(this)) return false;
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

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                Prefs.setBluetoothAutoEnabled(this, true);
                if (!bluetoothAutoSwitch.isChecked()) bluetoothAutoSwitch.setChecked(true);
                BatteryMonitorService.applyConfig(this);
                if (Prefs.getBluetoothAddress(this).isEmpty()) showPairedDevicePicker();
            } else {
                Prefs.setBluetoothAutoEnabled(this, false);
                if (bluetoothAutoSwitch.isChecked()) bluetoothAutoSwitch.setChecked(false);
                Toast.makeText(this, "需要蓝牙连接权限才能识别指定手表", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void updateBatteryState(Intent intent) {
        int raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (raw >= 0 && scale > 0) currentLevel = Math.round(raw * 100f / scale);

        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        currentCharging = plugged != 0
                || status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private void renderStatus() {
        if (statusText == null) return;
        String battery = currentLevel >= 0 ? currentLevel + "%" : "未知";
        int step = Prefs.getStep(this);
        if (!Prefs.isEnabled(this)) {
            if (Prefs.isBluetoothAutoEnabled(this)) {
                statusText.setText("总开关：关闭 · 等待 " + Prefs.getBluetoothName(this) + " 连接后自动开启 · 当前电量 " + battery);
            } else {
                statusText.setText("总开关：关闭 · 当前电量 " + battery);
            }
        } else if (isQuietModeNow()) {
            statusText.setText("总开关：开启 · 当前处于免打扰/静音，监测已自动暂停 · 当前 " + battery);
        } else if (currentCharging) {
            statusText.setText("总开关：开启 · 正在充电 · 当前 " + battery + " · 每提升 " + step + "% 提醒");
        } else {
            statusText.setText("总开关：开启 · 后台待机 · 当前 " + battery + " · 插电后自动开始计算");
        }
    }
}
