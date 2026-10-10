'use strict';
const http = require('http');
const path = require('path');
const { WebSocketServer } = require('ws');
const { Registry, sha256, safeEqual } = require('./registry');
const { parseTelemetry } = require('./validate');

const LIMITS = {
  maxPayload: 4096,          // bytes per message
  authTimeoutMs: 5000,       // must authenticate within 5 s
  minTelemetryGapMs: 400,    // per device: max ~2.5 msg/s
  maxViolations: 20,         // then the socket is closed
  maxConnsPerIp: 20,
  maxAuthFails: 5,           // per IP ...
  banMs: 60000,              // ... then blocked for 1 minute
  staleMs: 15000,            // no data for 15 s -> STALE
  offlineMs: 60000,          // no data for 60 s -> OFFLINE
  sweepMs: 2000,
  pingMs: 20000,
};

function createServer(opts = {}) {
  const viewerKey = opts.viewerKey ?? process.env.STSMN_VIEWER_KEY ?? '';
  if (viewerKey.length < 16) throw new Error('STSMN_VIEWER_KEY must be set (min 16 chars)');
  const viewerHash = sha256(viewerKey);
  const dataDir = opts.dataDir || process.env.STSMN_DATA_DIR || path.join(__dirname, '..', 'data');
  const trustProxy = (opts.trustProxy ?? process.env.STSMN_TRUST_PROXY) === '1' || opts.trustProxy === true;
  const limits = { ...LIMITS, ...(opts.limits || {}) };
  const registry = opts.registry || new Registry(dataDir);

  /** id -> live state */
  const live = new Map();
  const viewers = new Set();
  const publishers = new Map(); // id -> ws (one publisher per device; a new one replaces the old)
  const conns = new Map();      // ip -> count
  const fails = new Map();      // ip -> { n, until }

  const server = http.createServer((req, res) => {
    const headers = {
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'none'",
    };
    if (req.url === '/healthz') {
      res.writeHead(200, headers); res.end('{"ok":true}');
    } else {
      res.writeHead(404, headers); res.end('{"error":"not found"}');
    }
  });

  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: limits.maxPayload });

  const ipOf = (req) => {
    if (trustProxy) {
      const xf = req.headers['x-forwarded-for'];
      if (xf) return String(xf).split(',')[0].trim().slice(0, 64);
    }
    return req.socket.remoteAddress || 'unknown';
  };

  const send = (ws, obj) => { if (ws.readyState === 1) ws.send(JSON.stringify(obj)); };
  const broadcast = (obj) => { const s = JSON.stringify(obj); for (const v of viewers) if (v.readyState === 1) v.send(s); };

  const publicView = (id) => {
    const reg = registry.devices[id];
    const d = live.get(id) || {};
    return {
      id, name: reg.name,
      lat: d.lat ?? reg.lat, lng: d.lng ?? reg.lng,
      link: d.link || 'offline', ts: d.ts || null,
      status: d.status || null, risk: d.risk ?? null,
      temperature: d.temperature ?? null, voltage: d.voltage ?? null, current: d.current ?? null,
      oilLevel: d.oilLevel ?? null,
      smoke: d.smoke ?? null, flame: d.flame ?? null, relay: d.relay ?? null, alarm: d.alarm ?? null,
      fw: d.fw || null, demo: d.demo === true,
    };
  };
  const snapshot = () => Object.keys(registry.devices).map(publicView);
  const pushDevice = (id) => broadcast({ t: 'update', device: publicView(id) });

  wss.on('connection', (ws, req) => {
    const ip = ipOf(req);
    const ban = fails.get(ip);
    if (ban && ban.until > Date.now()) { ws.close(1008, 'blocked'); return; }
    const c = (conns.get(ip) || 0) + 1;
    if (c > limits.maxConnsPerIp) { ws.close(1008, 'too many connections'); return; }
    conns.set(ip, c);

    let role = null, id = null, lastTelemetry = 0, violations = 0, alive = true;
    const authTimer = setTimeout(() => ws.close(1008, 'auth timeout'), limits.authTimeoutMs);

    const failAuth = () => {
      const f = fails.get(ip) || { n: 0, until: 0 };
      f.n += 1;
      if (f.n >= limits.maxAuthFails) { f.until = Date.now() + limits.banMs; f.n = 0; }
      fails.set(ip, f);
      ws.close(1008, 'unauthorized'); // same message for every cause: no hints to attackers
    };

    ws.on('pong', () => { alive = true; });
    ws.pingTimer = setInterval(() => {
      if (!alive) return ws.terminate();
      alive = false; ws.ping();
    }, limits.pingMs);

    ws.on('message', (raw, isBinary) => {
      if (isBinary) return ws.close(1003, 'text only');
      let m;
      try { m = JSON.parse(raw.toString('utf8')); } catch { return ws.close(1007, 'bad json'); }
      if (!m || typeof m !== 'object' || typeof m.t !== 'string') return ws.close(1007, 'bad message');

      if (!role) { // ---------- first message must be auth ----------
        if (m.t !== 'auth') return failAuth();
        if (m.role === 'viewer') {
          if (typeof m.key !== 'string' || m.key.length > 128 || !safeEqual(sha256(m.key), viewerHash)) return failAuth();
          role = 'viewer'; clearTimeout(authTimer); viewers.add(ws);
          send(ws, { t: 'snapshot', now: Date.now(), devices: snapshot() });
        } else if (m.role === 'device') {
          if (!registry.checkToken(m.id, m.key)) return failAuth();
          role = 'device'; id = m.id; clearTimeout(authTimer);
          const old = publishers.get(id);
          if (old && old !== ws) old.close(1000, 'replaced');
          publishers.set(id, ws);
          send(ws, { t: 'ok', id });
        } else return failAuth();
        return;
      }

      if (role === 'device' && m.t === 'telemetry') {
        const now = Date.now();
        if (now - lastTelemetry < limits.minTelemetryGapMs) {
          if (++violations > limits.maxViolations) ws.close(1008, 'rate limit');
          return; // dropped
        }
        lastTelemetry = now;
        const r = parseTelemetry(m);
        if (r.error) { if (++violations > limits.maxViolations) ws.close(1008, 'invalid data'); return send(ws, { t: 'error', reason: r.error }); }
        live.set(id, { ...r.value, ts: now, link: 'live' });
        pushDevice(id);
      } else if (m.t === 'ping') {
        send(ws, { t: 'pong', now: Date.now() });
      }
      // everything else is ignored: viewers can never write anything
    });

    ws.on('close', () => {
      clearTimeout(authTimer); clearInterval(ws.pingTimer);
      conns.set(ip, Math.max(0, (conns.get(ip) || 1) - 1));
      viewers.delete(ws);
      if (role === 'device' && publishers.get(id) === ws) {
        publishers.delete(id);
        const d = live.get(id);
        if (d) { d.link = 'offline'; pushDevice(id); }
      }
    });
    ws.on('error', () => {});
  });

  // Mark devices STALE / OFFLINE when data stops (also when the gateway phone just loses signal).
  const sweep = setInterval(() => {
    const now = Date.now();
    // Pick up devices added/revoked by the admin scripts without a restart; kick revoked publishers.
    try {
      if (registry.reload()) {
        for (const [id, ws] of publishers) if (!registry.has(id)) { ws.close(1008, 'revoked'); publishers.delete(id); }
        broadcast({ t: 'snapshot', now, devices: snapshot() });
      }
    } catch (e) { /* unreadable file: keep the last good registry */ }
    for (const [id, d] of live) {
      if (!registry.has(id)) { live.delete(id); continue; }
      const age = now - d.ts;
      const link = age > limits.offlineMs ? 'offline' : age > limits.staleMs ? 'stale' : 'live';
      if (link !== d.link) { d.link = link; pushDevice(id); }
    }
    for (const [ip, f] of fails) if (f.until && f.until < now) fails.delete(ip);
  }, limits.sweepMs);

  const close = () => new Promise((resolve) => {
    clearInterval(sweep);
    for (const ws of wss.clients) { clearInterval(ws.pingTimer); ws.terminate(); }
    wss.close(() => server.close(() => resolve()));
  });

  return { server, wss, registry, close, listen: (port, host) => new Promise((r) => server.listen(port, host, r)) };
}

module.exports = { createServer, LIMITS };

if (require.main === module) {
  try {
    const app = createServer();
    const port = Number(process.env.PORT) || 8080;
    app.listen(port).then(() => console.log(`STSMN realtime server on :${port} (use wss:// behind TLS)`));
    const stop = () => app.close().then(() => process.exit(0));
    process.on('SIGINT', stop); process.on('SIGTERM', stop);
  } catch (e) { console.error(e.message); process.exit(1); }
}
