# 📱 WebRTC Android ParentControl – v2.0

Upgraded Android WebRTC monitoring app with **Firebase Realtime DB**, **auto-reconnect**, **anti-connection-loss**, **media capture** (audio/video/image), **PHP web dashboard** with login, and **Node.js signaling backend** deployable on Render.

---

## 🏗️ Architecture

```
Android App (Kotlin)
    │
    │  Socket.IO (WebRTC signaling + capture commands)
    ▼
Node.js Backend  ──────── Deployed on Render (Free tier)
    │
    │  base64 media forwarded to dashboard JS
    ▼
PHP Dashboard  ─────────  Your PHP host (shared hosting OK)
    │  MySQL DB – stores device list + temp media (auto-delete 24h)
    └──────────────────── Login protected
```

---

## 🚀 Quick Setup

### 1. Node.js Backend (Render)
1. Push this repo to GitHub
2. Go to [render.com](https://render.com) → New Web Service
3. Select **`node-backend/`** as root directory
4. Build: `npm install` | Start: `node server.js`
5. Copy your Render URL (e.g. `https://your-app.onrender.com`)

### 2. PHP Dashboard
1. Upload `php-dashboard/` to your PHP web host
2. Open `install.php` in browser
3. Fill in: DB credentials, admin login, Render URL
4. Click **Install Now** → tables auto-created
5. **Delete `install.php`** after setup ⚠️
6. Open `login.php` → Dashboard ready

### 3. Android App
1. Open in Android Studio
2. Edit `SpywareService.kt` → set `SERVER_URL` to your Render URL
3. Add permissions to `AndroidManifest.xml` (see `AndroidManifest_additions.xml`)
4. Build & install APK

---

## 📦 Features

| Feature | Status |
|---|---|
| WebRTC Live View | ✅ |
| Firebase Realtime DB sync | ✅ |
| Auto-reconnect (infinite retry) | ✅ |
| Anti-connection-loss (ping/pong) | ✅ |
| Silent Image Capture | ✅ NEW |
| Silent Audio Recording | ✅ NEW |
| Silent Video Recording | ✅ NEW |
| PHP Login Dashboard | ✅ NEW |
| One-click PHP Installer | ✅ NEW |
| Render deploy config | ✅ NEW |
| Auto-delete media (24h) | ✅ NEW |
| Boot auto-start | ✅ |

---

## ⚠️ Legal Notice
This tool is intended for **parental control / device monitoring on devices you own**. Unauthorized monitoring of others is illegal. Use responsibly and in compliance with local laws.
