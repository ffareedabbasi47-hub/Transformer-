(function () {
  'use strict';
  // =========================================================================
  // STSMN 100% OFFLINE REAL 3D EARTH GLOBE ENGINE (DUAL ENGINE: WEBGL + CANVAS 2D)
  // Zero internet requests, zero tile dependencies.
  // Instant boot (<30ms). Works in flight mode, exhibition halls, and offline.
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
  var markersLayer = document.getElementById('markers-layer');
  if (!markersLayer) {
    markersLayer = document.createElement('div');
    markersLayer.id = 'markers-layer';
    container.appendChild(markersLayer);
  }

  // Camera & State (centered on Uttar Pradesh / India: lat 26.85, lng 80.9)
  var cam = {
    yaw: -80.9 * DEG2RAD,  // longitude rotation
    pitch: 26.85 * DEG2RAD, // latitude tilt
    dist: 2.7,             // distance from globe center
    tiltAngle: 0.0
  };
  var targetCam = { yaw: cam.yaw, pitch: cam.pitch, dist: cam.dist, tiltAngle: 0.0 };
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

  // Markers map: id -> { data, el, parts, sig, v }
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
    m.parts.n.textContent = d.demo ? 'DEMO SITE (UP)' : '';
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

  // Convert (lat, lng) to unit sphere 3D vector [x, y, z]
  // Synchronized with sphere geometry and texture mapping:
  // x = cos(lat) * sin(lng)
  // y = sin(lat)
  // z = cos(lat) * cos(lng)
  function latLngToVector(lat, lng) {
    var phi = lat * DEG2RAD;
    var lam = lng * DEG2RAD;
    return {
      x: Math.cos(phi) * Math.sin(lam),
      y: Math.sin(phi),
      z: Math.cos(phi) * Math.cos(lam)
    };
  }

  // ---- Offline World Texture Generator (1024x512) ---------------------------
  function createOfflineEarthTexture(satelliteMode) {
    var texCanvas = document.createElement('canvas');
    texCanvas.width = 1024;
    texCanvas.height = 512;
    var ctx = texCanvas.getContext('2d');
    var W = texCanvas.width;
    var H = texCanvas.height;

    function toX(lng) { return ((lng + 180) / 360) * W; }
    function toY(lat) { return ((90 - lat) / 180) * H; }

    // 1. Deep Ocean Background
    var oceanGrad = ctx.createRadialGradient(W / 2, H / 2, 40, W / 2, H / 2, W / 2);
    if (satelliteMode) {
      oceanGrad.addColorStop(0, '#092347');
      oceanGrad.addColorStop(0.5, '#061730');
      oceanGrad.addColorStop(1, '#020914');
    } else {
      oceanGrad.addColorStop(0, '#081c33');
      oceanGrad.addColorStop(0.6, '#041021');
      oceanGrad.addColorStop(1, '#01050d');
    }
    ctx.fillStyle = oceanGrad;
    ctx.fillRect(0, 0, W, H);

    // 2. Graticule Lat / Long Grid
    ctx.strokeStyle = satelliteMode ? 'rgba(56, 189, 248, 0.15)' : 'rgba(96, 165, 250, 0.18)';
    ctx.lineWidth = 1;
    for (var lat = -60; lat <= 60; lat += 30) {
      ctx.beginPath();
      ctx.moveTo(0, toY(lat));
      ctx.lineTo(W, toY(lat));
      ctx.stroke();
    }
    for (var lng = -150; lng <= 180; lng += 30) {
      ctx.beginPath();
      ctx.moveTo(toX(lng), 0);
      ctx.lineTo(toX(lng), H);
      ctx.stroke();
    }
    // Equator (Golden accent)
    ctx.strokeStyle = 'rgba(251, 191, 36, 0.40)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(0, toY(0));
    ctx.lineTo(W, toY(0));
    ctx.stroke();

    // 3. World Continents (Polygons)
    ctx.fillStyle = satelliteMode ? '#1e3d30' : '#1e293b';
    ctx.strokeStyle = satelliteMode ? '#34d399' : '#38bdf8';
    ctx.lineWidth = 1.2;

    function drawPoly(pts) {
      ctx.beginPath();
      for (var i = 0; i < pts.length; i++) {
        var x = toX(pts[i][0]);
        var y = toY(pts[i][1]);
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
    }

    // Africa
    drawPoly([
      [-5, 36], [10, 37], [25, 32], [33, 31], [35, 27], [43, 13], [51, 11],
      [48, 4], [41, -4], [40, -15], [35, -24], [28, -34], [18, -34], [15, -28],
      [12, -17], [8, -4], [2, 5], [-7, 4], [-13, 8], [-17, 15], [-16, 21],
      [-13, 28], [-9, 30], [-5, 36]
    ]);
    // Madagascar
    drawPoly([[47, -13], [50, -15], [47, -25], [44, -25], [44, -16], [47, -13]]);

    // Europe & Mediterranean
    drawPoly([
      [-9, 43], [-9, 36], [-3, 37], [2, 42], [8, 44], [15, 40], [18, 38],
      [24, 35], [29, 41], [35, 42], [38, 47], [30, 48], [24, 46], [15, 46],
      [8, 47], [-1, 47], [-4, 48], [-1, 50], [4, 52], [9, 56], [10, 60],
      [15, 65], [25, 71], [30, 70], [40, 67], [50, 60], [40, 52], [28, 54],
      [18, 54], [8, 54], [2, 51], [-3, 48], [-9, 43]
    ]);
    // British Isles
    drawPoly([[-5, 50], [1, 51], [-1, 58], [-5, 58], [-5, 50]]);
    drawPoly([[-10, 52], [-6, 52], [-6, 55], [-10, 55], [-10, 52]]);

    // Asia & Russia / Siberia
    drawPoly([
      [40, 67], [60, 70], [80, 72], [110, 76], [140, 73], [165, 68], [178, 65],
      [160, 56], [142, 53], [135, 45], [130, 38], [122, 30], [118, 24],
      [108, 18], [105, 10], [101, 3], [104, 1], [98, 8], [98, 16],
      [90, 22], [83, 18], [80, 13], [77.5, 8], [76, 9], [73, 15], [70, 21],
      [68, 24], [62, 25], [56, 26], [50, 28], [44, 20], [38, 28], [35, 33],
      [40, 38], [50, 40], [53, 53], [50, 60], [40, 67]
    ]);
    // Japan
    drawPoly([[130, 32], [136, 35], [141, 40], [145, 44], [141, 44], [135, 38], [130, 32]]);

    // ---- INDIA (PROMINENT & HIGHLIGHTED) ----
    ctx.save();
    ctx.fillStyle = satelliteMode ? '#1e523f' : '#1e3a5f';
    ctx.strokeStyle = '#38bdf8';
    ctx.lineWidth = 2.0;
    drawPoly([
      [68, 24], [70, 21], [72, 19], [73, 15], [76, 9], [77.5, 8],
      [79.8, 11], [80, 13], [81, 16], [83, 18], [85, 19.5], [87, 21.5],
      [90, 22], [92, 24], [95, 27], [92, 28], [88, 27], [85, 28],
      [80, 30], [77, 32], [75, 35], [73, 33], [71, 30], [68, 24]
    ]);
    // Sri Lanka
    drawPoly([[80, 9], [81.5, 8], [81.5, 6.5], [80, 7], [80, 9]]);

    // ---- UTTAR PRADESH REGION & BEACON ----
    ctx.strokeStyle = '#fbbf24';
    ctx.lineWidth = 2.4;
    ctx.fillStyle = 'rgba(251, 191, 36, 0.35)';
    drawPoly([
      [77.2, 28.5], [77.8, 30.2], [79.2, 29.8], [80.5, 28.8],
      [82.2, 27.8], [84.2, 27.2], [84.4, 25.4], [83.0, 24.0],
      [81.5, 24.8], [79.8, 24.6], [78.4, 25.5], [77.4, 27.2]
    ]);

    // Golden beacon star at Lucknow / Ajrara (STSMN Demo Site)
    var lkoX = toX(80.95);
    var lkoY = toY(26.85);
    ctx.fillStyle = '#f59e0b';
    ctx.beginPath();
    ctx.arc(lkoX, lkoY, 4.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 1.2;
    ctx.stroke();
    ctx.restore();

    // Australia & NZ
    ctx.fillStyle = satelliteMode ? '#1e3d30' : '#1e293b';
    ctx.strokeStyle = satelliteMode ? '#34d399' : '#38bdf8';
    ctx.lineWidth = 1.2;
    drawPoly([
      [115, -32], [118, -35], [130, -32], [138, -36], [145, -38], [150, -37],
      [153, -27], [148, -20], [142, -10], [135, -12], [131, -12], [125, -15],
      [115, -22], [113, -26], [115, -32]
    ]);
    drawPoly([[145, -41], [148, -41], [148, -43], [145, -43], [145, -41]]);
    drawPoly([[172, -35], [178, -38], [174, -42], [170, -46], [168, -44], [172, -35]]);

    // Americas
    drawPoly([
      [-165, 65], [-150, 68], [-130, 70], [-95, 72], [-80, 65], [-85, 55],
      [-75, 50], [-64, 45], [-70, 42], [-75, 35], [-80, 25], [-82, 25],
      [-90, 29], [-97, 26], [-98, 20], [-90, 16], [-84, 9], [-77, 8],
      [-83, 10], [-95, 16], [-105, 22], [-113, 27], [-117, 32], [-122, 37],
      [-125, 48], [-135, 55], [-150, 60], [-165, 65]
    ]);
    drawPoly([[-45, 60], [-25, 70], [-20, 80], [-55, 82], [-50, 70], [-45, 60]]);
    drawPoly([
      [-79, 9], [-74, 11], [-65, 10], [-50, 0], [-35, -8], [-38, -15],
      [-43, -23], [-50, -30], [-58, -34], [-65, -42], [-68, -54], [-75, -50],
      [-73, -40], [-72, -30], [-77, -12], [-80, -2], [-79, 9]
    ]);
    drawPoly([
      [-180, -75], [-120, -78], [-60, -65], [0, -70], [60, -68],
      [120, -66], [180, -75], [180, -90], [-180, -90]
    ]);

    return texCanvas;
  }

  // ---- 4x4 Matrix Math ----------------------------------------------------
  function mat4_create() {
    var m = new Float32Array(16);
    m[0] = 1; m[5] = 1; m[10] = 1; m[15] = 1;
    return m;
  }
  function mat4_perspective(out, fovRad, aspect, near, far) {
    var f = 1.0 / Math.tan(fovRad / 2);
    var nf = 1.0 / (near - far);
    out.fill(0);
    out[0] = f / aspect;
    out[5] = f;
    out[10] = (far + near) * nf;
    out[11] = -1;
    out[14] = (2 * far * near) * nf;
  }
  function mat4_lookAt(out, eyeX, eyeY, eyeZ, cx, cy, cz, ux, uy, uz) {
    var z0 = eyeX - cx, z1 = eyeY - cy, z2 = eyeZ - cz;
    var len = 1 / Math.hypot(z0, z1, z2);
    z0 *= len; z1 *= len; z2 *= len;
    var x0 = uy * z2 - uz * z1, x1 = uz * z0 - ux * z2, x2 = ux * z1 - uy * z0;
    len = 1 / Math.hypot(x0, x1, x2);
    x0 *= len; x1 *= len; x2 *= len;
    var y0 = z1 * x2 - z2 * x1, y1 = z2 * x0 - z0 * x2, y2 = z0 * x1 - z1 * x0;
    out[0] = x0; out[1] = y0; out[2] = z0; out[3] = 0;
    out[4] = x1; out[5] = y1; out[6] = z1; out[7] = 0;
    out[8] = x2; out[9] = y2; out[10] = z2; out[11] = 0;
    out[12] = -(x0 * eyeX + x1 * eyeY + x2 * eyeZ);
    out[13] = -(y0 * eyeX + y1 * eyeY + y2 * eyeZ);
    out[14] = -(z0 * eyeX + z1 * eyeY + z2 * eyeZ);
    out[15] = 1;
  }
  function mat4_multiply(out, a, b) {
    var a00 = a[0], a01 = a[1], a02 = a[2], a03 = a[3];
    var a10 = a[4], a11 = a[5], a12 = a[6], a13 = a[7];
    var a20 = a[8], a21 = a[9], a22 = a[10], a23 = a[11];
    var a30 = a[12], a31 = a[13], a32 = a[14], a33 = a[15];
    for (var i = 0; i < 4; i++) {
      var b0 = b[i * 4], b1 = b[i * 4 + 1], b2 = b[i * 4 + 2], b3 = b[i * 4 + 3];
      out[i * 4] = a00 * b0 + a10 * b1 + a20 * b2 + a30 * b3;
      out[i * 4 + 1] = a01 * b0 + a11 * b1 + a21 * b2 + a31 * b3;
      out[i * 4 + 2] = a02 * b0 + a12 * b1 + a20 * b2 + a32 * b3;
      out[i * 4 + 3] = a03 * b0 + a13 * b1 + a23 * b2 + a33 * b3;
    }
  }

  // ---- WebGL Mode Setup ----------------------------------------------------
  var gl = null;
  try {
    gl = canvas.getContext('webgl', { antialias: true, alpha: true, depth: true }) ||
         canvas.getContext('experimental-webgl', { antialias: true, alpha: true, depth: true });
  } catch (e) {
    gl = null;
  }

  var useWebGL = !!gl;
  var posBuffer, normBuffer, uvBuffer, idxBuffer, prog;
  var attrPos, attrNorm, attrUV, uMVP, uModel, uSampler, uEyePos;
  var satTex, streetTex, currentTex;
  var sphereIndicesCount = 0;

  if (useWebGL) {
    try {
      // 3D Sphere Geometry: Exact Longitude/Latitude UV mapping
      var SPHERE_LAT_SEGS = 48;
      var SPHERE_LON_SEGS = 64;
      var spherePositions = [];
      var sphereNormals = [];
      var sphereUVs = [];
      var sphereIndices = [];

      for (var i = 0; i <= SPHERE_LAT_SEGS; i++) {
        var theta = (i * Math.PI) / SPHERE_LAT_SEGS; // 0 (North Pole) to PI (South Pole)
        var sinTheta = Math.sin(theta);
        var cosTheta = Math.cos(theta);

        for (var j = 0; j <= SPHERE_LON_SEGS; j++) {
          var u = j / SPHERE_LON_SEGS;
          var lng = u * 360 - 180; // -180 to +180 deg
          var lam = lng * DEG2RAD;

          // x = sin(theta) * sin(lam)
          // y = cos(theta)
          // z = sin(theta) * cos(lam)
          var x = sinTheta * Math.sin(lam);
          var y = cosTheta;
          var z = sinTheta * Math.cos(lam);

          spherePositions.push(x, y, z);
          sphereNormals.push(x, y, z);
          sphereUVs.push(u, i / SPHERE_LAT_SEGS);
        }
      }

      for (var i = 0; i < SPHERE_LAT_SEGS; i++) {
        for (var j = 0; j < SPHERE_LON_SEGS; j++) {
          var first = i * (SPHERE_LON_SEGS + 1) + j;
          var second = first + SPHERE_LON_SEGS + 1;
          sphereIndices.push(first, second, first + 1);
          sphereIndices.push(second, second + 1, first + 1);
        }
      }
      sphereIndicesCount = sphereIndices.length;

      posBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, posBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(spherePositions), gl.STATIC_DRAW);

      normBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, normBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(sphereNormals), gl.STATIC_DRAW);

      uvBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, uvBuffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(sphereUVs), gl.STATIC_DRAW);

      idxBuffer = gl.createBuffer();
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, idxBuffer);
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(sphereIndices), gl.STATIC_DRAW);

      var vsSource = [
        'attribute vec3 aPos;',
        'attribute vec3 aNorm;',
        'attribute vec2 aUV;',
        'uniform mat4 uMVP;',
        'uniform mat4 uModel;',
        'varying vec3 vNorm;',
        'varying vec3 vWorldPos;',
        'varying vec2 vUV;',
        'void main(void) {',
        '  vec4 worldPos = uModel * vec4(aPos, 1.0);',
        '  vWorldPos = worldPos.xyz;',
        '  vNorm = normalize((uModel * vec4(aNorm, 0.0)).xyz);',
        '  vUV = aUV;',
        '  gl_Position = uMVP * vec4(aPos, 1.0);',
        '}'
      ].join('\n');

      var fsSource = [
        'precision mediump float;',
        'uniform sampler2D uSampler;',
        'uniform vec3 uEyePos;',
        'varying vec3 vNorm;',
        'varying vec3 vWorldPos;',
        'varying vec2 vUV;',
        'void main(void) {',
        '  vec4 tex = texture2D(uSampler, vUV);',
        '  vec3 N = normalize(vNorm);',
        '  vec3 L = normalize(vec3(0.5, 0.8, 1.0));',
        '  vec3 V = normalize(uEyePos - vWorldPos);',
        '  float diff = max(dot(N, L), 0.0);',
        '  float light = 0.45 + 0.55 * diff;',
        '  float fresnel = pow(1.0 - max(dot(N, V), 0.0), 2.2);',
        '  vec3 atmo = vec3(0.20, 0.60, 1.0) * fresnel * 0.75;',
        '  gl_FragColor = vec4(tex.rgb * light + atmo, 1.0);',
        '}'
      ].join('\n');

      function compileShader(type, src) {
        var s = gl.createShader(type);
        gl.shaderSource(s, src);
        gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
          var err = gl.getShaderInfoLog(s);
          gl.deleteShader(s);
          throw new Error(err);
        }
        return s;
      }

      prog = gl.createProgram();
      gl.attachShader(prog, compileShader(gl.VERTEX_SHADER, vsSource));
      gl.attachShader(prog, compileShader(gl.FRAGMENT_SHADER, fsSource));
      gl.linkProgram(prog);

      attrPos = gl.getAttribLocation(prog, 'aPos');
      attrNorm = gl.getAttribLocation(prog, 'aNorm');
      attrUV = gl.getAttribLocation(prog, 'aUV');
      uMVP = gl.getUniformLocation(prog, 'uMVP');
      uModel = gl.getUniformLocation(prog, 'uModel');
      uSampler = gl.getUniformLocation(prog, 'uSampler');
      uEyePos = gl.getUniformLocation(prog, 'uEyePos');

      satTex = gl.createTexture();
      streetTex = gl.createTexture();
      currentTex = satTex;

      function loadTexture(targetTex, sat) {
        var c = createOfflineEarthTexture(sat);
        gl.bindTexture(gl.TEXTURE_2D, targetTex);
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, c);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
        gl.generateMipmap(gl.TEXTURE_2D);
      }
      loadTexture(satTex, true);
      loadTexture(streetTex, false);
    } catch (e) {
      useWebGL = false; // Graceful switch to 2D Canvas fallback
    }
  }

  // 2D Canvas fallback context if WebGL is unavailable
  var ctx2d = null;
  var cachedTextureCanvas = null;
  if (!useWebGL) {
    ctx2d = canvas.getContext('2d');
    cachedTextureCanvas = createOfflineEarthTexture(true);
  }

  // ---- Render Loop (60 FPS) ------------------------------------------------
  var pMatrix = mat4_create();
  var vMatrix = mat4_create();
  var mMatrix = mat4_create();
  var mvMatrix = mat4_create();
  var mvpMatrix = mat4_create();
  var lastFrameTime = 0;

  function render(now) {
    if (!lastFrameTime) lastFrameTime = now;
    var dt = Math.min((now - lastFrameTime) / 1000, 0.1);
    lastFrameTime = now;

    // Smooth camera ease
    var ease = isInteracting ? 0.35 : 0.14;
    cam.yaw += (targetCam.yaw - cam.yaw) * ease;
    cam.pitch += (targetCam.pitch - cam.pitch) * ease;
    cam.dist += (targetCam.dist - cam.dist) * ease;
    cam.tiltAngle += (targetCam.tiltAngle - cam.tiltAngle) * ease;

    // Smooth auto-rotation when idle
    if (spinning && !isInteracting) {
      targetCam.yaw += 0.045 * dt * 60 * DEG2RAD;
      cam.yaw += 0.045 * dt * 60 * DEG2RAD;
    }

    var w = container.clientWidth || window.innerWidth;
    var h = container.clientHeight || window.innerHeight;

    if (useWebGL) {
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
        gl.viewport(0, 0, w, h);
      }

      gl.clearColor(0.015, 0.035, 0.07, 1.0);
      gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
      gl.enable(gl.DEPTH_TEST);
      gl.enable(gl.CULL_FACE);
      gl.cullFace(gl.BACK);

      var aspect = w / h;
      mat4_perspective(pMatrix, 45 * DEG2RAD, aspect, 0.1, 100.0);

      var eyeDist = cam.dist;
      var eyeX = 0;
      var eyeY = Math.sin(cam.tiltAngle) * eyeDist;
      var eyeZ = Math.cos(cam.tiltAngle) * eyeDist;
      mat4_lookAt(vMatrix, eyeX, eyeY, eyeZ, 0, 0, 0, 0, 1, 0);

      // Model matrix: R = Rx(pitch) * Ry(yaw)
      mMatrix.fill(0);
      var cosP = Math.cos(cam.pitch), sinP = Math.sin(cam.pitch);
      var cosY = Math.cos(cam.yaw), sinY = Math.sin(cam.yaw);

      mMatrix[0] = cosY;
      mMatrix[1] = sinP * sinY;
      mMatrix[2] = -cosP * sinY;
      mMatrix[3] = 0;

      mMatrix[4] = 0;
      mMatrix[5] = cosP;
      mMatrix[6] = sinP;
      mMatrix[7] = 0;

      mMatrix[8] = sinY;
      mMatrix[9] = -sinP * cosY;
      mMatrix[10] = cosP * cosY;
      mMatrix[11] = 0;

      mMatrix[12] = 0; mMatrix[13] = 0; mMatrix[14] = 0; mMatrix[15] = 1;

      mat4_multiply(mvMatrix, vMatrix, mMatrix);
      mat4_multiply(mvpMatrix, pMatrix, mvMatrix);

      gl.useProgram(prog);
      gl.bindBuffer(gl.ARRAY_BUFFER, posBuffer);
      gl.vertexAttribPointer(attrPos, 3, gl.FLOAT, false, 0, 0);
      gl.enableVertexAttribArray(attrPos);

      gl.bindBuffer(gl.ARRAY_BUFFER, normBuffer);
      gl.vertexAttribPointer(attrNorm, 3, gl.FLOAT, false, 0, 0);
      gl.enableVertexAttribArray(attrNorm);

      gl.bindBuffer(gl.ARRAY_BUFFER, uvBuffer);
      gl.vertexAttribPointer(attrUV, 2, gl.FLOAT, false, 0, 0);
      gl.enableVertexAttribArray(attrUV);

      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, idxBuffer);

      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, currentTex);
      gl.uniform1i(uSampler, 0);

      gl.uniformMatrix4fv(uMVP, false, mvpMatrix);
      gl.uniformMatrix4fv(uModel, false, mMatrix);
      gl.uniform3f(uEyePos, eyeX, eyeY, eyeZ);

      gl.drawElements(gl.TRIANGLES, sphereIndicesCount, gl.UNSIGNED_SHORT, 0);
    } else {
      // 2D Canvas Fallback Renderer (Never fails on any phone!)
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w;
        canvas.height = h;
      }
      ctx2d.clearRect(0, 0, w, h);

      var cx = w / 2;
      var cy = h / 2;
      var radius = Math.min(w, h) * 0.40 * (2.8 / cam.dist);

      // Radial outer atmosphere glow
      var glow = ctx2d.createRadialGradient(cx, cy, radius * 0.95, cx, cy, radius * 1.25);
      glow.addColorStop(0, 'rgba(56, 189, 248, 0.45)');
      glow.addColorStop(0.5, 'rgba(56, 189, 248, 0.15)');
      glow.addColorStop(1, 'rgba(56, 189, 248, 0.0)');
      ctx2d.fillStyle = glow;
      ctx2d.beginPath();
      ctx2d.arc(cx, cy, radius * 1.25, 0, Math.PI * 2);
      ctx2d.fill();

      // Earth body with 3D sphere gradient lighting
      ctx2d.save();
      ctx2d.beginPath();
      ctx2d.arc(cx, cy, radius, 0, Math.PI * 2);
      ctx2d.clip();

      var earthGrad = ctx2d.createRadialGradient(cx - radius * 0.35, cy - radius * 0.35, radius * 0.1, cx, cy, radius);
      earthGrad.addColorStop(0, '#0d3264');
      earthGrad.addColorStop(0.6, '#061a35');
      earthGrad.addColorStop(1, '#020914');
      ctx2d.fillStyle = earthGrad;
      ctx2d.fillRect(cx - radius, cy - radius, radius * 2, radius * 2);

      // Draw rotating equirectangular texture mapped onto 2D orthographic sphere
      if (cachedTextureCanvas) {
        var shiftX = (((cam.yaw * RAD2DEG + 180) % 360) / 360) * cachedTextureCanvas.width;
        ctx2d.globalAlpha = 0.85;
        ctx2d.drawImage(cachedTextureCanvas, -shiftX, cy - radius, radius * 4, radius * 2);
        ctx2d.drawImage(cachedTextureCanvas, radius * 4 - shiftX, cy - radius, radius * 4, radius * 2);
        ctx2d.globalAlpha = 1.0;
      }
      ctx2d.restore();

      // Golden horizon rim
      ctx2d.strokeStyle = 'rgba(56, 189, 248, 0.65)';
      ctx2d.lineWidth = 2.0;
      ctx2d.beginPath();
      ctx2d.arc(cx, cy, radius, 0, Math.PI * 2);
      ctx2d.stroke();
    }

    // Project Markers onto Screen
    var isCompact = cam.dist > 2.2;
    Object.keys(markers).forEach(function (id) {
      var m = markers[id];
      if (!m) return;
      var v = m.v; // local unit sphere vector {x, y, z}

      var wx, wy, wz, cx_eye, cy_eye, cz_eye;
      if (useWebGL) {
        // World coordinates: P_w = M * P
        wx = mMatrix[0] * v.x + mMatrix[4] * v.y + mMatrix[8] * v.z;
        wy = mMatrix[1] * v.x + mMatrix[5] * v.y + mMatrix[9] * v.z;
        wz = mMatrix[2] * v.x + mMatrix[6] * v.y + mMatrix[10] * v.z;

        // Eye space: P_eye = V * P_w
        cx_eye = vMatrix[0] * wx + vMatrix[4] * wy + vMatrix[8] * wz + vMatrix[12];
        cy_eye = vMatrix[1] * wx + vMatrix[5] * wy + vMatrix[9] * wz + vMatrix[13];
        cz_eye = vMatrix[2] * wx + vMatrix[6] * wy + vMatrix[10] * wz + vMatrix[14];

        // Occlusion test
        var eyeX = 0;
        var eyeY = Math.sin(cam.tiltAngle) * cam.dist;
        var eyeZ = Math.cos(cam.tiltAngle) * cam.dist;
        var dotCam = -(wx * (eyeX - wx) + wy * (eyeY - wy) + wz * (eyeZ - wz));
        var isOccluded = dotCam >= 0 || cz_eye >= -0.5;

        if (isOccluded) {
          m.el.style.display = 'none';
        } else {
          var clipX = pMatrix[0] * cx_eye;
          var clipY = pMatrix[5] * cy_eye;
          var clipW = -cz_eye;

          var screenX = (clipX / clipW * 0.5 + 0.5) * w;
          var screenY = (1.0 - (clipY / clipW * 0.5 + 0.5)) * h;

          m.el.style.display = 'flex';
          m.el.style.left = screenX.toFixed(1) + 'px';
          m.el.style.top = screenY.toFixed(1) + 'px';
          m.el.classList.toggle('compact', isCompact);
        }
      } else {
        // 2D orthographic projection
        var cosY = Math.cos(cam.yaw), sinY = Math.sin(cam.yaw);
        var cosP = Math.cos(cam.pitch), sinP = Math.sin(cam.pitch);

        var rx = v.x * cosY + v.z * sinY;
        var rz = -v.x * sinY + v.z * cosY;
        var ry = v.y * cosP - rz * sinP;
        var rz2 = v.y * sinP + rz * cosP;

        if (rz2 <= 0.05) {
          m.el.style.display = 'none';
        } else {
          var radius = Math.min(w, h) * 0.40 * (2.8 / cam.dist);
          var screenX = w / 2 + rx * radius;
          var screenY = h / 2 - ry * radius;

          m.el.style.display = 'flex';
          m.el.style.left = screenX.toFixed(1) + 'px';
          m.el.style.top = screenY.toFixed(1) + 'px';
          m.el.classList.toggle('compact', isCompact);
        }
      }
    });

    requestAnimationFrame(render);
  }
  requestAnimationFrame(render);

  // ---- Touch & Mouse Gestures ----------------------------------------------
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
        var factor = lastDist / d;
        targetCam.dist = Math.max(1.3, Math.min(4.5, targetCam.dist * factor));
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

    var speed = (targetCam.dist / 2.7) * 0.0055;
    targetCam.yaw += dx * speed;
    targetCam.pitch = Math.max(-80 * DEG2RAD, Math.min(80 * DEG2RAD, targetCam.pitch - dy * speed));
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
    var delta = ev.deltaY > 0 ? 1.1 : 0.9;
    targetCam.dist = Math.max(1.3, Math.min(4.5, targetCam.dist * delta));
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
            parts: parts,
            el: parts.el,
            v: latLngToVector(d.lat, d.lng),
            sig: ''
          };
        }
        m.v = latLngToVector(d.lat, d.lng);
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
      if (!m) return;
      stopSpin();
      pick(id, false);
      var v = m.v;
      var lng = Math.atan2(v.x, v.z) * RAD2DEG;
      var lat = Math.asin(v.y) * RAD2DEG;

      targetCam.yaw = -lng * DEG2RAD;
      targetCam.pitch = lat * DEG2RAD;
      targetCam.dist = 1.45;
      targetCam.tiltAngle = 25 * DEG2RAD;
    },

    globe: function () {
      targetCam.yaw = -80.95 * DEG2RAD;
      targetCam.pitch = 26.85 * DEG2RAD;
      targetCam.dist = 2.7;
      targetCam.tiltAngle = 0;
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
        var v = markers[ids[0]].v;
        var lng = Math.atan2(v.x, v.z) * RAD2DEG;
        var lat = Math.asin(v.y) * RAD2DEG;
        targetCam.yaw = -lng * DEG2RAD;
        targetCam.pitch = lat * DEG2RAD;
        targetCam.dist = 1.80;
        targetCam.tiltAngle = 18 * DEG2RAD;
        return;
      }
      var sumLat = 0, sumLng = 0;
      ids.forEach(function (k) {
        var v = markers[k].v;
        sumLat += Math.asin(v.y) * RAD2DEG;
        sumLng += Math.atan2(v.x, v.z) * RAD2DEG;
      });
      targetCam.yaw = -(sumLng / ids.length) * DEG2RAD;
      targetCam.pitch = (sumLat / ids.length) * DEG2RAD;
      targetCam.dist = 2.0;
    },

    setStyle: function (k) {
      if (k !== 'sat' && k !== 'street') return;
      kind = k;
      if (useWebGL) {
        currentTex = (k === 'sat') ? satTex : streetTex;
      } else {
        cachedTextureCanvas = createOfflineEarthTexture(k === 'sat');
      }
    },

    tilt: function (on) {
      targetCam.tiltAngle = on ? 40 * DEG2RAD : 0;
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

  // Signal Android that the 3D globe is READY (instant boot!)
  setTimeout(function () {
    try { bridge.onReady && bridge.onReady(); } catch (e) {}
  }, 25);
})();
