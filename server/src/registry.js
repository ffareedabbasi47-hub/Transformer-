'use strict';
// Device registry: one JSON file in a private data dir. Tokens are stored ONLY as SHA-256 hashes.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { validId, cleanName } = require('./validate');

const sha256 = (s) => crypto.createHash('sha256').update(s).digest();

function safeEqual(a, b) {
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

class Registry {
  constructor(dir) {
    this.file = path.join(dir, 'devices.json');
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    this.devices = {};
    this.mtime = 0;
    this.reload();
  }

  /** Re-reads devices.json when another process (add-device / revoke-device scripts) changed it. */
  reload() {
    if (!fs.existsSync(this.file)) { this.devices = {}; this.mtime = 0; return false; }
    const m = fs.statSync(this.file).mtimeMs;
    if (m === this.mtime) return false;
    this.devices = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    this.mtime = m;
    return true;
  }

  save() {
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.devices, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
    this.mtime = fs.statSync(this.file).mtimeMs;
  }

  /** Creates a device and returns its token ONCE (never stored in clear). */
  add(id, name, lat, lng) {
    this.reload();
    if (!validId(id)) throw new Error('Invalid id (use A-Z, 0-9, dash; 2-24 chars, e.g. TR-002)');
    if (this.devices[id]) throw new Error('Device already exists: ' + id);
    if (lat != null && (!Number.isFinite(lat) || lat < -90 || lat > 90)) throw new Error('Invalid latitude');
    if (lng != null && (!Number.isFinite(lng) || lng < -180 || lng > 180)) throw new Error('Invalid longitude');
    const token = crypto.randomBytes(24).toString('hex');
    this.devices[id] = {
      name: cleanName(name) || id,
      tokenHash: sha256(token).toString('hex'),
      lat: lat ?? null, lng: lng ?? null,
      createdAt: Date.now(),
    };
    this.save();
    return token;
  }

  remove(id) { this.reload(); if (!this.has(id)) throw new Error('Unknown device: ' + id); delete this.devices[id]; this.save(); }

  has(id) { return Object.prototype.hasOwnProperty.call(this.devices, id); }

  checkToken(id, token) {
    if (!this.has(id) || typeof token !== 'string' || token.length > 128) return false;
    return safeEqual(sha256(token), Buffer.from(this.devices[id].tokenHash, 'hex'));
  }
}

module.exports = { Registry, sha256, safeEqual };
