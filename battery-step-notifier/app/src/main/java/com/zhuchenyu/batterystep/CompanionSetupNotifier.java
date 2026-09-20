package com.zhuchenyu.batterystep;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

public final class CompanionSetupNotifier {
    private static final String CHANNEL = "companion_setup";
    private static final int ID = 7301;

    private CompanionSetupNotifier() {}

    public static void notifyIfNeeded(Context context) {
        if (!Prefs.isBluetoothAutoEnabled(context)) return;
        if (Prefs.getBluetoothAddress(context).isEmpty()) return;
        if (!CompanionWatchManager.isSupported(context)) return;
        if (CompanionWatchManager.isAssociated(context)) {
            CompanionWatchManager.ensureObservingIfAssociated(context);
            cancel(context);
            return;
        }

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                "系统手表伴侣保活",
                NotificationManager.IMPORTANCE_HIGH
        );
        manager.createNotificationChannel(channel);

        Intent intent = new Intent(context, CompanionSetupActivity.class);
        PendingIntent open = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String name = Prefs.getBluetoothName(context);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("启用系统手表伴侣保活")
                .setContentText("点此把 " + name + " 关联为系统伴侣设备，提高每分钟后台检测可靠性")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .build();
        manager.notify(ID, notification);
        BackgroundLogStore.append(context, "伴侣", "等待用户完成系统伴侣关联");
    }

    public static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ID);
    }
}
