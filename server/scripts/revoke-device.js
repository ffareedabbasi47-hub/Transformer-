'use strict';
// Usage: node scripts/revoke-device.js TR-002          -> removes the device; its token stops working within ~2 s
//        node scripts/revoke-device.js TR-002 --rotate -> keeps the device, issues a NEW token (old one dies)
const path = require('path');
const { Registry } = require('../src/registry');
const [id, flag] = process.argv.slice(2);
if (!id) { console.error('Usage: revoke-device.js <ID> [--rotate]'); process.exit(1); }
const dir = process.env.STSMN_DATA_DIR || path.join(__dirname, '..', 'data');
try {
  const reg = new Registry(dir);
  const ID = id.toUpperCase();
  if (flag === '--rotate') {
    const d = reg.devices[ID];
    if (!d) throw new Error('Unknown device: ' + ID);
    reg.remove(ID);
    const token = reg.add(ID, d.name, d.lat, d.lng);
    console.log('New token for', ID, '(shown only once):'); console.log(token);
  } else { reg.remove(ID); console.log('Revoked', ID); }
} catch (e) { console.error('Error:', e.message); process.exit(1); }
