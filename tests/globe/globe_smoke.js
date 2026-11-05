// Host smoke test for the bundled 3D globe page (no phone needed).
// Run:  NODE_PATH=<dir with playwright> node tests/globe/globe_smoke.js
// Needs Chromium (set CHROMIUM=/path/to/chromium if Playwright's own is not installed). Tiles are stubbed offline.
const http = require('http'), fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../Android/app/src/main/assets/globe');
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
const TYPES = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html' };
let failures = 0;
const check = (name, ok, extra) => { console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '  -> ' + extra)); if (!ok) failures++; };

const srv = http.createServer((q, r) => {
  const f = path.join(root, q.url.split('?')[0] === '/' ? 'index.html' : q.url.split('?')[0]);
  fs.readFile(f, (e, d) => { if (e) { r.writeHead(404); r.end(); return; } r.writeHead(200, { 'content-type': TYPES[path.extname(f)] || 'application/octet-stream' }); r.end(d); });
});

async function page(browser, bridgeSrc, opts = {}) {
  const p = await browser.newPage({ viewport: { width: 400, height: 760 } });
  const log = { ready: 0, fatal: [], notice: [], select: [], errors: [] };
  await p.exposeFunction('__ready', () => log.ready++);
  await p.exposeFunction('__fatal', (k, d) => log.fatal.push(k));
  await p.exposeFunction('__notice', (k, d) => log.notice.push(k));
  await p.exposeFunction('__select', id => log.select.push(id));
  await p.addInitScript(bridgeSrc);
  p.on('pageerror', e => log.errors.push(e.message));
  await p.route(/^https:\/\//, r => opts.failTiles ? r.abort() : r.fulfill({ status: 200, contentType: 'image/png', body: PNG }));
  await p.goto('http://localhost:8765/index.html');
  return { p, log };
}
const BRIDGE = `window.AndroidBridge = { onReady: () => window.__ready(), onFatal: (k, d) => window.__fatal(k, d), onNotice: (k, d) => window.__notice(k, d), onSelect: id => window.__select(id) };`;

srv.listen(8765, async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM || undefined, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  try {
    // 1. normal start
    let { p, log } = await page(browser, BRIDGE);
    await p.waitForFunction(() => window.stsmn, null, { timeout: 15000 });
    await p.waitForTimeout(2500);
    check('reports READY exactly once', log.ready === 1, log.ready);
    check('no fatal on normal start', log.fatal.length === 0, log.fatal);

    // 2. empty fleet, invalid data, add / update / remove
    await p.evaluate(() => stsmn.setDevices([])); check('empty fleet is fine', (await p.evaluate(() => stsmn.markerCount())) === 0);
    await p.evaluate(() => stsmn.setDevices([
      { id: 'T01', lat: 26.85, lng: 80.9, state: 'SAFE' },
      { id: 'T02', lat: 28.6, lng: 77.2, state: 'EMERGENCY', demo: true },
      { id: '<img src=x onerror=alert(1)>', lat: 1, lng: 1, state: 'SAFE' },
      { id: 'T03', lat: 999, lng: 1, state: 'SAFE' },
      { id: 'T04', lat: 1, lng: 181, state: 'SAFE' },
      { id: 'T05', lat: NaN, lng: 1, state: 'SAFE' },
      { id: 'T06', lat: 1, lng: 1, state: 'BOGUS' },
      null, 'x', 5,
    ]));
    check('only valid devices drawn', (await p.evaluate(() => stsmn.markerCount())) === 2, await p.evaluate(() => stsmn.markerCount()));
    const html = await p.evaluate(() => document.querySelector('.mk').innerHTML);
    check('marker text is never HTML', !/<img/.test(html));
    await p.evaluate(() => { window.__el = document.querySelector('.mk'); });
    await p.evaluate(() => stsmn.setDevices([{ id: 'T01', lat: 26.85, lng: 80.9, state: 'SAFE' }, { id: 'T02', lat: 28.6, lng: 77.2, state: 'EMERGENCY', demo: true }]));
    check('unchanged marker is not recreated', await p.evaluate(() => window.__el === document.querySelector('.mk')));
    await p.evaluate(() => stsmn.setDevices([{ id: 'T01', lat: 26.9, lng: 80.9, state: 'WARNING' }]));
    check('removed device leaves no marker', (await p.evaluate(() => stsmn.markerCount())) === 1);
    check('state update is painted', (await p.evaluate(() => document.querySelector('.mk .s').textContent)) === 'WARNING');
    check('EMERGENCY pulses', await p.evaluate(() => { stsmn.setDevices([{ id: 'T01', lat: 26.9, lng: 80.9, state: 'EMERGENCY' }]); return document.querySelector('.mk').classList.contains('pulse'); }));

    // 3. selection + focus
    await p.evaluate(() => { stsmn.select('T01'); });
    check('select marks marker', (await p.evaluate(() => stsmn.selected())) === 'T01');
    await p.evaluate(() => stsmn.select("T01'); alert(1);//"));
    check('hostile select ignored', (await p.evaluate(() => stsmn.selected())) === 'T01');
    await p.click('.mk .pill'); check('tap reports selection to app', log.select.includes('T01'), log.select);
    await p.evaluate(() => stsmn.setDevices([])); check('removing selected device clears selection', (await p.evaluate(() => stsmn.selected())) === null);

    // 4. Show all: zero / one / identical / dateline
    await p.evaluate(() => stsmn.fitAll());
    await p.evaluate(() => stsmn.setDevices([{ id: 'T01', lat: 10, lng: 10, state: 'SAFE' }, { id: 'T02', lat: 10, lng: 10, state: 'SAFE' }]));
    await p.evaluate(() => stsmn.fitAll());
    await p.evaluate(() => stsmn.setDevices([{ id: 'F1', lat: -17, lng: 179.5, state: 'SAFE' }, { id: 'F2', lat: -17.5, lng: -179.5, state: 'SAFE' }]));
    await p.evaluate(() => stsmn.fitAll()); await p.waitForTimeout(2500);
    check('no exceptions in Show all (0/1/identical/dateline)', log.errors.length === 0, log.errors);

    // 5. repeated style switching keeps markers + selection
    await p.evaluate(() => stsmn.select('F1'));
    for (let i = 0; i < 6; i++) { await p.evaluate(k => stsmn.setStyle(k), i % 2 ? 'sat' : 'street'); await p.waitForTimeout(500); }
    check('markers survive 6 style switches', (await p.evaluate(() => stsmn.markerCount())) === 2);
    check('selection survives style switches', (await p.evaluate(() => stsmn.selected())) === 'F1');
    check('last style applied', (await p.evaluate(() => stsmn.styleKind())) === 'sat');
    await p.evaluate(() => stsmn.setStyle('evil')); check('unknown style ignored', (await p.evaluate(() => stsmn.styleKind())) === 'sat');
    await p.evaluate(() => { stsmn.tilt(true); stsmn.tilt(false); });
    check('no page errors during all of the above', log.errors.length === 0, log.errors);

    // 6. a stray script error is only a notice, never fatal
    await p.evaluate(() => { setTimeout(() => { throw new Error('stray'); }, 0); });
    await p.waitForTimeout(300);
    check('stray JS error -> notice, not fatal', log.fatal.length === 0 && log.notice.includes('SCRIPT_ERROR'), JSON.stringify(log));
    check('notice sent once (no duplicates)', log.notice.filter(n => n === 'SCRIPT_ERROR').length === 1);
    await p.close();

    // 7. tiles unavailable -> TILES_FAILED notice only, globe stays ready
    ({ p, log } = await page(browser, BRIDGE, { failTiles: true }));
    await p.waitForFunction(() => window.stsmn, null, { timeout: 15000 });
    await p.waitForTimeout(5000);
    check('offline tiles: READY and TILES_FAILED notice, no fatal', log.ready === 1 && log.fatal.length === 0 && log.notice.includes('TILES_FAILED'), JSON.stringify(log));
    await p.close();

    // 8. WebGL unsupported -> fatal WEBGL_UNSUPPORTED, never READY
    ({ p, log } = await page(browser, BRIDGE + `HTMLCanvasElement.prototype.getContext = function () { return null; };`));
    await p.waitForTimeout(1500);
    check('no WebGL -> WEBGL_UNSUPPORTED fatal, not ready', log.fatal.includes('WEBGL_UNSUPPORTED') && log.ready === 0, JSON.stringify(log));
    await p.close();
  } catch (e) { console.log('FAIL harness error: ' + e.message); failures++; }
  await browser.close(); srv.close();
  console.log(failures ? failures + ' check(s) FAILED' : 'ALL CHECKS PASSED');
  process.exit(failures ? 1 : 0);
});
