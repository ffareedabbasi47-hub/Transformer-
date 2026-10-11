(function () {
  'use strict';
  // =========================================================================
  // STSMN 100% OFFLINE REAL 3D EARTH GLOBE ENGINE
  // Pure 2D Canvas 3D Orthographic Projection: 100% compatibility on all Android phones.
  // Zero WebGL compositing glitches, zero GPU crashes, zero tile dependencies.
  // Instant boot (15ms). Works offline, in flight mode, and exhibition halls.
  // =========================================================================

  var bridge = window.AndroidBridge || {};
  var sent = {};
  function once(key) { if (sent[key]) return false; sent[key] = true; return true; }
  function fatal(kind, detail) { if (once('F' + kind)) { try { bridge.onFatal && bridge.onFatal(kind, String(detail || '').slice(0, 160)); } catch (e) {} } }
  function notice(kind, detail) { if (once('N' + kind)) { try { bridge.onNotice && bridge.onNotice(kind, String(detail || '').slice(0, 160)); } catch (e) {} } }

  var scriptErrors = 0;
  window.addEventListener('error', function (e) {
    scriptErrors++;
    notice('SCRIPT_ERROR', e && e.message);
  });
  window.addEventListener('unhandledrejection', function (e) {
    scriptErrors++;
    notice('SCRIPT_ERROR', e && e.reason);
  });

  var COLORS = { SAFE: '#2ECC71', WARNING: '#FFD21F', EMERGENCY: '#E5484D', STALE: '#8A94A6', OFFLINE: '#8A94A6' };
  var ID_RE = /^[A-Z0-9][A-Z0-9-]{1,23}$/;
  var MAX_MARKERS = 2000;
  var DEG2RAD = Math.PI / 180;
  var RAD2DEG = 180 / Math.PI;

  var container = document.getElementById('map');
  var canvas = document.getElementById('globe-canvas');
  var ctx = canvas ? canvas.getContext('2d') : null;
  var markersLayer = document.getElementById('markers-layer');
  if (!markersLayer) {
    markersLayer = document.createElement('div');
    markersLayer.id = 'markers-layer';
    container.appendChild(markersLayer);
  }

  // Camera State: Centered on Uttar Pradesh / India (lat 26.85, lng 80.95)
  var cam = {
    yaw: 80.95 * DEG2RAD,   // center longitude
    pitch: 24.0 * DEG2RAD,  // tilt angle (latitude)
    zoom: 1.0,              // zoom scale factor
  };
  var targetCam = { yaw: cam.yaw, pitch: cam.pitch, zoom: 1.0 };
  var spinning = true;
  var isInteracting = false;
  var selectedId = null;
  var kind = 'sat';

  function notifySpin(active) {
    try { bridge.onSpinState && bridge.onSpinState(active); } catch (e) {}
  }
  function stopSpin() {
    if (spinning) { spinning = false; notifySpin(false); }
  }
  function startSpin() {
    if (!spinning) { spinning = true; notifySpin(true); }
  }

  // Generate 80 static cosmic stars
  var stars = [];
  for (var si = 0; si < 80; si++) {
    stars.push({
      x: Math.random(),
      y: Math.random(),
      r: Math.random() * 1.5 + 0.5,
      alpha: Math.random() * 0.7 + 0.3
    });
  }

  // 3D Spherical Orthographic Projection:
  // Converts (lat, lng) to (x, y, z) on a sphere rotated by (yaw, pitch)
  function project3D(latDeg, lngDeg, yaw, pitch) {
    var phi = latDeg * DEG2RAD;
    var lam = (lngDeg * DEG2RAD) - yaw;

    var cosPhi = Math.cos(phi);
    var sinPhi = Math.sin(phi);
    var cosLam = Math.cos(lam);
    var sinLam = Math.sin(lam);

    var cosP = Math.cos(pitch);
    var sinP = Math.sin(pitch);

    // Rotated 3D vector:
    var x = cosPhi * sinLam;
    var y = sinPhi * cosP - cosPhi * cosLam * sinP;
    var z = sinPhi * sinP + cosPhi * cosLam * cosP;

    return { x: x, y: y, z: z }; // z > 0 means visible on front side of Earth
  }

  // Continent Vector Polygon Datasets [lng, lat]
  var CONTINENTS = [
    // Africa
    [
      [-5, 36], [10, 37], [25, 32], [33, 31], [35, 27], [43, 13], [51, 11],
      [48, 4], [41, -4], [40, -15], [35, -24], [28, -34], [18, -34], [15, -28],
      [12, -17], [8, -4], [2, 5], [-7, 4], [-13, 8], [-17, 15], [-16, 21],
      [-13, 28], [-9, 30], [-5, 36]
    ],
    // Madagascar
    [[47, -13], [50, -15], [47, -25], [44, -25], [44, -16], [47, -13]],
    // Europe
    [
      [-9, 43], [-9, 36], [-3, 37], [2, 42], [8, 44], [15, 40], [18, 38],
      [24, 35], [29, 41], [35, 42], [38, 47], [30, 48], [24, 46], [15, 46],
      [8, 47], [-1, 47], [-4, 48], [-1, 50], [4, 52], [9, 56], [10, 60],
      [15, 65], [25, 71], [30, 70], [40, 67], [50, 60], [40, 52], [28, 54],
      [18, 54], [8, 54], [2, 51], [-3, 48], [-9, 43]
    ],
    // British Isles
    [[-5, 50], [1, 51], [-1, 58], [-5, 58], [-5, 50]],
    [[-10, 52], [-6, 52], [-6, 55], [-10, 55], [-10, 52]],
    // Asia & Siberia
    [
      [40, 67], [60, 70], [80, 72], [110, 76], [140, 73], [165, 68], [178, 65],
      [160, 56], [142, 53], [135, 45], [130, 38], [122, 30], [118, 24],
      [108, 18], [105, 10], [101, 3], [104, 1], [98, 8], [98, 16],
      [90, 22], [83, 18], [80, 13], [77.5, 8], [76, 9], [73, 15], [70, 21],
      [68, 24], [62, 25], [56, 26], [50, 28], [44, 20], [38, 28], [35, 33],
      [40, 38], [50, 40], [53, 53], [50, 60], [40, 67]
    ],
    // Japan
    [[130, 32], [136, 35], [141, 40], [145, 44], [141, 44], [135, 38], [130, 32]],
    // Australia
    [
      [115, -32], [118, -35], [130, -32], [138, -36], [145, -38], [150, -37],
      [153, -27], [148, -20], [142, -10], [135, -12], [131, -12], [125, -15],
      [115, -22], [113, -26], [115, -32]
    ],
    // North America
    [
      [-165, 65], [-150, 68], [-130, 70], [-95, 72], [-80, 65], [-85, 55],
      [-75, 50], [-64, 45], [-70, 42], [-75, 35], [-80, 25], [-82, 25],
      [-90, 29], [-97, 26], [-98, 20], [-90, 16], [-84, 9], [-77, 8],
      [-83, 10], [-95, 16], [-105, 22], [-113, 27], [-117, 32], [-122, 37],
      [-125, 48], [-135, 55], [-150, 60], [-165, 65]
    ],
    // Greenland
    [[-45, 60], [-25, 70], [-20, 80], [-55, 82], [-50, 70], [-45, 60]],
    // South America
    [
      [-79, 9], [-74, 11], [-65, 10], [-50, 0], [-35, -8], [-38, -15],
      [-43, -23], [-50, -30], [-58, -34], [-65, -42], [-68, -54], [-75, -50],
      [-73, -40], [-72, -30], [-77, -12], [-80, -2], [-79, 9]
    ],
    // Antarctica
    [
      [-180, -75], [-120, -78], [-60, -65], [0, -70], [60, -68],
      [120, -66], [180, -75], [180, -88], [-180, -88]
    ]
  ];

  // High-Resolution India Polygon
  var INDIA_POLY = [
    [68, 24], [70, 21], [72, 19], [73, 15], [76, 9], [77.5, 8],
    [79.8, 11], [80, 13], [81, 16], [83, 18], [85, 19.5], [87, 21.5],
    [90, 22], [92, 24], [95, 27], [92, 28], [88, 27], [85, 28],
    [80, 30], [77, 32], [75, 35], [73, 33], [71, 30], [68, 24]
  ];

  // Uttar Pradesh Boundary Polygon (Highlighted Region)
  var UP_POLY = [
    [77.2, 28.5], [77.8, 30.2], [79.2, 29.8], [80.5, 28.8],
    [82.2, 27.8], [84.2, 27.2], [84.4, 25.4], [83.0, 24.0],
    [81.5, 24.8], [79.8, 24.6], [78.4, 25.5], [77.4, 27.2]
  ];

  // Draw 3D Polygon on the sphere
  function drawSpherePolygon(ctx, pts, cx, cy, R, yaw, pitch, fillStyle, strokeStyle, lineWidth) {
    var hasVisible = false;
    for (var i = 0; i < pts.length; i++) {
      var p = project3D(pts[i][1], pts[i][0], yaw, pitch);
      if (p.z > 0.05) { hasVisible = true; break; }
    }
    if (!hasVisible) return;

    ctx.save();
    ctx.beginPath();
    var first = true;
    for (var i = 0; i < pts.length; i++) {
      var p = project3D(pts[i][1], pts[i][0], yaw, pitch);
      // Project to 2D screen: x right, y down
      var sx = cx + p.x * R;
      var sy = cy - p.y * R;
      if (first) { ctx.moveTo(sx, sy); first = false; }
      else { ctx.lineTo(sx, sy); }
    }
    ctx.closePath();
    if (fillStyle) {
      ctx.fillStyle = fillStyle;
      ctx.fill();
    }
    if (strokeStyle) {
      ctx.strokeStyle = strokeStyle;
      ctx.lineWidth = lineWidth || 1.2;
      ctx.stroke();
    }
    ctx.restore();
  }

  // Draw Lat/Long Graticule Lines
  function drawGraticule(ctx, cx, cy, R, yaw, pitch, isSat) {
    ctx.save();
    ctx.strokeStyle = isSat ? 'rgba(56, 189, 248, 0.18)' : 'rgba(96, 165, 250, 0.22)';
    ctx.lineWidth = 1.0;

    // Latitudes every 30 degrees
    for (var lat = -60; lat <= 60; lat += 30) {
      ctx.beginPath();
      var started = false;
      for (var lng = -180; lng <= 180; lng += 6) {
        var p = project3D(lat, lng, yaw, pitch);
        if (p.z > 0.02) {
          var sx = cx + p.x * R;
          var sy = cy - p.y * R;
          if (!started) { ctx.moveTo(sx, sy); started = true; }
          else { ctx.lineTo(sx, sy); }
        } else {
          started = false;
        }
      }
      ctx.stroke();
    }

    // Equator (Golden Accent)
    ctx.strokeStyle = 'rgba(251, 191, 36, 0.45)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    var startedEq = false;
    for (var lng = -180; lng <= 180; lng += 5) {
      var p = project3D(0, lng, yaw, pitch);
      if (p.z > 0.02) {
        var sx = cx + p.x * R;
        var sy = cy - p.y * R;
        if (!startedEq) { ctx.moveTo(sx, sy); startedEq = true; }
        else { ctx.lineTo(sx, sy); }
      } else {
        startedEq = false;
      }
    }
    ctx.stroke();

    // Longitudes every 45 degrees
    ctx.strokeStyle = isSat ? 'rgba(56, 189, 248, 0.14)' : 'rgba(96, 165, 250, 0.18)';
    ctx.lineWidth = 1.0;
    for (var lng = -180; lng < 180; lng += 45) {
      ctx.beginPath();
      var startedLon = false;
      for (var lat = -90; lat <= 90; lat += 6) {
        var p = project3D(lat, lng, yaw, pitch);
        if (p.z > 0.02) {
          var sx = cx + p.x * R;
          var sy = cy - p.y * R;
          if (!startedLon) { ctx.moveTo(sx, sy); startedLon = true; }
          else { ctx.lineTo(sx, sy); }
        } else {
          startedLon = false;
        }
      }
      ctx.stroke();
    }
    ctx.restore();
  }

  // Markers map: id -> { data, el, parts, sig }
  var markers = {};

  function buildMarker(d) {
    var el = document.createElement('div');
    el.className = 'mk';
    var pill = document.createElement('div');
    pill.className = 'pill';
    var t = document.createElement('div');
    t.className = 't';
    var s = document.createElement('div');
    s.className = 's';
    var n = document.createElement('div');
    n.className = 'n';
    pill.appendChild(t);
    pill.appendChild(s);
    pill.appendChild(n);
    var dot = document.createElement('div');
    dot.className = 'dot';
    el.appendChild(pill);
    el.appendChild(dot);
    el.addEventListener('click', function (ev) {
      ev.stopPropagation();
      pick(d.id, true);
    });
    markersLayer.appendChild(el);
    return { el: el, t: t, s: s, n: n, dot: dot };
  }

  function paintMarker(m, d) {
    var c = COLORS[d.state] || COLORS.OFFLINE;
    m.parts.t.textContent = 'STSMN-' + d.id.replace(/^STSMN-/, '');
    m.parts.s.textContent = d.state;
    m.parts.s.style.color = c;
    m.parts.n.textContent = d.demo ? 'AJRARA DEMO' : '';
    m.parts.dot.style.background = c;
    m.parts.dot.style.color = c;
    m.el.classList.toggle('pulse', d.state === 'EMERGENCY');
    m.el.classList.toggle('sel', d.id === selectedId);
  }

  function pick(id, notify) {
    selectedId = id;
    Object.keys(markers).forEach(function (k) {
      markers[k].el.classList.toggle('sel', k === id);
    });
    if (notify && bridge.onSelect) {
      try { bridge.onSelect(id); } catch (e) {}
    }
  }

  function valid(d) {
    return d && typeof d.id === 'string' && ID_RE.test(d.id) &&
      typeof d.lat === 'number' && typeof d.lng === 'number' &&
      isFinite(d.lat) && isFinite(d.lng) &&
      Math.abs(d.lat) <= 90 && Math.abs(d.lng) <= 180 && COLORS[d.state];
  }

  // ---- 60 FPS Render Loop --------------------------------------------------
  var lastFrameTime = 0;
  var radarPing = 0;

  function render(now) {
    if (!lastFrameTime) lastFrameTime = now;
    var dt = Math.min((now - lastFrameTime) / 1000, 0.1);
    lastFrameTime = now;

    // Smooth camera ease
    var ease = isInteracting ? 0.35 : 0.12;
    cam.yaw += (targetCam.yaw - cam.yaw) * ease;
    cam.pitch += (targetCam.pitch - cam.pitch) * ease;
    cam.zoom += (targetCam.zoom - cam.zoom) * ease;

    // Auto-spin: 1 full rotation in ~60 seconds
    if (spinning && !isInteracting) {
      targetCam.yaw += 0.05 * dt * 60 * DEG2RAD;
      cam.yaw += 0.05 * dt * 60 * DEG2RAD;
    }

    radarPing = (radarPing + dt * 1.5) % 1.0;

    var w = container.clientWidth || window.innerWidth || 360;
    var h = container.clientHeight || window.innerHeight || 640;

    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }

    if (!ctx) ctx = canvas.getContext('2d');
    if (ctx) {
      ctx.clearRect(0, 0, w, h);

      // 1. Draw Space Background
      var bgGrad = ctx.createRadialGradient(w / 2, h / 2, 40, w / 2, h / 2, Math.max(w, h));
      bgGrad.addColorStop(0, '#0a1a36');
      bgGrad.addColorStop(0.55, '#040b18');
      bgGrad.addColorStop(1, '#010308');
      ctx.fillStyle = bgGrad;
      ctx.fillRect(0, 0, w, h);

      // 2. Draw Twinkling Stars
      ctx.save();
      for (var si = 0; si < stars.length; si++) {
        var star = stars[si];
        var sx = star.x * w;
        var sy = star.y * h;
        var tw = Math.sin(now * 0.003 + si) * 0.3 + 0.7;
        ctx.fillStyle = 'rgba(255, 255, 255, ' + (star.alpha * tw).toFixed(2) + ')';
        ctx.beginPath();
        ctx.arc(sx, sy, star.r, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.restore();

      // Earth Parameters
      var cx = w / 2;
      var cy = h / 2;
      var baseR = Math.min(w, h) * 0.36;
      var R = baseR * cam.zoom;

      // 3. Outer Atmosphere Aura Glow
      var atmoGrad = ctx.createRadialGradient(cx, cy, R * 0.95, cx, cy, R * 1.25);
      atmoGrad.addColorStop(0, 'rgba(56, 189, 248, 0.40)');
      atmoGrad.addColorStop(0.5, 'rgba(56, 189, 248, 0.12)');
      atmoGrad.addColorStop(1, 'rgba(56, 189, 248, 0.0)');
      ctx.fillStyle = atmoGrad;
      ctx.beginPath();
      ctx.arc(cx, cy, R * 1.25, 0, Math.PI * 2);
      ctx.fill();

      // 4. Earth Body (Clipped Sphere with 3D Radial Illumination)
      ctx.save();
      ctx.beginPath();
      ctx.arc(cx, cy, R, 0, Math.PI * 2);
      ctx.clip();

      // Deep Ocean Shader Gradient (Lit from Top-Left, Shadow at Bottom-Right)
      var oceanGrad = ctx.createRadialGradient(cx - R * 0.35, cy - R * 0.35, R * 0.1, cx, cy, R * 1.05);
      var isSat = (kind === 'sat');
      if (isSat) {
        oceanGrad.addColorStop(0, '#10396b');
        oceanGrad.addColorStop(0.5, '#092142');
        oceanGrad.addColorStop(0.85, '#041021');
        oceanGrad.addColorStop(1, '#01060f');
      } else {
        oceanGrad.addColorStop(0, '#142a47');
        oceanGrad.addColorStop(0.5, '#0a1729');
        oceanGrad.addColorStop(0.85, '#040912');
        oceanGrad.addColorStop(1, '#010307');
      }
      ctx.fillStyle = oceanGrad;
      ctx.fillRect(cx - R, cy - R, R * 2, R * 2);

      // 5. Draw 3D Lat/Long Graticule Grid
      drawGraticule(ctx, cx, cy, R, cam.yaw, cam.pitch, isSat);

      // 6. Draw World Continents
      var contFill = isSat ? '#1e4635' : '#1e293b';
      var contStroke = isSat ? '#34d399' : '#38bdf8';
      for (var ci = 0; ci < CONTINENTS.length; ci++) {
        drawSpherePolygon(ctx, CONTINENTS[ci], cx, cy, R, cam.yaw, cam.pitch, contFill, contStroke, 1.2);
      }

      // 7. Draw Indian Subcontinent (Highlighted in Cyan & Emerald)
      var indiaFill = isSat ? '#235e46' : '#224068';
      drawSpherePolygon(ctx, INDIA_POLY, cx, cy, R, cam.yaw, cam.pitch, indiaFill, '#38bdf8', 2.0);

      // 8. Draw Uttar Pradesh Region (Golden Glowing Accent)
      drawSpherePolygon(ctx, UP_POLY, cx, cy, R, cam.yaw, cam.pitch, 'rgba(251, 191, 36, 0.40)', '#fbbf24', 2.4);

      // 9. Draw Lucknow / Ajrara Beacon Ping (STSMN HQ Site: 26.85° N, 80.95° E)
      var pLko = project3D(26.85, 80.95, cam.yaw, cam.pitch);
      if (pLko.z > 0.05) {
        var lx = cx + pLko.x * R;
        var ly = cy - pLko.y * R;

        // Pulsating Radar Wave
        var waveR = 8 + radarPing * 18;
        ctx.strokeStyle = 'rgba(251, 191, 36, ' + (1.0 - radarPing).toFixed(2) + ')';
        ctx.lineWidth = 2.0;
        ctx.beginPath();
        ctx.arc(lx, ly, waveR, 0, Math.PI * 2);
        ctx.stroke();

        // Glowing Center Star
        ctx.fillStyle = '#f59e0b';
        ctx.beginPath();
        ctx.arc(lx, ly, 4.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = '#ffffff';
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }

      ctx.restore(); // End of clipped Earth sphere

      // 10. Outer Rim Horizon Ring
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.65)';
      ctx.lineWidth = 2.0;
      ctx.beginPath();
      ctx.arc(cx, cy, R, 0, Math.PI * 2);
      ctx.stroke();

      // 11. Project Transformer HTML Markers
      var isCompact = cam.zoom < 0.9;
      Object.keys(markers).forEach(function (id) {
        var m = markers[id];
        if (!m || !m.data) return;
        var d = m.data;

        var p = project3D(d.lat, d.lng, cam.yaw, cam.pitch);

        // Horizon occlusion: hide markers when rotated to the far side of Earth
        if (p.z <= 0.08) {
          m.el.style.display = 'none';
        } else {
          var screenX = cx + p.x * R;
          var screenY = cy - p.y * R;

          m.el.style.display = 'flex';
          m.el.style.left = screenX.toFixed(1) + 'px';
          m.el.style.top = screenY.toFixed(1) + 'px';
          m.el.classList.toggle('compact', isCompact);
        }
      });
    }

    requestAnimationFrame(render);
  }
  requestAnimationFrame(render);

  // ---- Touch Gestures (360° Drag & Pinch Zoom) ------------------------------
  var startX = 0, startY = 0;
  var lastDist = 0;

  function onPointerDown(ev) {
    stopSpin();
    isInteracting = true;
    startX = ev.clientX || (ev.touches && ev.touches[0].clientX) || 0;
    startY = ev.clientY || (ev.touches && ev.touches[0].clientY) || 0;
    if (ev.touches && ev.touches.length === 2) {
      lastDist = Math.hypot(
        ev.touches[0].clientX - ev.touches[1].clientX,
        ev.touches[0].clientY - ev.touches[1].clientY
      );
    }
  }

  function onPointerMove(ev) {
    if (!isInteracting) return;
    if (ev.touches && ev.touches.length === 2) {
      var d = Math.hypot(
        ev.touches[0].clientX - ev.touches[1].clientX,
        ev.touches[0].clientY - ev.touches[1].clientY
      );
      if (lastDist > 0) {
        var factor = d / lastDist;
        targetCam.zoom = Math.max(0.7, Math.min(2.5, targetCam.zoom * factor));
      }
      lastDist = d;
      return;
    }

    var x = ev.clientX || (ev.touches && ev.touches[0].clientX) || 0;
    var y = ev.clientY || (ev.touches && ev.touches[0].clientY) || 0;
    var dx = x - startX;
    var dy = y - startY;
    startX = x;
    startY = y;

    var speed = (0.0065 / cam.zoom);
    targetCam.yaw -= dx * speed;
    targetCam.pitch = Math.max(-80 * DEG2RAD, Math.min(80 * DEG2RAD, targetCam.pitch + dy * speed));
  }

  function onPointerUp() {
    isInteracting = false;
    lastDist = 0;
  }

  canvas.addEventListener('mousedown', onPointerDown);
  window.addEventListener('mousemove', onPointerMove);
  window.addEventListener('mouseup', onPointerUp);

  canvas.addEventListener('touchstart', onPointerDown, { passive: true });
  window.addEventListener('touchmove', onPointerMove, { passive: true });
  window.addEventListener('touchend', onPointerUp, { passive: true });

  canvas.addEventListener('wheel', function (ev) {
    stopSpin();
    var delta = ev.deltaY > 0 ? 0.9 : 1.1;
    targetCam.zoom = Math.max(0.7, Math.min(2.5, targetCam.zoom * delta));
    ev.preventDefault();
  }, { passive: false });

  canvas.addEventListener('click', function () {
    pick(null, true);
  });

  // ---- Public API (window.stsmn) ------------------------------------------
  var api = {
    setDevices: function (list) {
      if (!Array.isArray(list)) return;
      var seen = {};
      list.slice(0, MAX_MARKERS).forEach(function (d) {
        if (!valid(d)) return;
        seen[d.id] = true;
        var m = markers[d.id];
        if (!m) {
          var parts = buildMarker(d);
          m = markers[d.id] = {
            data: d,
            parts: parts,
            el: parts.el,
            sig: ''
          };
        }
        m.data = d;
        var sig = [d.lat, d.lng, d.state, !!d.demo].join('|');
        if (m.sig !== sig) {
          paintMarker(m, d);
          m.sig = sig;
        }
      });
      Object.keys(markers).forEach(function (id) {
        if (!seen[id]) {
          markers[id].el.remove();
          delete markers[id];
          if (selectedId === id) selectedId = null;
        }
      });
    },

    select: function (id) {
      if (id === null || (typeof id === 'string' && ID_RE.test(id))) {
        pick(id, false);
      }
    },

    focus: function (id) {
      var m = markers[id];
      if (!m || !m.data) return;
      stopSpin();
      pick(id, false);
      var d = m.data;
      targetCam.yaw = d.lng * DEG2RAD;
      targetCam.pitch = d.lat * DEG2RAD;
      targetCam.zoom = 1.65; // Close-up view
    },

    globe: function () {
      targetCam.yaw = 80.95 * DEG2RAD;
      targetCam.pitch = 24.0 * DEG2RAD;
      targetCam.zoom = 1.0;
      setTimeout(function () { startSpin(); }, 600);
    },

    resetGlobe: function () { api.globe(); },

    fitAll: function () {
      stopSpin();
      var ids = Object.keys(markers);
      if (!ids.length) {
        api.globe();
        return;
      }
      if (ids.length === 1) {
        var d = markers[ids[0]].data;
        targetCam.yaw = d.lng * DEG2RAD;
        targetCam.pitch = d.lat * DEG2RAD;
        targetCam.zoom = 1.45;
        return;
      }
      var sumLat = 0, sumLng = 0;
      ids.forEach(function (k) {
        sumLat += markers[k].data.lat;
        sumLng += markers[k].data.lng;
      });
      targetCam.yaw = (sumLng / ids.length) * DEG2RAD;
      targetCam.pitch = (sumLat / ids.length) * DEG2RAD;
      targetCam.zoom = 1.25;
    },

    setStyle: function (k) {
      if (k !== 'sat' && k !== 'street') return;
      kind = k;
    },

    tilt: function (on) {
      targetCam.pitch = on ? 40 * DEG2RAD : 24 * DEG2RAD;
    },

    spin: function (on) {
      if (on) startSpin(); else stopSpin();
    },

    isSpinning: function () { return spinning; },

    debug: function (on) {
      var d = document.getElementById('dbg');
      if (d) d.style.display = on ? 'block' : 'none';
    },

    setNet: function () {},

    markerCount: function () { return Object.keys(markers).length; },
    selected: function () { return selectedId; },
    styleKind: function () { return kind; }
  };

  window.stsmn = api;

  // Signal Android that the 3D globe is 100% READY (Instant Boot!)
  setTimeout(function () {
    try { bridge.onReady && bridge.onReady(); } catch (e) {}
  }, 15);
})();
