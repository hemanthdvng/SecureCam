/**
 * SecureCam Signaling + Relay Server
 * -----------------------------------
 * Handles BOTH:
 *   1) WebRTC signaling (offer/answer/ICE candidate exchange)
 *   2) WebSocket video frame relay (for WebSocket mode)
 *
 * Deploy on any Node.js host (Heroku, Railway, Render, VPS, Raspberry Pi LAN, etc.)
 *
 * Usage:
 *   npm install
 *   node server.js
 *
 * Default port: 8080  (set PORT env var to override)
 */

const WebSocket = require('ws');
const http = require('http');

const PORT = process.env.PORT || 8080;
const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('SecureCam Signaling Server — OK\n');
});

const wss = new WebSocket.Server({ server });

// rooms[roomCode] = { camera: ws | null, viewer: ws | null }
const rooms = {};

function getRoom(code) {
    if (!rooms[code]) rooms[code] = { camera: null, viewer: null };
    return rooms[code];
}

function cleanRoom(code) {
    const r = rooms[code];
    if (r && !r.camera && !r.viewer) {
        delete rooms[code];
        console.log(`Room ${code} cleaned up`);
    }
}

function send(ws, obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(typeof obj === 'string' ? obj : JSON.stringify(obj));
    }
}

wss.on('connection', (ws, req) => {
    const ip = req.socket.remoteAddress;
    console.log(`New connection from ${ip}`);
    ws._role = null;
    ws._room = null;

    ws.on('message', (data) => {
        // Handle binary frames (WebSocket relay mode — camera sending video)
        if (Buffer.isBuffer(data) && ws._role === 'sender') {
            const room = rooms[ws._room];
            if (room && room.receiver) {
                send(room.receiver, data);
            }
            return;
        }

        let msg;
        try {
            msg = JSON.parse(data.toString());
        } catch (e) {
            console.error('Bad JSON:', e.message);
            return;
        }

        const { type, room: roomCode, role } = msg;

        // ── JOIN (WebRTC mode) ──────────────────────────────────────────
        if (type === 'join') {
            ws._room = roomCode;
            ws._role = role;
            const room = getRoom(roomCode);

            if (role === 'camera') {
                if (room.camera && room.camera !== ws) room.camera.close();
                room.camera = ws;
                console.log(`Camera joined room: ${roomCode}`);
                if (room.viewer) {
                    send(room.camera, { type: 'peer_joined' });
                    send(room.viewer, { type: 'peer_joined' });
                }
            } else if (role === 'viewer') {
                if (room.viewer && room.viewer !== ws) room.viewer.close();
                room.viewer = ws;
                console.log(`Viewer joined room: ${roomCode}`);
                if (room.camera) {
                    send(room.camera, { type: 'peer_joined' });
                    send(room.viewer, { type: 'peer_joined' });
                }
            }
        }

        // ── JOIN STREAM (WebSocket relay mode) ─────────────────────────
        else if (type === 'join_stream') {
            ws._room = roomCode;
            ws._role = role; // 'sender' or 'receiver'
            const room = getRoom(roomCode);

            if (role === 'sender') {
                room.camera = ws;
                console.log(`Stream sender joined room: ${roomCode}`);
                if (room.receiver) {
                    send(room.camera, { type: 'peer_joined' });
                    send(room.receiver, { type: 'peer_joined' });
                }
            } else if (role === 'receiver') {
                room.receiver = ws;
                room.viewer = ws;
                console.log(`Stream receiver joined room: ${roomCode}`);
                if (room.camera) {
                    send(room.camera, { type: 'peer_joined' });
                    send(room.receiver, { type: 'peer_joined' });
                }
            }
        }

        // ── WebRTC OFFER (camera → viewer) ─────────────────────────────
        else if (type === 'offer') {
            const room = getRoom(roomCode);
            if (room.viewer) send(room.viewer, msg);
        }

        // ── WebRTC ANSWER (viewer → camera) ────────────────────────────
        else if (type === 'answer') {
            const room = getRoom(roomCode);
            if (room.camera) send(room.camera, msg);
        }

        // ── ICE CANDIDATE (relay to other peer) ────────────────────────
        else if (type === 'ice_candidate') {
            const room = getRoom(roomCode);
            if (ws._role === 'camera' && room.viewer) send(room.viewer, msg);
            else if (ws._role === 'viewer' && room.camera) send(room.camera, msg);
        }

        // ── MOTION / AI EVENT (camera → viewer) ────────────────────────
        else if (type === 'motion_event' || type === 'ai_event') {
            const room = getRoom(roomCode);
            if (room.viewer) send(room.viewer, msg);
            if (room.receiver) send(room.receiver, msg);
        }
    });

    ws.on('close', () => {
        const code = ws._room;
        if (!code) return;
        const room = rooms[code];
        if (!room) return;

        console.log(`Peer left room ${code} (role: ${ws._role})`);
        const isCamera = ws._role === 'camera' || ws._role === 'sender';
        const other = isCamera ? (room.viewer || room.receiver) : room.camera;

        if (isCamera) { room.camera = null; }
        else { room.viewer = null; room.receiver = null; }

        if (other) send(other, { type: 'peer_left' });
        cleanRoom(code);
    });

    ws.on('error', (err) => {
        console.error(`WS error (room: ${ws._room}):`, err.message);
    });
});

server.listen(PORT, () => {
    console.log(`SecureCam server running on port ${PORT}`);
    console.log(`WebRTC signaling + WebSocket relay ready`);
});
