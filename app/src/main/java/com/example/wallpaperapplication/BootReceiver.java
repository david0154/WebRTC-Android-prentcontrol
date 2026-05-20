package com.example.wallpaperapplication;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "android.intent.action.QUICKBOOT_POWERON":
            case "android.intent.action.MY_PACKAGE_REPLACED":
                Log.d(TAG, "Auto-start trigger: " + action);
                startService(context);
                break;
            default:
                break;
        }
    }

    private static void startService(Context context) {
        try {
            Intent svc = new Intent(context, StreamingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            Log.d("BootReceiver", "StreamingService started via BootReceiver");
        } catch (Exception e) {
            Log.e("BootReceiver", "Failed to start StreamingService", e);
        }
    }
}
