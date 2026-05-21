package com.example.wallpaperapplication;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * FIX Bug 5  — ForegroundServiceStartNotAllowedException on Android 12+
 * FIX Bug 11 — DataSyncWorker and StreamingService both poll SMS/calls every 30s,
 *              causing duplicate data bursts in the dashboard.
 *
 * Changes:
 *  1. On Android 12+ we only send ACTION_SYNC_DATA to an *already-running* service
 *     via startService() (safe if FGS is live) with a try/catch fallback.
 *     We never call startForegroundService() from a background worker.
 *  2. We set the intent action to "ACTION_SYNC_DATA" so StreamingService can gate
 *     the handler with a dedup flag: it only forwards data if the service's own
 *     30s dataHandler hasn't fired in the last 20 seconds.
 *
 * Scheduling (in StreamingService.scheduleDataSyncWorker):
 *   PeriodicWorkRequest.Builder(DataSyncWorker.class, 15, TimeUnit.MINUTES)
 *     .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
 *     .build()
 * Note: WorkManager minimum period is 15 minutes. The 30s dataHandler inside
 * StreamingService handles the high-frequency path. DataSyncWorker is a low-power
 * backstop for when the service is sleeping.
 */
public class DataSyncWorker extends Worker {
    private static final String TAG = "DataSyncWorker";

    public DataSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "DataSyncWorker fired");
        Context ctx = getApplicationContext();

        // Only poke the service — never emit data directly from this worker.
        // If StreamingService is already running its own 30s cycle, it will
        // ignore this and NOT send duplicate data (dedup guard in service).
        Intent intent = new Intent(ctx, StreamingService.class);
        intent.setAction(StreamingServiceIntents.ACTION_SYNC_DATA);

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Android 12+: startForegroundService() from background throws.
                // Use startService() — safe only if the service is already in foreground state.
                // If service is dead, the AlarmManager 4-min watchdog will restart it.
                ctx.startService(intent);
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            return Result.success();
        } catch (Exception e) {
            // Service not reachable (expected on Android 12+ when service is truly dead).
            // AlarmManager watchdog handles restart — no need to retry here.
            Log.w(TAG, "Service not reachable: " + e.getMessage());
            return Result.success(); // Don't retry — watchdog will handle it
        }
    }
}
