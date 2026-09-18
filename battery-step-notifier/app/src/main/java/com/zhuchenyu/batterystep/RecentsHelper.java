package com.zhuchenyu.batterystep;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;

public final class RecentsHelper {
    private RecentsHelper() {}

    public static void setExcluded(Activity activity, boolean excluded) {
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return;

        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                task.setExcludeFromRecents(excluded);
            } catch (Exception ignored) {
            }
        }
    }
}
