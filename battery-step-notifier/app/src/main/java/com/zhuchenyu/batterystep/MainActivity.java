package com.zhuchenyu.batterystep;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 100;

    private Switch enabledSwitch;
    private Switch hideRecentsSwitch;
    private SeekBar stepSeek;
    private TextView stepText;
    private TextView statusText;
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

        enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setEnabled(this, isChecked);
            if (isChecked) {
                requestNotificationPermissionIfNeeded();
                BatteryMonitorService.start(this);
                Toast.makeText(this, "监测已开启", Toast.LENGTH_SHORT).show();
            } else {
                BatteryMonitorService.stop(this);
                Toast.makeText(this, "监测已关闭", Toast.LENGTH_SHORT).show();
            }
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

    private LinearLayout buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        switchHint.setText("打开后立即启动后台监测；关闭后立即停止后台服务和电量提醒。状态会被记住。\n");
        switchHint.setTextSize(13);
        root.addView(switchHint, matchWrap());

        hideRecentsSwitch = new Switch(this);
        hideRecentsSwitch.setText("在最近任务页面隐藏");
        hideRecentsSwitch.setTextSize(17);
        hideRecentsSwitch.setPadding(0, dp(4), 0, dp(4));
        root.addView(hideRecentsSwitch, matchWrap());

        TextView hideHint = new TextView(this);
        hideHint.setText("开启后仍可从桌面图标或通知进入 App，只是不出现在最近任务列表中。\n");
        hideHint.setTextSize(13);
        root.addView(hideHint, matchWrap());

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
        note.setText("\n说明：总开关开启时会保留一个低优先级前台服务通知，以减少系统清理后台导致漏提醒。服务不使用网络、定位或 WakeLock。");
        note.setTextSize(12);
        root.addView(note, matchWrap());
        return root;
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
    protected void onStop() {
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {}
        super.onStop();
    }

    private void saveStep() {
        int step = stepSeek.getProgress() + 1;
        Prefs.setStep(this, step);
        if (Prefs.isEnabled(this)) {
            BatteryMonitorService.refresh(this);
        }
        Toast.makeText(this, "提醒间隔已保存：" + step + "%", Toast.LENGTH_SHORT).show();
        renderStatus();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
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
            statusText.setText("总开关：关闭 · 当前电量 " + battery);
        } else if (currentCharging) {
            statusText.setText("总开关：开启 · 正在充电 · 当前 " + battery + " · 每提升 " + step + "% 提醒");
        } else {
            statusText.setText("总开关：开启 · 后台待机 · 当前 " + battery + " · 插电后自动开始计算");
        }
    }
}
