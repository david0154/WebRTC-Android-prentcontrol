package com.example.wallpaperapplication;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * FIX Bug 11 — Starting a foreground service from a WorkManager background worker
 * without setExpedited() throws ForegroundServiceStartNotAllowedException on Android 12+.
 * Fix: send a broadcast / direct call to already-running service instead of
 * startForegroundService(). If service not running, start normally via startService()
 * on pre-12, or skip and let the AlarmManager watchdog restart it on 12+.
 */
public class DataSyncWorker extends Worker {
    private static final String TAG = "DataSyncWorker";

    public DataSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Background Sync Started");
        try {
            Intent serviceIntent = new Intent(getApplicationContext(), StreamingService.class);
            serviceIntent.setAction("ACTION_SYNC_DATA");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Android 12+ — cannot startForegroundService from background worker.
                // Send as a plain broadcast; StreamingService handles it if already running.
                // If the service is not running, the AlarmManager watchdog will restart it.
                Intent broadcast = new Intent(getApplicationContext(), StreamingService.class);
                broadcast.setAction("ACTION_SYNC_DATA");
                // Use LocalBroadcastManager equivalent — just startService (won't throw if
                // service is already in foreground state)
                try {
                    getApplicationContext().startService(serviceIntent);
                } catch (Exception ignored) {
                    // Service not reachable from background — AlarmManager watchdog handles restart
                    Log.w(TAG, "Could not reach StreamingService from background (Android 12+), watchdog will restart");
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(serviceIntent);
            } else {
                getApplicationContext().startService(serviceIntent);
            }

            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Sync failed", e);
            return Result.retry();
        }
    }
}
