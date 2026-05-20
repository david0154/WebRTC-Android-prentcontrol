package com.example.wallpaperapplication;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

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
            // Trigger the service to send data if it's running, 
            // OR start the service if it's not (which will then sync).
            
            Intent serviceIntent = new Intent(getApplicationContext(), StreamingService.class);
            serviceIntent.setAction("ACTION_SYNC_DATA"); // Custom action we'll handle in Service
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
