'use strict';
// Strict validation of everything that comes over the wire. Unknown fields are dropped.

const ID_RE = /^[A-Z0-9][A-Z0-9-]{1,23}$/; // e.g. TR-001
const STATUSES = new Set(['SAFE', 'WARNING', 'EMERGENCY']);

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const numOrNull = (v, min, max) => (v === null ? null : isNum(v) && v >= min && v <= max ? v : undefined);

function validId(id) { return typeof id === 'string' && ID_RE.test(id); }

/** Returns a clean telemetry object or { error }. */
function parseTelemetry(m) {
  if (!m || typeof m !== 'object') return { error: 'not an object' };
  const out = {};
  if (!Number.isInteger(m.seq) || m.seq < 0) return { error: 'bad seq' };
  out.seq = m.seq;
  if (!STATUSES.has(m.status)) return { error: 'bad status' };
  out.status = m.status;
  if (!Number.isInteger(m.risk) || m.risk < 0 || m.risk > 100) return { error: 'bad risk' };
  out.risk = m.risk;
  for (const k of ['smoke', 'flame', 'relay', 'alarm']) {
    if (typeof m[k] !== 'boolean') return { error: 'bad ' + k };
    out[k] = m[k];
  }
  const ranges = { temperature: [-55, 125], voltage: [0, 25], current: [-5, 5], oilLevel: [0, 100] };
  for (const [k, [lo, hi]] of Object.entries(ranges)) {
    const v = m[k] === undefined ? null : numOrNull(m[k], lo, hi);
    if (v === undefined) return { error: 'bad ' + k };
    out[k] = v;
  }
  if (m.lat !== undefined || m.lng !== undefined) {
    if (!isNum(m.lat) || !isNum(m.lng) || m.lat < -90 || m.lat > 90 || m.lng < -180 || m.lng > 180) {
      return { error: 'bad location' };
    }
    out.lat = m.lat; out.lng = m.lng;
    if (m.demo !== undefined) { if (typeof m.demo !== 'boolean') return { error: 'bad demo' }; out.demo = m.demo; }
  }
  if (m.fw !== undefined) {
    if (typeof m.fw !== 'string' || m.fw.length > 24) return { error: 'bad fw' };
    out.fw = m.fw.replace(/[^\x20-\x7E]/g, '');
  }
  return { value: out };
}

function cleanName(s) {
  return String(s || '').replace(/[^\x20-\x7E]/g, '').trim().slice(0, 40);
}

module.exports = { validId, parseTelemetry, cleanName, ID_RE };
