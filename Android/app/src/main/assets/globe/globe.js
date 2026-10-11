(function () {
  'use strict';
  // ---- reporting to the app --------------------------------------------------------------
  var bridge = window.AndroidBridge || {};
  var sent = {};
  function once(key) { if (sent[key]) return false; sent[key] = true; return true; }
  function fatal(kind, detail) { if (once('F' + kind)) { try { bridge.onFatal && bridge.onFatal(kind, String(detail || '').slice(0, 160)); } catch (e) {} } }
  function notice(kind, detail) { if (once('N' + kind)) { try { bridge.onNotice && bridge.onNotice(kind, String(detail || '').slice(0, 160)); } catch (e) {} } }

  var scriptErrors = 0;
  window.addEventListener('error', function (e) {
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
  var HOME = { center: [80.9, 26.85], zoom: 1.8, pitch: 20 }; // Uttar Pradesh, India - global space perspective
  var MAX_MARKERS = 2000;
  var DEG2RAD = Math.PI / 180;

  var SKY = {
    'sky-color': '#061024',
    'horizon-color': '#1f65c9',
    'fog-color': '#061024',
    'sky-horizon-blend': 0.6,
    'horizon-fog-blend': 0.6,
    'fog-ground-blend': 0.3,
    'atmosphere-blend': 0.8
  };

  function style(kind) {
    var sat = kind !== 'street';
    return {
      version: 8,
      projection: { type: 'globe' },
      sky: SKY,
      sources: {
        base: sat
          ? { type: 'raster', tileSize: 256, maxzoom: 19, tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
              attribution: 'Imagery © Esri, Maxar, Earthstar Geographics' }
          : { type: 'raster', tileSize: 256, maxzoom: 19, tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
              attribution: '© OpenStreetMap contributors' },
        relief: { type: 'raster-dem', tileSize: 256, maxzoom: 14, encoding: 'terrarium',
                  tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'], attribution: 'Terrain: Mapzen/AWS' }
      },
      layers: [{ id: 'base', type: 'raster', source: 'base' }]
    };
  }

  // ---- map -------------------------------------------------------------------------------
  var kind = 'sat', loaded = false;
  var map;
  try {
    map = new maplibregl.Map({
      container: 'map',
      style: style(kind),
      center: HOME.center,
      zoom: HOME.zoom,
      minZoom: 1.0,
      maxZoom: 18,
      pitch: HOME.pitch,
      bearing: 0,
      maxPitch: 70,
      attributionControl: { compact: true },
      pixelRatio: Math.min(window.devicePixelRatio || 1, 2),
      canvasContextAttributes: { antialias: true, alpha: true }
    });
  } catch (e) { fatal('INIT_ERROR', 'map could not start: ' + (e && e.message)); return; }
  map.addControl(new maplibregl.NavigationControl({ visualizePitch: true, showCompass: true }), 'top-right');

  // Globe projection must be applied after every style load (also after Satellite/Streets switches).
  map.on('style.load', function () {
    try { map.setProjection({ type: 'globe' }); } catch (e) { notice('SCRIPT_ERROR', 'globe projection unavailable: ' + e.message); }
  });

  // GPU context can be taken away while the app is in the background: keep the map alive and redraw on return.
  var cv = map.getCanvas();
  cv.addEventListener('webglcontextlost', function (e) { e.preventDefault(); }, false);
  cv.addEventListener('webglcontextrestored', function () { try { map.resize(); map.triggerRepaint(); } catch (e) {} }, false);

  // ---- diagnostics -----------------------------------------------------------------------
  var tileOk = 0, tileFail = 0, lastErr = '', netInfo = '', dbgOn = false;
  function dbgText() {
    var d = document.getElementById('dbg'); if (!d) return;
    d.style.display = dbgOn ? 'block' : 'none';
    if (dbgOn) d.textContent = 'tiles ok ' + tileOk + ' / failed ' + tileFail + ' · z ' + map.getZoom().toFixed(1) +
      ' · js errors ' + scriptErrors + (netInfo ? ' · ' + netInfo : '') + (lastErr ? ' · ' + lastErr : '');
  }
  setInterval(dbgText, 1000);
  map.on('data', function (ev) { if (ev && ev.dataType === 'source' && ev.tile) tileOk++; });
  map.on('error', function (ev) {
    var msg = String((ev && ev.error && ev.error.message) || 'error').slice(0, 80);
    lastErr = msg;
    var src = ev && ev.sourceId;
    if (src === 'relief') {
      terrainOn = false; try { map.setTerrain(null); } catch (e) {}
      notice('TERRAIN_FAILED', msg); return;
    }
    if (src) {
      tileFail++;
      if (tileFail >= 8 && tileOk === 0) notice('TILES_FAILED', msg);
      return;
    }
    if (!loaded) fatal('LOAD_ERROR', msg);
    else notice('SCRIPT_ERROR', msg);
  });

  // ---- smooth 60fps auto-rotation --------------------------------------------------------
  var spinning = true;
  var spinReq = null;
  var lastSpinTime = 0;

  function notifySpin(active) {
    try { if (bridge.onSpinState) bridge.onSpinState(active); } catch (e) {}
  }

  function stopSpin() {
    if (spinning) {
      spinning = false;
      if (spinReq) { cancelAnimationFrame(spinReq); spinReq = null; }
      try { map.stop(); } catch (e) {}
      notifySpin(false);
    }
  }

  function startSpin() {
    if (!spinning) {
      spinning = true;
      lastSpinTime = 0;
      notifySpin(true);
      spinReq = requestAnimationFrame(spinLoop);
    }
  }

  function spinLoop(ts) {
    if (!spinning) return;
    if (!lastSpinTime) lastSpinTime = ts;
    var dt = (ts - lastSpinTime) / 1000;
    lastSpinTime = ts;

    // Only spin when zoomed out and not in a programmatic camera flight
    if (map.getZoom() <= 4.2 && !map.isMoving()) {
      var c = map.getCenter();
      var nextLng = c.lng + (3.2 * dt);
      if (nextLng > 180) nextLng -= 360;
      map.setCenter([nextLng, Math.max(-45, Math.min(45, c.lat))]);
    }

    spinReq = requestAnimationFrame(spinLoop);
  }

  ['mousedown', 'touchstart', 'wheel', 'pointerdown'].forEach(function (e) {
    map.on(e, stopSpin);
  });

  // ---- terrain ---------------------------------------------------------------------------
  var terrainOn = false;
  function syncTerrain() {
    try {
      if (!map.getSource('relief')) return;
      if (terrainOn) {
        if (!map.getTerrain()) map.setTerrain({ source: 'relief', exaggeration: 1.4 });
      } else {
        if (map.getTerrain()) map.setTerrain(null);
      }
    } catch (e) {}
  }
  map.on('styledata', syncTerrain);

  map.on('load', function () {
    if (loaded) return;
    loaded = true;
    spinReq = requestAnimationFrame(spinLoop);
    try { bridge.onReady && bridge.onReady(); } catch (e) {}
  });

  // ---- markers & spherical horizon occlusion ---------------------------------------------
  var markers = {}; // id -> { marker, el, parts, sig, coords }
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
    m.parts.dot.style.color = c;
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

  function updateOcclusion() {
    var c = map.getCenter();
    var clat = c.lat * DEG2RAD;
    var clng = c.lng * DEG2RAD;
    var sinClat = Math.sin(clat);
    var cosClat = Math.cos(clat);
    var z = map.getZoom();
    var isGlobe = z < 5.5;

    Object.keys(markers).forEach(function (id) {
      var m = markers[id];
      if (!m || !m.coords) return;
      if (isGlobe) {
        var mlat = m.coords[1] * DEG2RAD;
        var mlng = m.coords[0] * DEG2RAD;
        // Dot product between camera center unit vector and marker unit vector on unit sphere
        var cosDist = sinClat * Math.sin(mlat) + cosClat * Math.cos(mlat) * Math.cos(mlng - clng);
        var visible = cosDist > 0.05;
        m.el.classList.toggle('occluded', !visible);
        m.el.classList.toggle('compact', z < 3.5);
      } else {
        m.el.classList.remove('occluded');
        m.el.classList.remove('compact');
      }
    });
  }

  map.on('move', updateOcclusion);
  map.on('render', updateOcclusion);

  // Bounds that also work across the 180° meridian
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
    setDevices: function (list) {
      if (!Array.isArray(list)) return;
      var seen = {};
      list.slice(0, MAX_MARKERS).forEach(function (d) {
        if (!valid(d)) return;
        seen[d.id] = true;
        var m = markers[d.id];
        if (!m) {
          var parts = build(d);
          m = markers[d.id] = {
            parts: parts,
            el: parts.el,
            coords: [d.lng, d.lat],
            marker: new maplibregl.Marker({ element: parts.el, anchor: 'bottom' }).setLngLat([d.lng, d.lat]).addTo(map),
            sig: ''
          };
        }
        m.coords = [d.lng, d.lat];
        var sig = [d.lat, d.lng, d.state, !!d.demo].join('|');
        if (m.sig !== sig) { m.marker.setLngLat([d.lng, d.lat]); paint(m, d); m.sig = sig; }
      });
      Object.keys(markers).forEach(function (id) {
        if (!seen[id]) { markers[id].marker.remove(); delete markers[id]; if (selectedId === id) selectedId = null; }
      });
      updateOcclusion();
    },
    select: function (id) { if (id === null || (typeof id === 'string' && ID_RE.test(id))) pick(id, false); },
    focus: function (id) {
      var m = markers[id]; if (!m) return;
      stopSpin(); pick(id, false);
      map.flyTo({ center: m.marker.getLngLat(), zoom: 12.5, pitch: 45, bearing: 0, speed: 0.8, curve: 1.4, essential: true });
    },
    globe: function () {
      map.flyTo({ center: HOME.center, zoom: HOME.zoom, pitch: HOME.pitch, bearing: 0, speed: 0.8, curve: 1.4, essential: true });
      setTimeout(function () { startSpin(); }, 1200);
    },
    resetGlobe: function () { api.globe(); },
    fitAll: function () {
      stopSpin();
      var ids = Object.keys(markers);
      if (!ids.length) { api.globe(); return; }
      if (ids.length === 1) {
        var p = markers[ids[0]].marker.getLngLat();
        map.flyTo({ center: p, zoom: 5.5, pitch: 35, bearing: 0, speed: 0.8, curve: 1.4, essential: true });
        return;
      }
      var pts = ids.map(function (id) { var p = markers[id].marker.getLngLat(); return [p.lng, p.lat]; });
      var b = boundsOf(pts);
      if (b.east - b.west < 1e-4 && b.north - b.south < 1e-4) {
        var p0 = markers[ids[0]].marker.getLngLat();
        map.flyTo({ center: p0, zoom: 5.5, pitch: 35, bearing: 0, speed: 0.8, curve: 1.4, essential: true });
        return;
      }
      map.fitBounds([[b.west, b.south], [b.east, b.north]], { padding: 90, maxZoom: 8, pitch: 35, duration: 1800 });
    },
    setStyle: function (k) {
      if (k !== 'sat' && k !== 'street') return;
      kind = k; tileOk = 0; tileFail = 0; sent['NTILES_FAILED'] = false;
      map.setStyle(style(k));
    },
    tilt: function (on) { terrainOn = !!on; map.easeTo({ pitch: on ? 55 : 0, duration: 700 }); setTimeout(syncTerrain, 800); },
    spin: function (on) { if (on) startSpin(); else stopSpin(); },
    isSpinning: function () { return spinning; },
    debug: function (on) { dbgOn = !!on; dbgText(); },
    setNet: function (s) { netInfo = String(s || '').slice(0, 60); },
    markerCount: function () { return Object.keys(markers).length; },
    selected: function () { return selectedId; },
    styleKind: function () { return kind; }
  };
  window.stsmn = api;
  map.on('click', function () { pick(null, true); });
})();
