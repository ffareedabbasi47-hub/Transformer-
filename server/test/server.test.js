'use strict';
const test = require('node:test');
const assert = require('node:assert');
const os = require('os');
const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { createServer } = require('../src/server');

const VIEWER = 'v'.repeat(32);
const good = { t: 'telemetry', seq: 1, status: 'SAFE', risk: 10, smoke: false, flame: false, relay: true, alarm: false,
  temperature: 30.5, voltage: 3.7, current: 0.4, oilLevel: 80 };

async function setup(limits) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stsmn-'));
  const app = createServer({ viewerKey: VIEWER, dataDir: dir, limits });
  const t1 = app.registry.add('TR-001', 'Ajrara', 26.85, 80.94);
  const t2 = app.registry.add('TR-002', 'Second', 26.9, 80.9);
  await app.listen(0, '127.0.0.1');
  const url = `ws://127.0.0.1:${app.server.address().port}/ws`;
  return { app, url, t1, t2 };
}
const open = (url) => new Promise((res, rej) => { const w = new WebSocket(url); w.once('open', () => res(w)); w.once('error', rej); });
const next = (w, pred = () => true) => new Promise((res) => {
  const h = (d) => { const m = JSON.parse(d.toString()); if (pred(m)) { w.off('message', h); res(m); } };
  w.on('message', h);
});
const closed = (w) => new Promise((res) => w.once('close', (code) => res(code)));
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

test('viewer gets snapshot, then live updates from two devices with separate IDs', async () => {
  const { app, url, t1, t2 } = await setup();
  const v = await open(url);
  const snap = next(v, (m) => m.t === 'snapshot');
  v.send(JSON.stringify({ t: 'auth', role: 'viewer', key: VIEWER }));
  const s = await snap;
  assert.deepStrictEqual(s.devices.map((d) => d.id).sort(), ['TR-001', 'TR-002']);
  assert.strictEqual(s.devices[0].link, 'offline');

  const d1 = await open(url), d2 = await open(url);
  d1.send(JSON.stringify({ t: 'auth', role: 'device', id: 'TR-001', key: t1 }));
  d2.send(JSON.stringify({ t: 'auth', role: 'device', id: 'TR-002', key: t2 }));
  await next(d1, (m) => m.t === 'ok'); await next(d2, (m) => m.t === 'ok');
  const u1 = next(v, (m) => m.t === 'update' && m.device.id === 'TR-001');
  d1.send(JSON.stringify(good));
  const m1 = await u1;
  assert.strictEqual(m1.device.status, 'SAFE');
  assert.strictEqual(m1.device.link, 'live');
  assert.strictEqual(m1.device.lat, 26.85);
  const u2 = next(v, (m) => m.t === 'update' && m.device.id === 'TR-002');
  d2.send(JSON.stringify({ ...good, status: 'EMERGENCY', risk: 95 }));
  assert.strictEqual((await u2).device.status, 'EMERGENCY');
  // device disconnect -> offline broadcast
  const off = next(v, (m) => m.t === 'update' && m.device.id === 'TR-001' && m.device.link === 'offline');
  d1.close(); await off;
  [v, d2].forEach((w) => w.close()); await app.close();
});

test('wrong keys are rejected; device cannot publish as another device', async () => {
  const { app, url, t1 } = await setup();
  const a = await open(url); const ca = closed(a);
  a.send(JSON.stringify({ t: 'auth', role: 'viewer', key: 'x'.repeat(32) }));
  assert.strictEqual(await ca, 1008);
  const b = await open(url); const cb = closed(b);
  b.send(JSON.stringify({ t: 'auth', role: 'device', id: 'TR-002', key: t1 })); // TR-001's token on TR-002
  assert.strictEqual(await cb, 1008);
  const c = await open(url); const cc = closed(c);
  c.send(JSON.stringify(good)); // no auth first
  assert.strictEqual(await cc, 1008);
  await app.close();
});

