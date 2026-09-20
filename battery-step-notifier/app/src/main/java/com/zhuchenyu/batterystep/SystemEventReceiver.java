package com.zhuchenyu.batterystep;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class SystemEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            if (!Prefs.isBluetoothAutoEnabled(context)) return;
            if (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                BackgroundLogStore.append(context, "蓝牙", "缺少 BLUETOOTH_CONNECT 权限，忽略连接事件");
                return;
            }

            BluetoothDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                //noinspection deprecation
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
            if (device == null) return;

            String selected = Prefs.getBluetoothAddress(context);
            if (selected.isEmpty()) return;
            try {
                if (!selected.equalsIgnoreCase(device.getAddress())) return;
            } catch (SecurityException e) {
                return;
            }

            boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
            Prefs.setEnabled(context, connected);
            BackgroundLogStore.append(
                    context,
                    "蓝牙",
                    Prefs.getBluetoothName(context) + (connected ? " 已连接 → 总开关开启" : " 已断开 → 总开关关闭")
            );

            if (connected) {
                if (CompanionWatchManager.isAssociated(context)) {
                    CompanionWatchManager.ensureObservingIfAssociated(context);
                } else {
                    CompanionSetupNotifier.notifyIfNeeded(context);
                }
            }
            BatteryMonitorService.applyConfig(context);
        }
    }
}
