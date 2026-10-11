(function () {
  'use strict';
  function report(m) { try { if (window.AndroidBridge && window.AndroidBridge.onError) window.AndroidBridge.onError(String(m)); } catch (e) {} }
  window.addEventListener('error', function (e) { report('JS: ' + (e && e.message)); });
  window.addEventListener('unhandledrejection', function (e) { report('JS: ' + (e && e.reason)); });
  try {
    var c = document.createElement('canvas');
    if (!(c.getContext('webgl2') || c.getContext('webgl'))) { report('WebGL not supported on this phone'); return; }
  } catch (e) { report('WebGL check failed'); return; }
  // ---- constants -------------------------------------------------------------------------
  var COLORS = { SAFE: '#2ECC71', WARNING: '#FFD21F', EMERGENCY: '#E5484D', STALE: '#8A94A6', OFFLINE: '#8A94A6' };
  var ID_RE = /^[A-Z0-9][A-Z0-9-]{1,23}$/;
  var HOME = { center: [80.9, 26.85], zoom: 3.2 }; // Uttar Pradesh, India

  var SKY = {
    'sky-color': '#0a1a3a', 'horizon-color': '#2a6fd6', 'fog-color': '#0b1b3a',
    'sky-horizon-blend': 0.5, 'horizon-fog-blend': 0.5, 'fog-ground-blend': 0.2,
    'atmosphere-blend': ['interpolate', ['linear'], ['zoom'], 0, 1, 5, 1, 7, 0]
  };
  function style(kind) {
    var sat = kind !== 'street';
    var s = {
      version: 8,
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
    return s;
  }

  // ---- map -------------------------------------------------------------------------------
  var kind = 'sat';
  var map = new maplibregl.Map({
    container: 'map', style: style(kind), center: [70, 22], zoom: 0.9, minZoom: -1, pitch: 0, bearing: 0,
    maxPitch: 75, attributionControl: { compact: true }, pixelRatio: Math.min(window.devicePixelRatio || 1, 2), canvasContextAttributes: { antialias: false }
  });
  map.addControl(new maplibregl.NavigationControl({ visualizePitch: true, showCompass: true }), 'top-right');

  map.on('style.load', function () {
    try { map.setProjection({ type: 'globe' }); } catch (e) { report('Globe projection unavailable: ' + e.message); }
  });

  var spinning = true, userTouched = false, fitted = false;
  function stopSpin() { spinning = false; }
  ['mousedown', 'touchstart', 'wheel'].forEach(function (e) { map.on(e, stopSpin); });
  function spinStep() {
    if (!spinning || map.getZoom() > 3.5) return;
    var c = map.getCenter();
    map.easeTo({ center: [c.lng + 25, Math.max(-40, Math.min(40, c.lat))], duration: 2000, easing: function (t) { return t; } });
  }
  map.on('moveend', spinStep);

  // Real 3D relief only when zoomed in (globe projection switches to flat map there). Errors never break the map.
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
  var announced = false;
  map.on('load', function () {
    setTimeout(spinStep, 300);
    if (!announced) { announced = true; if (window.AndroidBridge && window.AndroidBridge.onReady) window.AndroidBridge.onReady(); }
  });

  // ---- markers (DOM built with textContent only: server text can never become HTML) ----------
  var markers = {}; // id -> {marker, el, sig, d}
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
    if (notify && window.AndroidBridge && window.AndroidBridge.onSelect) window.AndroidBridge.onSelect(id);
  }

  function valid(d) {
    return d && typeof d.id === 'string' && ID_RE.test(d.id) && typeof d.lat === 'number' && typeof d.lng === 'number' &&
      isFinite(d.lat) && isFinite(d.lng) && Math.abs(d.lat) <= 90 && Math.abs(d.lng) <= 180 && COLORS[d.state];
  }

  var api = {
    /** devices: [{id, lat, lng, state, demo}] - only transformers with a position are drawn. */
    setDevices: function (list) {
      if (!Array.isArray(list)) return;
      var seen = {};
      list.slice(0, 500).forEach(function (d) {
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
      Object.keys(markers).forEach(function (id) { if (!seen[id]) { markers[id].marker.remove(); delete markers[id]; } });
      if (!fitted && Object.keys(markers).length) { fitted = true; api.fitAll(); }
    },
    select: function (id) { if (id === null || ID_RE.test(String(id))) pick(id, false); },
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
      var b = new maplibregl.LngLatBounds();
      ids.forEach(function (id) { b.extend(markers[id].marker.getLngLat()); });
      map.fitBounds(b, { padding: 90, maxZoom: 12, pitch: 35, duration: 1800 });
    },
    setStyle: function (k) {
      if (k !== 'sat' && k !== 'street') return;
      kind = k; map.setStyle(style(k));
    },
    tilt: function (on) { terrainOn = !!on; map.easeTo({ pitch: on ? 60 : 0, duration: 700 }); setTimeout(syncTerrain, 800); },
    spin: function (on) { spinning = !!on; if (on) spinStep(); },
    ready: function () { return map.loaded(); }
  };
  window.stsmn = api;
  map.on('click', function () { pick(null, true); });
})();
