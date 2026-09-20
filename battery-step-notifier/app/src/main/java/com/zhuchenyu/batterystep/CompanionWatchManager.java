package com.zhuchenyu.batterystep;

import android.app.Activity;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.companion.ObservingDevicePresenceRequest;
import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

public final class CompanionWatchManager {
    public static final int REQUEST_ASSOCIATION = 601;

    private CompanionWatchManager() {}

    public static boolean isSupported(Context context) {
        return Build.VERSION.SDK_INT >= 31
                && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP);
    }

    public static boolean isAssociated(Context context) {
        if (!isSupported(context)) return false;
        String address = Prefs.getBluetoothAddress(context);
        if (address.isEmpty()) return false;
        CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
        if (manager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                for (AssociationInfo info : manager.getMyAssociations()) {
                    if (info.getDeviceMacAddress() != null
                            && address.equalsIgnoreCase(info.getDeviceMacAddress().toString())) {
                        return true;
                    }
                }
                return false;
            }
            //noinspection deprecation
            List<String> addresses = manager.getAssociations();
            for (String item : addresses) {
                if (address.equalsIgnoreCase(item)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    public static int findAssociationId(Context context) {
        if (Build.VERSION.SDK_INT < 33 || !isSupported(context)) return -1;
        String address = Prefs.getBluetoothAddress(context);
        if (address.isEmpty()) return -1;
        CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
        if (manager == null) return -1;
        try {
            for (AssociationInfo info : manager.getMyAssociations()) {
                if (info.getDeviceMacAddress() != null
                        && address.equalsIgnoreCase(info.getDeviceMacAddress().toString())) {
                    return info.getId();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    public static void ensureObservingIfAssociated(Context context) {
        if (!isAssociated(context)) return;
        CompanionDeviceManager manager = context.getSystemService(CompanionDeviceManager.class);
        if (manager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                int associationId = findAssociationId(context);
                if (associationId < 0) return;
                ObservingDevicePresenceRequest request = new ObservingDevicePresenceRequest.Builder()
                        .setAssociationId(associationId)
                        .build();
                manager.startObservingDevicePresence(request);
            } else if (Build.VERSION.SDK_INT >= 31) {
                //noinspection deprecation
                manager.startObservingDevicePresence(Prefs.getBluetoothAddress(context));
            }
            BackgroundLogStore.append(context, "伴侣", "系统伴侣设备观察已启用");
        } catch (IllegalStateException ignored) {
            // Already observing is fine.
        } catch (RuntimeException e) {
            BackgroundLogStore.append(context, "伴侣", "启用系统伴侣观察失败：" + e.getClass().getSimpleName());
        }
    }

    public static void requestAssociation(Activity activity, ResultCallback callback) {
        if (!isSupported(activity)) {
            callback.onFailure("当前系统不支持 CompanionDeviceManager");
            return;
        }
        String address = Prefs.getBluetoothAddress(activity);
        if (address.isEmpty()) {
            callback.onFailure("请先选择手表蓝牙设备");
            return;
        }
        if (isAssociated(activity)) {
            ensureObservingIfAssociated(activity);
            callback.onAssociated();
            return;
        }

        CompanionDeviceManager manager = activity.getSystemService(CompanionDeviceManager.class);
        if (manager == null) {
            callback.onFailure("系统伴侣设备服务不可用");
            return;
        }

        BluetoothDeviceFilter filter = new BluetoothDeviceFilter.Builder()
                .setAddress(address)
                .build();
        AssociationRequest.Builder builder = new AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH);
        }
        AssociationRequest request = builder.build();

        CompanionDeviceManager.Callback systemCallback = new CompanionDeviceManager.Callback() {
            @Override
            public void onAssociationPending(IntentSender intentSender) {
                try {
                    activity.startIntentSenderForResult(
                            intentSender,
                            REQUEST_ASSOCIATION,
                            null,
                            0,
                            0,
                            0
                    );
                } catch (IntentSender.SendIntentException e) {
                    callback.onFailure("无法打开系统关联确认页面");
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onDeviceFound(IntentSender intentSender) {
                if (Build.VERSION.SDK_INT >= 33) return;
                try {
                    activity.startIntentSenderForResult(
                            intentSender,
                            REQUEST_ASSOCIATION,
                            null,
                            0,
                            0,
                            0
                    );
                } catch (IntentSender.SendIntentException e) {
                    callback.onFailure("无法打开系统关联确认页面");
                }
            }

            @Override
            public void onAssociationCreated(AssociationInfo associationInfo) {
                ensureObservingIfAssociated(activity);
                BackgroundLogStore.append(activity, "伴侣", "系统伴侣关联成功");
                CompanionSetupNotifier.cancel(activity);
                callback.onAssociated();
            }

            @Override
            public void onFailure(CharSequence error) {
                callback.onFailure(error == null ? "系统伴侣关联失败" : error.toString());
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.associate(request, activity.getMainExecutor(), systemCallback);
            } else {
                //noinspection deprecation
                manager.associate(request, systemCallback, new Handler(Looper.getMainLooper()));
            }
        } catch (RuntimeException e) {
            callback.onFailure("系统伴侣关联失败：" + e.getClass().getSimpleName());
        }
    }

    public interface ResultCallback {
        void onAssociated();
        void onFailure(String message);
    }
}
