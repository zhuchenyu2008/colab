package com.zhuchenyu.batterystep;

import android.annotation.TargetApi;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.companion.DevicePresenceEvent;
import android.os.Build;

@TargetApi(Build.VERSION_CODES.S)
public class WatchCompanionService extends CompanionDeviceService {
    @Override
    public void onDevicePresenceEvent(DevicePresenceEvent event) {
        if (Build.VERSION.SDK_INT < 36 || event == null) return;
        int type = event.getEvent();
        if (type == DevicePresenceEvent.EVENT_BT_CONNECTED
                || type == DevicePresenceEvent.EVENT_BLE_APPEARED) {
            handlePresence(true, "系统 CompanionDeviceService：手表已连接/出现");
        } else if (type == DevicePresenceEvent.EVENT_BT_DISCONNECTED
                || type == DevicePresenceEvent.EVENT_BLE_DISAPPEARED) {
            handlePresence(false, "系统 CompanionDeviceService：手表已断开/离开");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDeviceAppeared(AssociationInfo associationInfo) {
        handlePresence(true, "系统 CompanionDeviceService：手表已出现");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDeviceDisappeared(AssociationInfo associationInfo) {
        handlePresence(false, "系统 CompanionDeviceService：手表已离开");
    }

    private void handlePresence(boolean connected, String log) {
        BackgroundLogStore.append(this, "伴侣", log);
        if (!Prefs.isBluetoothAutoEnabled(this)) return;
        Prefs.setEnabled(this, connected);
        if (connected) {
            BatteryMonitorService.start(this);
        } else {
            BatteryMonitorService.applyConfig(this);
        }
    }
}
