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
        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            AlarmMonitor.clearSession(context);
            if (Prefs.isEnabled(context)) AlarmMonitor.schedule(context, 1000L);
            BatteryMonitorService.applyConfig(context);
            return;
        }
        if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            AlarmMonitor.resetForPowerDisconnected(context);
            return;
        }
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            if (!Prefs.isBluetoothAutoEnabled(context)) return;
            if (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return;

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
            AlarmMonitor.clearSession(context);
            if (connected) {
                AlarmMonitor.applyConfig(context);
                BatteryMonitorService.applyConfig(context);
            } else {
                AlarmMonitor.cancel(context);
                BatteryMonitorService.applyConfig(context);
            }
        }
    }
}
