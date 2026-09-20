package com.zhuchenyu.batterystep;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class CompanionSetupActivity extends Activity {
    private TextView status;
    private Button action;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("系统手表伴侣保活");
        setContentView(buildUi());
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private LinearLayout buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("系统手表伴侣保活");
        title.setTextSize(26);
        title.setTextColor(getColor(R.color.text_primary));
        root.addView(title, matchWrap());

        TextView desc = new TextView(this);
        desc.setText("这是 Android 官方 CompanionDeviceManager 机制。关联后，手表连接时系统会绑定本 App 的 CompanionDeviceService，提高后台进程存活优先级，让固定每分钟检测更稳定。\n\n只需确认一次，不会重新配对或清除手表现有连接。");
        desc.setTextSize(15);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setPadding(0, dp(12), 0, dp(18));
        root.addView(desc, matchWrap());

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 0, 0, dp(16));
        root.addView(status, matchWrap());

        action = new Button(this);
        action.setAllCaps(false);
        action.setOnClickListener(v -> beginAssociation());
        root.addView(action, matchWrap());

        Button back = new Button(this);
        back.setText("返回");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = matchWrap();
        backParams.topMargin = dp(10);
        root.addView(back, backParams);
        return root;
    }

    private void render() {
        if (!CompanionWatchManager.isSupported(this)) {
            status.setText("当前系统不支持系统伴侣设备保活。仍会继续使用普通前台服务。 ");
            action.setText("不可用");
            action.setEnabled(false);
            return;
        }
        if (Prefs.getBluetoothAddress(this).isEmpty()) {
            status.setText("请先回主页面选择手表蓝牙设备。 ");
            action.setText("尚未选择手表");
            action.setEnabled(false);
            return;
        }
        if (CompanionWatchManager.isAssociated(this)) {
            CompanionWatchManager.ensureObservingIfAssociated(this);
            CompanionSetupNotifier.cancel(this);
            status.setText("✓ 已关联：" + Prefs.getBluetoothName(this) + "\n系统伴侣保活已启用。");
            action.setText("已启用");
            action.setEnabled(false);
        } else {
            status.setText("待关联：" + Prefs.getBluetoothName(this));
            action.setText("关联为系统手表伴侣");
            action.setEnabled(true);
        }
    }

    private void beginAssociation() {
        action.setEnabled(false);
        action.setText("正在打开系统确认…");
        CompanionWatchManager.requestAssociation(this, new CompanionWatchManager.ResultCallback() {
            @Override
            public void onAssociated() {
                runOnUiThread(() -> {
                    Toast.makeText(CompanionSetupActivity.this, "系统伴侣保活已启用", Toast.LENGTH_LONG).show();
                    BatteryMonitorService.applyConfig(CompanionSetupActivity.this);
                    render();
                });
            }

            @Override
            public void onFailure(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(CompanionSetupActivity.this, message, Toast.LENGTH_LONG).show();
                    BackgroundLogStore.append(CompanionSetupActivity.this, "伴侣", "系统关联失败：" + message);
                    action.setEnabled(true);
                    action.setText("重试系统伴侣关联");
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CompanionWatchManager.REQUEST_ASSOCIATION) {
            if (resultCode == RESULT_OK) {
                CompanionWatchManager.ensureObservingIfAssociated(this);
                CompanionSetupNotifier.cancel(this);
                BackgroundLogStore.append(this, "伴侣", "用户确认系统伴侣关联");
                BatteryMonitorService.applyConfig(this);
            } else {
                BackgroundLogStore.append(this, "伴侣", "用户取消系统伴侣关联");
            }
            render();
        }
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
