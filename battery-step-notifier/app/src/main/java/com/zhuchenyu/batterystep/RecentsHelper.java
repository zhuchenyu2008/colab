package com.zhuchenyu.batterystep;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;

public final class RecentsHelper {
    private RecentsHelper() {}

    public static void setExcluded(Activity activity, boolean excluded) {
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return;

        int currentTaskId = activity.getTaskId();
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.taskId == currentTaskId) {
                    task.setExcludeFromRecents(excluded);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }
}