test('invalid telemetry is rejected and never broadcast; viewers cannot publish', async () => {
  const { app, url, t1 } = await setup({ minTelemetryGapMs: 0 });
  const v = await open(url); v.send(JSON.stringify({ t: 'auth', role: 'viewer', key: VIEWER }));
  await next(v, (m) => m.t === 'snapshot');
  const d = await open(url); d.send(JSON.stringify({ t: 'auth', role: 'device', id: 'TR-001', key: t1 }));
  await next(d, (m) => m.t === 'ok');
  let got = 0; v.on('message', (x) => { if (JSON.parse(x).t === 'update') got++; });
  const err = next(d, (m) => m.t === 'error');
  d.send(JSON.stringify({ ...good, temperature: 9999 }));
  assert.strictEqual((await err).reason, 'bad temperature');
  d.send(JSON.stringify({ ...good, status: 'HACKED' }));
  v.send(JSON.stringify(good)); // viewer tries to write
  await wait(200);
  assert.strictEqual(got, 0);
  await app.close();
});

test('rate limit drops bursts; oversize payload closes socket', async () => {
  const { app, url, t1 } = await setup({ minTelemetryGapMs: 300 });
  const v = await open(url); v.send(JSON.stringify({ t: 'auth', role: 'viewer', key: VIEWER }));
  await next(v, (m) => m.t === 'snapshot');
  const d = await open(url); d.send(JSON.stringify({ t: 'auth', role: 'device', id: 'TR-001', key: t1 }));
  await next(d, (m) => m.t === 'ok');
  let got = 0; v.on('message', (x) => { if (JSON.parse(x).t === 'update') got++; });
  for (let i = 0; i < 10; i++) d.send(JSON.stringify({ ...good, seq: i }));
  await wait(250);
  assert.strictEqual(got, 1);
  const cl = closed(d);
  d.send('x'.repeat(5000));
  assert.strictEqual(await cl, 1009);
  await app.close();
});

test('IP is blocked after repeated failed logins', async () => {
  const { app, url } = await setup({ maxAuthFails: 3, banMs: 5000 });
  for (let i = 0; i < 3; i++) {
    const w = await open(url); const c = closed(w);
    w.send(JSON.stringify({ t: 'auth', role: 'viewer', key: 'nope' })); await c;
  }
  const w = await open(url); const c = closed(w);
  w.send(JSON.stringify({ t: 'auth', role: 'viewer', key: VIEWER })); // even the right key is refused while banned
  assert.strictEqual(await c, 1008);
  await app.close();
});

test('silent device turns STALE then OFFLINE', async () => {
  const { app, url, t1 } = await setup({ staleMs: 300, offlineMs: 700, sweepMs: 100 });
  const v = await open(url); v.send(JSON.stringify({ t: 'auth', role: 'viewer', key: VIEWER }));
  await next(v, (m) => m.t === 'snapshot');
  const d = await open(url); d.send(JSON.stringify({ t: 'auth', role: 'device', id: 'TR-001', key: t1 }));
  await next(d, (m) => m.t === 'ok');
  d.send(JSON.stringify(good));
  await next(v, (m) => m.t === 'update' && m.device.link === 'live');
  await next(v, (m) => m.t === 'update' && m.device.link === 'stale');
  await next(v, (m) => m.t === 'update' && m.device.link === 'offline');
  await app.close();
});

test('server refuses to start without a viewer key', () => {
  assert.throws(() => createServer({ viewerKey: 'short', dataDir: fs.mkdtempSync(path.join(os.tmpdir(), 's-')) }));
});

test('device added or revoked by the admin script is picked up without restart', async () => {
  const { app, url } = await setup({ sweepMs: 100 });
  const v = await open(url); v.send(JSON.stringify({ t: 'auth', role: 'viewer', key: VIEWER }));
  await next(v, (m) => m.t === 'snapshot');
  // a second process writes the file (simulated with another Registry instance)
  const { Registry } = require('../src/registry');
  const other = new Registry(path.dirname(app.registry.file));
  await wait(20);
  const tok = other.add('TR-009', 'New', 1, 2);
  const snap = await next(v, (m) => m.t === 'snapshot' && m.devices.some((d) => d.id === 'TR-009'));
  assert.ok(snap);
  const d = await open(url); const ok = next(d, (m) => m.t === 'ok');
  d.send(JSON.stringify({ t: 'auth', role: 'device', id: 'TR-009', key: tok })); await ok;
  const cl = closed(d);
  await wait(20); other.remove('TR-009');
  assert.strictEqual(await cl, 1008); // revoked publisher is kicked
  await app.close();
});
