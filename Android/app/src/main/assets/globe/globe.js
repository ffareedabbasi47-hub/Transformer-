(function () {
  'use strict';
  // ---- reporting to the app --------------------------------------------------------------
  // Fatal = the globe cannot work at all (WebGL missing, map could not be created, style failed to load).
  // Notice = something degraded but the globe stays usable (tiles, terrain, a stray script error).
  var bridge = window.AndroidBridge || {};
  var sent = {};
  function once(key) { if (sent[key]) return false; sent[key] = true; return true; }
  function fatal(kind, detail) { if (once('F' + kind)) { try { bridge.onFatal && bridge.onFatal(kind, String(detail || '').slice(0, 160)); } catch (e) {} } }
  function notice(kind, detail) { if (once('N' + kind)) { try { bridge.onNotice && bridge.onNotice(kind, String(detail || '').slice(0, 160)); } catch (e) {} } }

  var scriptErrors = 0;
  window.addEventListener('error', function (e) {
    // A stray script error must never kill a usable globe: count it, tell the app once, keep running.
    scriptErrors++; notice('SCRIPT_ERROR', e && e.message);
  });
  window.addEventListener('unhandledrejection', function (e) { scriptErrors++; notice('SCRIPT_ERROR', e && e.reason); });

  try {
    var probe = document.createElement('canvas');
    if (!(probe.getContext('webgl2') || probe.getContext('webgl'))) { fatal('WEBGL_UNSUPPORTED', 'no WebGL context'); return; }
  } catch (e) { fatal('WEBGL_UNSUPPORTED', 'WebGL check failed'); return; }
  if (typeof maplibregl === 'undefined') { fatal('INIT_ERROR', 'map library missing'); return; }

  // ---- constants -------------------------------------------------------------------------
  var COLORS = { SAFE: '#2ECC71', WARNING: '#FFD21F', EMERGENCY: '#E5484D', STALE: '#8A94A6', OFFLINE: '#8A94A6' };
  var ID_RE = /^[A-Z0-9][A-Z0-9-]{1,23}$/;
  var HOME = { center: [80.9, 26.85], zoom: 3.2 }; // Uttar Pradesh, India
  var MAX_MARKERS = 2000;

  var SKY = {
    'sky-color': '#0a1a3a', 'horizon-color': '#2a6fd6', 'fog-color': '#0b1b3a',
    'sky-horizon-blend': 0.5, 'horizon-fog-blend': 0.5, 'fog-ground-blend': 0.2,
    'atmosphere-blend': ['interpolate', ['linear'], ['zoom'], 0, 1, 5, 1, 7, 0]
  };
  // Imagery providers, tried in order if one is blocked or unreachable on this phone/network.
  var PROVIDERS = {
    esri:  { tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'], maxzoom: 19, attribution: 'Imagery © Esri, Maxar, Earthstar Geographics' },
    osm:   { tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], maxzoom: 19, attribution: '© OpenStreetMap contributors' },
    carto: { tiles: ['https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png', 'https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png'], maxzoom: 19, attribution: '© OpenStreetMap contributors © CARTO' }
  };
  var CHAIN = { sat: ['esri', 'osm', 'carto'], street: ['osm', 'carto'] };
  var provIdx = 0;
  function style(kind, idx) {
    var p = PROVIDERS[CHAIN[kind][idx || 0]];
    var st = {
      version: 8,
      sky: SKY,
      sources: {
        base: { type: 'raster', tileSize: 256, maxzoom: p.maxzoom, tiles: p.tiles, attribution: p.attribution },
        relief: { type: 'raster-dem', tileSize: 256, maxzoom: 14, encoding: 'terrarium',
                  tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'], attribution: 'Terrain: Mapzen/AWS' }
      },
      layers: [{ id: 'ocean', type: 'background', paint: { 'background-color': '#0b3d6e' } },   // the globe is never an invisible dark ball, even with no pictures
               { id: 'base', type: 'raster', source: 'base', paint: { 'raster-resampling': 'linear', 'raster-fade-duration': 250 } }]
    };
    if (kind === 'sat') {                // place names and borders over the satellite picture, like Google Earth
      st.sources.labels = { type: 'raster', tileSize: 256, maxzoom: 19,
        tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}'],
        attribution: 'Labels © Esri' };
      st.layers.push({ id: 'labels', type: 'raster', source: 'labels', paint: { 'raster-opacity': 0.9 } });
    }
    return st;
  }

  // ---- map -------------------------------------------------------------------------------
  var kind = 'sat', loaded = false;
  var map;
  try {
    map = new maplibregl.Map({
      container: 'map', style: style(kind, 0), center: HOME.center, zoom: HOME.zoom, minZoom: 0.5, maxZoom: 20, pitch: 0, bearing: 0,
      maxPitch: 75, attributionControl: { compact: true },
      pixelRatio: Math.min(window.devicePixelRatio || 1, 2), canvasContextAttributes: { antialias: false, preserveDrawingBuffer: true }   // keeps the picture on screen on GPUs that drop an idle WebGL buffer
    });
  } catch (e) { fatal('INIT_ERROR', 'map could not start: ' + (e && e.message)); return; }
  map.addControl(new maplibregl.NavigationControl({ visualizePitch: true, showCompass: true }), 'top-right');

  // Globe projection must be applied after every style load (also after Satellite/Streets switches).
  map.on('style.load', function () {
    try { map.setProjection({ type: 'globe' }); } catch (e) { notice('SCRIPT_ERROR', 'globe projection unavailable: ' + e.message); }
    // READY as soon as the globe itself exists. The map's own 'load' event waits for every first tile, so on a slow or
    // blocked network it never fires and the app showed "Loading…" forever. Tiles now simply appear when they arrive.
    epoch = Date.now();
    if (!loaded) {
      loaded = true;
      setTimeout(spinStep, 300);
      try { bridge.onReady && bridge.onReady(); } catch (e) {}
    }
  });

  // GPU context can be taken away while the app is in the background: keep the map alive and redraw on return.
  var cv = map.getCanvas();
  cv.addEventListener('webglcontextlost', function (e) { e.preventDefault(); }, false);
  cv.addEventListener('webglcontextrestored', function () { try { map.resize(); map.triggerRepaint(); } catch (e) {} }, false);

  // ---- diagnostics (hidden unless the app turns debug on, or something goes wrong) ---------
  var tileOk = 0, tileFail = 0, lastErr = '', netInfo = '', dbgOn = false;
  function dbgText() {
    var d = document.getElementById('dbg'); if (!d) return;
    d.style.display = dbgOn ? 'block' : 'none';
    if (dbgOn) d.textContent = envInfo + ' · tiles ok ' + tileOk + ' / failed ' + tileFail + ' · z ' + map.getZoom().toFixed(1) +
      ' · js errors ' + scriptErrors + (netInfo ? ' · ' + netInfo : '') + (lastErr ? ' · ' + lastErr : '');
  }
  setInterval(dbgText, 1000);
  // A provider is "failed" when no tile arrives at all (blocked, unreachable, or the connection just hangs).
  // Move to the next one quickly so the picture appears within a couple of seconds instead of staying blank.

  // ---- environment info (shown in the diagnostics line) and a "nothing is drawn" probe ----------
  var envInfo = (function () {
    var out = [];
    try { var m = /Chrome\/(\d+)/.exec(navigator.userAgent); out.push('wv ' + (m ? m[1] : '?')); } catch (e) {}
    try {
      var c = document.createElement('canvas'), g = c.getContext('webgl2'); out.push(g ? 'gl2' : 'NO-gl2');
      if (g) { var x = g.getExtension('WEBGL_debug_renderer_info'); if (x) out.push(String(g.getParameter(x.UNMASKED_RENDERER_WEBGL)).slice(0, 24)); }
    } catch (e) { out.push('gl?'); }
    return out.join(' ');
  })();
  var probePending = false, probeDone = false;
  setTimeout(function () { probePending = true; try { map.triggerRepaint(); } catch (e) {} }, 5000);
  map.on('render', function () {
    if (!probePending || probeDone) return;
    probePending = false;
    try {
      var gl = map.getCanvas().getContext('webgl2') || map.getCanvas().getContext('webgl');
      var w = gl.drawingBufferWidth, h = gl.drawingBufferHeight, px = new Uint8Array(4), dark = 0, n = 0;
      [[.5, .5], [.3, .5], [.7, .5], [.5, .3], [.5, .7]].forEach(function (p) {
        gl.readPixels(Math.floor(w * p[0]), Math.floor(h * p[1]), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        n++; if (px[3] === 0 || px[0] + px[1] + px[2] < 24) dark++;
      });
      probeDone = true;
      try {                                                    // layout facts: tells us why a drawn globe may not be visible
        var cv = map.getCanvas(), cs = getComputedStyle(cv), mr = map.getContainer().getBoundingClientRect(), cr = cv.getBoundingClientRect();
        gl.readPixels(Math.floor(w / 2), Math.floor(h / 2), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        notice('DIAG', 'win ' + innerWidth + 'x' + innerHeight + ' dpr ' + devicePixelRatio + ' map ' + Math.round(mr.width) + 'x' + Math.round(mr.height) +
          '@' + Math.round(mr.top) + ' cv ' + cv.width + 'x' + cv.height + '@' + Math.round(cr.top) + ' ' + cs.display + '/' + cs.visibility + '/' + cs.opacity +
          ' px ' + px[0] + ',' + px[1] + ',' + px[2] + ',' + px[3] + ' css ' + document.styleSheets.length + ' z ' + map.getZoom().toFixed(1));
      } catch (e) {}
      if (dark === n) { dbgOn = true; lastErr = 'blank render'; notice('RENDER_BLANK', 'all sampled pixels dark'); }
      else notice('PAINTED', 'drawing');   // proof for the app that this phone really draws the globe
    } catch (e) { probeDone = true; }
  });
  notice('ENV', envInfo);                              // phone info for the app's diagnostics line
  var epoch = Date.now();
  function providerFailed(why) {
    dbgOn = true;                                      // show the reason on screen so it can be reported
    if (provIdx + 1 < CHAIN[kind].length) {
      provIdx++; tileFail = 0; tileOk = 0; epoch = Date.now();
      if (kind === 'sat') notice('SATELLITE_FALLBACK', CHAIN[kind][provIdx]);
      map.setStyle(style(kind, provIdx));
    } else notice('TILES_FAILED', why);
  }
  setInterval(function () {
    if (loaded && tileOk === 0 && Date.now() - epoch > 9000) providerFailed('no tiles after 9 s');
  }, 1000);
  map.on('data', function (ev) { if (ev && ev.dataType === 'source' && ev.tile) tileOk++; });
  map.on('error', function (ev) {
    var msg = String((ev && ev.error && ev.error.message) || 'error').slice(0, 80);
    lastErr = msg;
    var src = ev && ev.sourceId;
    if (src === 'relief') {              // terrain only: drop it, the globe itself keeps working
      terrainOn = false; try { map.setTerrain(null); } catch (e) {}
      notice('TERRAIN_FAILED', msg); return;
    }
    if (src === 'labels') return;         // optional place-name overlay: ignore its failures
    if (src) {                           // individual satellite/street tile
      tileFail++;
      if (tileFail >= 6 && tileOk === 0) providerFailed(msg);
      return;
    }
    if (!loaded) fatal('LOAD_ERROR', msg);   // style itself failed before the first load
    else notice('SCRIPT_ERROR', msg);
  });

  // ---- camera ---------------------------------------------------------------------------
  var spinning = false, fitted = false;   // opens on India; the app can start a slow spin with stsmn.spin(true)
  function stopSpin() { spinning = false; }
  ['mousedown', 'touchstart', 'wheel'].forEach(function (e) { map.on(e, stopSpin); });
  function spinStep() {
    if (!spinning || map.getZoom() > 3.5) return;
    var c = map.getCenter();
    map.easeTo({ center: [c.lng + 25, Math.max(-40, Math.min(40, c.lat))], duration: 2000, easing: function (t) { return t; } });
  }
  map.on('moveend', spinStep);

  var terrainOn = false; // relief is heavy on weak phones: only after the user taps "Tilt 3D"
  function syncTerrain() {
    try {
      if (!map.getSource('relief')) return;
      if (terrainOn && map.getZoom() >= 9) { if (!map.getTerrain()) map.setTerrain({ source: 'relief', exaggeration: 1.35 }); }
      else if (map.getTerrain()) map.setTerrain(null);
    } catch (e) { /* terrain unsupported: stay flat */ }
  }
  map.on('zoomend', syncTerrain);
  map.on('styledata', syncTerrain);

  // ---- markers (DOM built with textContent only: server text can never become HTML) ----------
  var markers = {}; // id -> {marker, el, parts, sig}
  var selectedId = null;

  function build(d) {
    var el = document.createElement('div'); el.className = 'mk';
    var pill = document.createElement('div'); pill.className = 'pill';
    var t = document.createElement('div'); t.className = 't';
    var s = document.createElement('div'); s.className = 's';
    var n = document.createElement('div'); n.className = 'n';
    pill.appendChild(t); pill.appendChild(s); pill.appendChild(n);
    var dot = document.createElement('div'); dot.className = 'dot';
    el.appendChild(pill); el.appendChild(dot);
    el.addEventListener('click', function (ev) { ev.stopPropagation(); pick(d.id, true); });
    return { el: el, t: t, s: s, n: n, dot: dot };
  }
  function paint(m, d) {
    var c = COLORS[d.state] || COLORS.OFFLINE;
    m.parts.t.textContent = 'STSMN-' + d.id.replace(/^STSMN-/, '');
    m.parts.s.textContent = d.state; m.parts.s.style.color = c;
    m.parts.n.textContent = d.demo ? 'DEMO LOCATION' : '';
    m.parts.dot.style.background = c;
    m.el.classList.toggle('pulse', d.state === 'EMERGENCY');
    m.el.classList.toggle('sel', d.id === selectedId);
  }
  function pick(id, notify) {
    selectedId = id;
    Object.keys(markers).forEach(function (k) { markers[k].el.classList.toggle('sel', k === id); });
    if (notify && bridge.onSelect) { try { bridge.onSelect(id); } catch (e) {} }
  }

  function valid(d) {
    return d && typeof d.id === 'string' && ID_RE.test(d.id) && typeof d.lat === 'number' && typeof d.lng === 'number' &&
      isFinite(d.lat) && isFinite(d.lng) && Math.abs(d.lat) <= 90 && Math.abs(d.lng) <= 180 && COLORS[d.state];
  }

  // Bounds that also work across the 180° meridian: cut the circle at the biggest gap between longitudes.
  function boundsOf(points) {
    var lngs = points.map(function (p) { return p[0]; }).sort(function (a, b) { return a - b; });
    var best = 0, at = 0;
    for (var i = 0; i < lngs.length; i++) {
      var next = i === lngs.length - 1 ? lngs[0] + 360 : lngs[i + 1];
      if (next - lngs[i] > best) { best = next - lngs[i]; at = i; }
    }
    var west = lngs[(at + 1) % lngs.length], east = lngs[at];
    if (east < west) east += 360;
    var lats = points.map(function (p) { return p[1]; });
    return { west: west, east: east, south: Math.min.apply(null, lats), north: Math.max.apply(null, lats) };
  }

  var api = {
    /** devices: [{id, lat, lng, state, demo}] - only transformers with a valid position are drawn. */
    setDevices: function (list) {
      if (!Array.isArray(list)) return;
      var seen = {};
      list.slice(0, MAX_MARKERS).forEach(function (d) {
        if (!valid(d)) return;
        seen[d.id] = true;
        var m = markers[d.id];
        if (!m) {
          var parts = build(d);
          m = markers[d.id] = { parts: parts, el: parts.el, marker: new maplibregl.Marker({ element: parts.el, anchor: 'bottom' }).setLngLat([d.lng, d.lat]).addTo(map), sig: '' };
        }
        var sig = [d.lat, d.lng, d.state, !!d.demo].join('|');
        if (m.sig !== sig) { m.marker.setLngLat([d.lng, d.lat]); paint(m, d); m.sig = sig; }
      });
      Object.keys(markers).forEach(function (id) { if (!seen[id]) { markers[id].marker.remove(); delete markers[id]; if (selectedId === id) selectedId = null; } });
      if (!fitted && Object.keys(markers).length) { fitted = true; api.fitAll(); }
    },
    select: function (id) { if (id === null || (typeof id === 'string' && ID_RE.test(id))) pick(id, false); },
    focus: function (id) {
      var m = markers[id]; if (!m) return;
      stopSpin(); pick(id, false);
      map.flyTo({ center: m.marker.getLngLat(), zoom: 13, pitch: 40, bearing: 0, speed: 0.8, curve: 1.5, essential: true });
    },
    fitAll: function () {
      stopSpin();
      var ids = Object.keys(markers);
      if (!ids.length) { map.flyTo({ center: HOME.center, zoom: HOME.zoom, pitch: 0, bearing: 0, essential: true }); return; }
      if (ids.length === 1) { api.focus(ids[0]); return; }
      var pts = ids.map(function (id) { var p = markers[id].marker.getLngLat(); return [p.lng, p.lat]; });
      var b = boundsOf(pts);
      if (b.east - b.west < 1e-4 && b.north - b.south < 1e-4) { api.focus(ids[0]); return; } // identical positions
      map.fitBounds([[b.west, b.south], [b.east, b.north]], { padding: 90, maxZoom: 12, pitch: 35, duration: 1800 });
    },
    setStyle: function (k) {
      if (k !== 'sat' && k !== 'street') return;
      kind = k; provIdx = 0; epoch = Date.now(); tileOk = 0; tileFail = 0; sent['NTILES_FAILED'] = false;
      map.setStyle(style(k, 0));
    },
    tilt: function (on) { terrainOn = !!on; map.easeTo({ pitch: on ? 60 : 0, duration: 700 }); setTimeout(syncTerrain, 800); },
    spin: function (on) { spinning = !!on; if (on) spinStep(); },
    debug: function (on) { dbgOn = !!on; dbgText(); },
    setNet: function (s) { netInfo = String(s || '').slice(0, 60); },
    // test hooks (read-only)
    markerCount: function () { return Object.keys(markers).length; },
    selected: function () { return selectedId; },
    styleKind: function () { return kind; },
    provider: function () { return CHAIN[kind][provIdx]; }
  };
  window.stsmn = api;
  map.on('click', function () { pick(null, true); });
})();
