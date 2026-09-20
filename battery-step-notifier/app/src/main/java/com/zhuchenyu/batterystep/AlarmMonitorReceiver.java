package com.zhuchenyu.batterystep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AlarmMonitorReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AlarmMonitor.handleTick(context);
    }
}
