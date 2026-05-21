package com.example.wallpaperapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import androidx.preference.PreferenceManager;
import java.util.UUID;

/**
 * Central settings/preferences helper.
 *
 * FIX Bug 4 — Stable Device ID
 * The old code used UUID.randomUUID() every onCreate(), so the device appeared
 * as a completely new device after every reconnect / service restart.
 * Fix: generate once on first call, persist in SharedPreferences under key
 * "stable_device_id", and always return the same value thereafter.
 *
 * Priority order:
 *   1. Previously persisted UUID (most reconnects)
 *   2. ANDROID_ID if available and not the known-bad emulator value
 *   3. Fresh UUID (first install / factory reset)
 */
public final class SettingsRepository {

    private static final String KEY_DEVICE_ID   = "stable_device_id";
    private static final String KEY_SIGNALING   = "signaling_url";
    private static final String BAD_ANDROID_ID  = "9774d56d682e549c"; // known emulator value

    private SettingsRepository() {}

    /** Returns the signaling URL set by the user, or the compile-time default. */
    public static String getSignalingUrl(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getString(KEY_SIGNALING, StreamingService.DEFAULT_SIGNALING_URL);
    }

    /**
     * Returns a stable device ID that persists across app restarts, service kills,
     * and socket reconnections. Generated once, stored forever.
     *
     * Call this instead of UUID.randomUUID() in StreamingService.onCreate().
     */
    public static String getStableDeviceId(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("device_prefs", Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_DEVICE_ID, null);
        if (saved != null && !saved.isEmpty()) return saved;

        // Try ANDROID_ID first (survives reinstall, but not factory reset)
        String androidId = Settings.Secure.getString(
                ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        String id;
        if (androidId != null && !androidId.isEmpty() && !androidId.equals(BAD_ANDROID_ID)) {
            id = "dev_" + androidId;
        } else {
            id = "dev_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        return id;
    }
}
