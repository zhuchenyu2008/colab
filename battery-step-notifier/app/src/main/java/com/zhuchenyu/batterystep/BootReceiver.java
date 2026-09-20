package com.zhuchenyu.batterystep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        CompanionWatchManager.ensureObservingIfAssociated(context);
        CompanionSetupNotifier.notifyIfNeeded(context);
        if (Prefs.shouldKeepServiceRunning(context)) {
            BatteryMonitorService.start(context);
        }
        BackgroundLogStore.append(
                context,
                "系统",
                "收到 " + (intent.getAction() == null ? "启动事件" : intent.getAction())
        );
    }
}
