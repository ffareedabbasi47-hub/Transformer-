(function () {
  'use strict';

  function report(m) {
    try {
      if (window.AndroidBridge && window.AndroidBridge.onError) {
        window.AndroidBridge.onError(String(m));
      }
    } catch (e) {}
  }

  window.addEventListener('error', function (e) { report('JS: ' + (e && e.message)); });
  window.addEventListener('unhandledrejection', function (e) { report('JS: ' + (e && e.reason)); });

  try {
    var c = document.createElement('canvas');
    if (!(c.getContext('webgl2') || c.getContext('webgl'))) {
      report('WebGL not supported on this phone');
      return;
    }
  } catch (e) {
    report('WebGL check failed');
    return;
  }

  // ---- constants -------------------------------------------------------------------------
  var COLORS = {
    SAFE: '#2ECC71',
    WARNING: '#FFD21F',
    EMERGENCY: '#E5484D',
    STALE: '#8A94A6',
    OFFLINE: '#8A94A6'
  };
  var BG_COLORS = {
    SAFE: 'rgba(46, 204, 113, 0.22)',
    WARNING: 'rgba(255, 210, 31, 0.22)',
    EMERGENCY: 'rgba(229, 72, 77, 0.28)',
    STALE: 'rgba(138, 148, 166, 0.2)',
    OFFLINE: 'rgba(138, 148, 166, 0.2)'
  };
  var ID_RE = /^[A-Z0-9][A-Z0-9-]{1,23}$/;
  var HOME = { center: [80.9, 26.85], zoom: 3.5 }; // Uttar Pradesh, India

  var SKY = {
    'sky-color': '#030712',
    'horizon-color': '#0e2447',
    'fog-color': '#040b17',
    'sky-horizon-blend': 0.65,
    'horizon-fog-blend': 0.5,
    'fog-ground-blend': 0.3,
    'atmosphere-blend': ['interpolate', ['linear'], ['zoom'], 0, 1, 6, 0.8, 8, 0]
  };

  function buildDarkEarthStyle() {
    var landData = (window.STSMN_LAND && window.STSMN_LAND.features)
      ? window.STSMN_LAND
      : { type: 'FeatureCollection', features: [] };
    var graticulesData = (window.STSMN_GRATICULES && window.STSMN_GRATICULES.features)
      ? window.STSMN_GRATICULES
      : { type: 'FeatureCollection', features: [] };
    var gridData = (window.STSMN_GRID && window.STSMN_GRID.features)
      ? window.STSMN_GRID
      : { type: 'FeatureCollection', features: [] };
    var radarData = (window.STSMN_RADAR && window.STSMN_RADAR.features)
      ? window.STSMN_RADAR
      : { type: 'FeatureCollection', features: [] };

    return {
      version: 8,
      sky: SKY,
      sources: {
        land: { type: 'geojson', data: landData },
        graticules: { type: 'geojson', data: graticulesData },
        grid: { type: 'geojson', data: gridData },
        radar: { type: 'geojson', data: radarData },
        sat: {
          type: 'raster', tileSize: 256, maxzoom: 19,
          tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
          attribution: 'Imagery © Esri, Maxar'
        },
        street: {
          type: 'raster', tileSize: 256, maxzoom: 19,
          tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
          attribution: '© OpenStreetMap contributors'
        },
        relief: {
          type: 'raster-dem', tileSize: 256, maxzoom: 14, encoding: 'terrarium',
          tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'],
          attribution: 'Terrain: Mapzen/AWS'
        }
      },
      layers: [
        // 1. Deep space ocean planetary base
        { id: 'ocean-base', type: 'background', paint: { 'background-color': '#060d1a' } },

        // 2. Graticules: Coordinate lines (parallels & meridians)
        {
          id: 'graticules-parallels', type: 'line', source: 'graticules',
          filter: ['==', 'kind', 'parallel'],
          paint: { 'line-color': '#0e1f3a', 'line-width': 0.7, 'line-opacity': 0.65 }
        },
        {
          id: 'graticules-meridians', type: 'line', source: 'graticules',
          filter: ['==', 'kind', 'meridian'],
          paint: { 'line-color': '#0e1f3a', 'line-width': 0.7, 'line-opacity': 0.65 }
        },
        {
          id: 'graticules-equator', type: 'line', source: 'graticules',
          filter: ['==', 'kind', 'equator'],
          paint: { 'line-color': '#1d4ed8', 'line-width': 1.6, 'line-dasharray': [3, 2], 'line-opacity': 0.85 }
        },
        {
          id: 'graticules-tropics', type: 'line', source: 'graticules',
          filter: ['==', 'kind', 'tropic'],
          paint: { 'line-color': '#1e3a8a', 'line-width': 1.0, 'line-dasharray': [2, 2], 'line-opacity': 0.6 }
        },

        // 3. Continents: dark tech slate with glowing cyan coastlines
        {
          id: 'land-fill', type: 'fill', source: 'land',
          filter: ['!=', 'type', 'sector'],
          paint: { 'fill-color': '#0b162c', 'fill-opacity': 0.96 }
        },
        {
          id: 'land-glow', type: 'line', source: 'land',
          filter: ['!=', 'type', 'sector'],
          paint: { 'line-color': '#1d4ed8', 'line-width': 2.2, 'line-opacity': 0.35 }
        },
        {
          id: 'land-coastline', type: 'line', source: 'land',
          filter: ['!=', 'type', 'sector'],
          paint: { 'line-color': '#38bdf8', 'line-width': 0.9, 'line-opacity': 0.85 }
        },

        // 4. Uttar Pradesh Regional Monitoring Sector & Radar Rings
        {
          id: 'radar-rings', type: 'line', source: 'radar',
          paint: { 'line-color': '#0284c7', 'line-width': 1.0, 'line-dasharray': [3, 3], 'line-opacity': 0.45 }
        },
        {
          id: 'up-sector-fill', type: 'fill', source: 'land',
          filter: ['==', 'type', 'sector'],
          paint: { 'fill-color': '#0284c7', 'fill-opacity': 0.16 }
        },
        {
          id: 'up-sector-line', type: 'line', source: 'land',
          filter: ['==', 'type', 'sector'],
          paint: { 'line-color': '#38bdf8', 'line-width': 1.8, 'line-opacity': 0.95 }
        },

        // 5. Regional Power Grid Transmission Lines
        {
          id: 'grid-lines', type: 'line', source: 'grid',
          paint: { 'line-color': '#00f0ff', 'line-width': 1.2, 'line-opacity': 0.7, 'line-dasharray': [4, 2] }
        },

        // 6. Optional Remote Map Imagery Overlays (Off by default in standalone mode)
        {
          id: 'sat-layer', type: 'raster', source: 'sat',
          layout: { visibility: 'none' },
          paint: { 'raster-opacity': 0.92 }
        },
        {
          id: 'street-layer', type: 'raster', source: 'street',
          layout: { visibility: 'none' },
          paint: { 'raster-opacity': 0.88 }
        }
      ]
    };
  }

  // ---- Map initialization (Standalone Dark Earth Default) --------------------------------
  var currentMode = 'dark'; // 'dark' (standalone offline), 'sat' (satellite), 'street' (streets)
  var map = new maplibregl.Map({
    container: 'map',
    style: buildDarkEarthStyle(),
    center: [78, 24],
    zoom: 1.2,
    minZoom: -1,
    pitch: 0,
    bearing: 0,
    maxPitch: 75,
    attributionControl: { compact: true },
    pixelRatio: Math.min(window.devicePixelRatio || 1, 2),
    canvasContextAttributes: { antialias: true }
  });
  map.addControl(new maplibregl.NavigationControl({ visualizePitch: true, showCompass: true }), 'top-right');

  map.on('style.load', function () {
    try {
      map.setProjection({ type: 'globe' });
    } catch (e) {
      report('Globe projection unavailable: ' + e.message);
    }
  });

  // Preserve WebGL on background/resume
  var cv = map.getCanvas();
  cv.addEventListener('webglcontextlost', function (e) { e.preventDefault(); }, false);
  cv.addEventListener('webglcontextrestored', function () {
    try { map.resize(); map.triggerRepaint(); } catch (e) {}
  }, false);

  // Status and tile error handling
  var tileErrors = 0, tileOk = 0;
  map.on('data', function (ev) {
    if (ev && ev.dataType === 'source' && ev.tile) tileOk++;
  });
  map.on('error', function (ev) {
    if (ev && ev.sourceId) {
      tileErrors++;
      // Remote tiles failed, but offline dark earth stays 100% active!
      if (tileErrors === 6 && window.AndroidBridge && window.AndroidBridge.onWarn) {
        window.AndroidBridge.onWarn('remote_offline');
      }
    }
  });

  setInterval(function () {
    var d = document.getElementById('dbg');
    if (!d) return;
    var z = map.getZoom().toFixed(1);
    if (currentMode === 'dark') {
      d.textContent = '3D EARTH · OFFLINE STANDALONE · z ' + z;
    } else if (currentMode === 'sat') {
      d.textContent = 'SATELLITE · ' + (tileErrors > 4 ? 'Offline (Dark Earth base)' : 'Tiles ok ' + tileOk) + ' · z ' + z;
    } else {
      d.textContent = 'STREETS · ' + (tileErrors > 4 ? 'Offline (Dark Earth base)' : 'Tiles ok ' + tileOk) + ' · z ' + z;
    }
  }, 1000);

  // Smooth planetary auto-spin
  var spinning = true;
  function stopSpin() { spinning = false; }
  ['mousedown', 'touchstart', 'wheel'].forEach(function (e) { map.on(e, stopSpin); });
  function spinStep() {
    if (!spinning || map.getZoom() > 3.5) return;
    var c = map.getCenter();
    map.easeTo({ center: [c.lng + 20, Math.max(-35, Math.min(35, c.lat))], duration: 2400, easing: function (t) { return t; } });
  }
  map.on('moveend', spinStep);

  // Optional 3D terrain relief when tilted and zoomed in
  var terrainOn = false;
  function syncTerrain() {
    try {
      if (!map.getSource('relief')) return;
      if (terrainOn && currentMode !== 'dark' && map.getZoom() >= 9) {
        if (!map.getTerrain()) map.setTerrain({ source: 'relief', exaggeration: 1.35 });
      } else if (map.getTerrain()) {
        map.setTerrain(null);
      }
    } catch (e) {}
  }
  map.on('zoomend', syncTerrain);

  var announced = false;
  map.on('load', function () {
    setTimeout(spinStep, 400);
    if (!announced) {
      announced = true;
      if (window.AndroidBridge && window.AndroidBridge.onReady) {
        window.AndroidBridge.onReady();
      }
    }
  });

  // ---- Markers & Live Telemetry HUD ------------------------------------------------------
  var markers = {}; // id -> { marker, el, parts, sig }
  var selectedId = null;

  function buildMarker(d) {
    var el = document.createElement('div'); el.className = 'mk';

    var card = document.createElement('div'); card.className = 'card';
    var topRow = document.createElement('div'); topRow.className = 'top-row';
    var idEl = document.createElement('div'); idEl.className = 'id';
    var stEl = document.createElement('div'); stEl.className = 'st';
    topRow.appendChild(idEl); topRow.appendChild(stEl);

    var telemEl = document.createElement('div'); telemEl.className = 'telem';
    var critEl = document.createElement('div'); critEl.className = 'crit';
    var demoEl = document.createElement('div'); demoEl.className = 'n';

    card.appendChild(topRow);
    card.appendChild(telemEl);
    card.appendChild(critEl);
    card.appendChild(demoEl);

    var stem = document.createElement('div'); stem.className = 'stem';

    var beacon = document.createElement('div'); beacon.className = 'beacon';
    var core = document.createElement('div'); core.className = 'beacon-core';
    var ring = document.createElement('div'); ring.className = 'beacon-ring';
    beacon.appendChild(ring);
    beacon.appendChild(core);

    el.appendChild(card);
    el.appendChild(stem);
    el.appendChild(beacon);

    el.addEventListener('click', function (ev) {
      ev.stopPropagation();
      pick(d.id, true);
    });

    return {
      el: el, card: card, idEl: idEl, stEl: stEl,
      telemEl: telemEl, critEl: critEl, demoEl: demoEl,
      core: core, ring: ring
    };
  }

  function paintMarker(m, d) {
    var c = COLORS[d.state] || COLORS.OFFLINE;
    var bg = BG_COLORS[d.state] || BG_COLORS.OFFLINE;

    // Header: ID and Status badge
    m.parts.idEl.textContent = 'STSMN-' + d.id.replace(/^STSMN-/, '');
    m.parts.stEl.textContent = d.state;
    m.parts.stEl.style.color = c;
    m.parts.stEl.style.backgroundColor = bg;

    // Telemetry strip
    var snippets = [];
    if (d.volt != null) snippets.push(d.volt.toFixed(1) + 'V');
    if (d.amp != null) snippets.push(d.amp.toFixed(1) + 'A');
    if (d.temp != null) snippets.push(d.temp.toFixed(0) + '°C');
    if (d.risk != null) snippets.push('Risk ' + d.risk);

    m.parts.telemEl.textContent = snippets.length > 0 ? snippets.join(' · ') : (d.isLive ? 'LIVE' : 'STANDBY');
    m.parts.telemEl.style.display = snippets.length > 0 || d.isLive ? 'block' : 'none';

    // Critical Alerts
    var critMsg = '';
    if (d.flame) critMsg = '🔥 FLAME DETECTED';
    else if (d.smoke) critMsg = '💨 SMOKE DETECTED';
    else if (d.relay === false) critMsg = '⛔ RELAY CUTOFF';
    m.parts.critEl.textContent = critMsg;
    m.parts.critEl.style.display = critMsg ? 'block' : 'none';

    // Demo location tag
    m.parts.demoEl.textContent = d.demo ? 'DEMO SITE' : '';
    m.parts.demoEl.style.display = d.demo ? 'block' : 'none';

    // Beacon colors & pulse
    m.parts.core.style.backgroundColor = c;
    m.parts.core.style.color = c;
    m.parts.ring.style.color = c;

    var isEmergency = d.state === 'EMERGENCY' || !!d.flame || !!d.smoke;
    m.el.classList.toggle('pulse', isEmergency);
    m.el.classList.toggle('sel', d.id === selectedId);
  }

  function pick(id, notify) {
    selectedId = id;
    Object.keys(markers).forEach(function (k) {
      markers[k].el.classList.toggle('sel', k === id);
    });
    if (notify && window.AndroidBridge && window.AndroidBridge.onSelect) {
      window.AndroidBridge.onSelect(id);
    }
  }

  function valid(d) {
    return d && typeof d.id === 'string' && ID_RE.test(d.id) &&
      typeof d.lat === 'number' && typeof d.lng === 'number' &&
      isFinite(d.lat) && isFinite(d.lng) &&
      Math.abs(d.lat) <= 90 && Math.abs(d.lng) <= 180 &&
      COLORS[d.state];
  }

  // ---- Public API for Android WebView ----------------------------------------------------
  var api = {
    setDevices: function (list) {
      if (!Array.isArray(list)) return;
      var seen = {};
      list.slice(0, 500).forEach(function (d) {
        if (!valid(d)) return;
        seen[d.id] = true;
        var m = markers[d.id];
        if (!m) {
          var parts = buildMarker(d);
          m = markers[d.id] = {
            parts: parts,
            el: parts.el,
            marker: new maplibregl.Marker({ element: parts.el, anchor: 'bottom' })
              .setLngLat([d.lng, d.lat])
              .addTo(map),
            sig: ''
          };
        }
        var sig = [d.lat, d.lng, d.state, !!d.demo, d.risk, d.volt, d.amp, d.temp, d.flame, d.smoke, d.relay].join('|');
        if (m.sig !== sig) {
          m.marker.setLngLat([d.lng, d.lat]);
          paintMarker(m, d);
          m.sig = sig;
        }
      });
      Object.keys(markers).forEach(function (id) {
        if (!seen[id]) {
          markers[id].marker.remove();
          delete markers[id];
        }
      });
    },

    select: function (id) {
      if (id === null || ID_RE.test(String(id))) pick(id, false);
    },

    focus: function (id) {
      var m = markers[id];
      if (!m) return;
      stopSpin();
      pick(id, false);
      map.flyTo({
        center: m.marker.getLngLat(),
        zoom: 11,
        pitch: 45,
        bearing: 0,
        speed: 0.9,
        curve: 1.5,
        essential: true
      });
    },

    fitAll: function () {
      stopSpin();
      var ids = Object.keys(markers);
      if (!ids.length) {
        map.flyTo({ center: HOME.center, zoom: HOME.zoom, pitch: 0, bearing: 0, essential: true });
        return;
      }
      if (ids.length === 1) {
        api.focus(ids[0]);
        return;
      }
      var b = new maplibregl.LngLatBounds();
      ids.forEach(function (id) { b.extend(markers[id].marker.getLngLat()); });
      map.fitBounds(b, { padding: 90, maxZoom: 12, pitch: 35, duration: 1800 });
    },

    setStyle: function (k) {
      if (k !== 'dark' && k !== 'sat' && k !== 'street') return;
      currentMode = k;
      try {
        if (map.getLayer('sat-layer')) {
          map.setLayoutProperty('sat-layer', 'visibility', k === 'sat' ? 'visible' : 'none');
        }
        if (map.getLayer('street-layer')) {
          map.setLayoutProperty('street-layer', 'visibility', k === 'street' ? 'visible' : 'none');
        }
        syncTerrain();
      } catch (e) {}
    },

    tilt: function (on) {
      terrainOn = !!on;
      map.easeTo({ pitch: on ? 60 : 0, duration: 750 });
      setTimeout(syncTerrain, 850);
    },

    spin: function (on) {
      spinning = !!on;
      if (on) spinStep();
    },

    ready: function () {
      return map.loaded();
    }
  };

  window.stsmn = api;
  map.on('click', function () { pick(null, true); });
})();
