'use strict';
// Usage: node scripts/add-device.js TR-002 "Ajrara Substation" 26.85 80.94
const path = require('path');
const { Registry } = require('../src/registry');
const [id, name, lat, lng] = process.argv.slice(2);
if (!id) { console.error('Usage: add-device.js <ID> [name] [lat] [lng]'); process.exit(1); }
const dir = process.env.STSMN_DATA_DIR || path.join(__dirname, '..', 'data');
try {
  const token = new Registry(dir).add(id.toUpperCase(), name, lat ? Number(lat) : null, lng ? Number(lng) : null);
  console.log('Device created:', id.toUpperCase());
  console.log('TOKEN (shown only once, put it in the phone app -> Settings -> Cloud):');
  console.log(token);
} catch (e) { console.error('Error:', e.message); process.exit(1); }
