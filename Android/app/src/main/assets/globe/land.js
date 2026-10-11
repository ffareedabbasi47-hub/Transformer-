/**
 * STSMN Standalone Offline 3D Earth Data
 * Self-contained GeoJSON features for continents, Uttar Pradesh grid sector, and mathematical graticules.
 * Requires ZERO network access.
 */
(function (global) {
  'use strict';

  // 1. World Landmasses (optimized vector polygons)
  var continents = [
    // Eurasia (including detailed Indian subcontinent contour)
    {
      type: 'Feature',
      properties: { name: 'Eurasia', type: 'continent' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-9.5, 36.5], [-9.0, 43.0], [-1.8, 43.5], [0.0, 49.5], [4.5, 52.0],
          [8.5, 55.0], [5.0, 60.0], [7.0, 63.0], [15.0, 68.0], [28.0, 71.0],
          [40.0, 68.0], [55.0, 69.0], [70.0, 73.0], [85.0, 74.0], [105.0, 78.0],
          [130.0, 72.0], [150.0, 70.0], [170.0, 67.0], [180.0, 65.0], [170.0, 60.0],
          [160.0, 55.0], [140.0, 50.0], [130.0, 42.0], [120.0, 38.0], [122.0, 30.0],
          [118.0, 24.0], [110.0, 20.0], [108.0, 15.0], [105.0, 10.0], [102.0, 2.5],
          [98.0, 8.0], [98.0, 16.0], [92.0, 21.0],
          // Subcontinent - Bay of Bengal & Delta
          [90.5, 22.8], [88.3, 21.7], [85.8, 19.8], [83.3, 17.7], [80.3, 13.1],
          [79.8, 10.3], [78.1, 8.8],
          // Southern tip: Kanyakumari
          [77.5, 8.1],
          // Arabian Sea Coast / Western Ghats
          [76.2, 10.0], [74.8, 13.0], [73.8, 15.4], [72.8, 19.0], [72.2, 21.5],
          // Gujarat / Kathiawar Peninsula
          [71.0, 20.7], [69.0, 22.3], [70.1, 23.0], [68.2, 23.8],
          // Indus mouth to Persian Gulf & Arabia
          [67.0, 25.0], [60.0, 25.2], [57.0, 22.5], [54.0, 17.0], [45.0, 12.5],
          [43.5, 16.0], [35.0, 28.0], [34.0, 31.5], [30.0, 31.0], [26.0, 36.5],
          [23.0, 38.0], [20.0, 40.0], [16.0, 38.5], [14.0, 41.0], [12.0, 44.0],
          [9.0, 43.5], [3.0, 42.5], [-3.0, 36.5], [-9.5, 36.5]
        ]]
      }
    },
    // Africa
    {
      type: 'Feature',
      properties: { name: 'Africa', type: 'continent' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-5.8, 35.8], [3.0, 36.8], [11.0, 37.0], [25.0, 31.8], [32.5, 31.3],
          [34.0, 27.8], [38.5, 21.0], [43.5, 12.5], [51.2, 12.0], [49.3, 10.0],
          [42.0, 0.5], [40.5, -4.0], [40.5, -10.0], [35.5, -20.0], [32.8, -27.0],
          [28.0, -32.5], [19.0, -34.8], [18.0, -34.0], [15.0, -28.0], [12.0, -17.0],
          [9.0, 2.0], [2.0, 6.0], [-4.0, 5.0], [-10.0, 6.0], [-16.0, 11.5],
          [-17.5, 14.8], [-16.0, 20.0], [-13.0, 27.0], [-9.5, 31.0], [-5.8, 35.8]
        ]]
      }
    },
    // North America
    {
      type: 'Feature',
      properties: { name: 'North America', type: 'continent' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-168.0, 66.0], [-150.0, 71.0], [-130.0, 70.0], [-110.0, 68.0], [-85.0, 65.0],
          [-75.0, 62.0], [-65.0, 58.0], [-55.0, 50.0], [-60.0, 44.0], [-70.0, 42.0],
          [-75.0, 36.0], [-80.5, 25.0], [-82.0, 28.5], [-90.0, 29.5], [-97.0, 26.0],
          [-97.0, 20.0], [-88.0, 16.0], [-83.0, 9.0], [-79.5, 8.5], [-85.0, 13.0],
          [-96.0, 16.0], [-105.0, 22.0], [-115.0, 32.0], [-122.0, 37.0], [-124.0, 48.0],
          [-130.0, 54.0], [-140.0, 60.0], [-160.0, 56.0], [-168.0, 66.0]
        ]]
      }
    },
    // South America
    {
      type: 'Feature',
      properties: { name: 'South America', type: 'continent' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-79.5, 8.5], [-72.0, 11.5], [-60.0, 8.0], [-50.0, 0.0], [-35.0, -5.0],
          [-37.0, -12.0], [-42.0, -22.0], [-48.0, -28.0], [-54.0, -34.0], [-62.0, -40.0],
          [-66.0, -50.0], [-67.0, -55.0], [-74.0, -52.0], [-72.0, -42.0], [-70.0, -30.0],
          [-75.0, -18.0], [-80.0, -6.0], [-80.0, 0.0], [-77.0, 6.0], [-79.5, 8.5]
        ]]
      }
    },
    // Australia
    {
      type: 'Feature',
      properties: { name: 'Australia', type: 'continent' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [142.0, -11.0], [146.0, -18.0], [153.0, -28.0], [150.0, -36.0], [144.0, -38.5],
          [138.0, -35.0], [130.0, -31.5], [116.0, -34.5], [114.0, -26.0], [120.0, -20.0],
          [128.0, -15.0], [136.0, -12.0], [142.0, -11.0]
        ]]
      }
    },
    // Antarctica
    {
      type: 'Feature',
      properties: { name: 'Antarctica', type: 'continent' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-60.0, -64.0], [0.0, -70.0], [60.0, -68.0], [120.0, -66.0], [180.0, -74.0],
          [-120.0, -72.0], [-60.0, -64.0]
        ]]
      }
    },
    // Greenland
    {
      type: 'Feature',
      properties: { name: 'Greenland', type: 'island' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-45.0, 60.0], [-35.0, 66.0], [-20.0, 75.0], [-30.0, 82.0], [-55.0, 82.0],
          [-60.0, 76.0], [-50.0, 68.0], [-45.0, 60.0]
        ]]
      }
    },
    // Great Britain & Ireland
    {
      type: 'Feature',
      properties: { name: 'Great Britain', type: 'island' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [-5.0, 50.0], [1.5, 51.5], [0.0, 54.0], [-2.0, 58.5], [-5.5, 56.5],
          [-3.0, 53.5], [-5.0, 50.0]
        ]]
      }
    },
    // Japan
    {
      type: 'Feature',
      properties: { name: 'Japan', type: 'island' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [131.0, 32.0], [136.0, 35.0], [141.0, 38.5], [143.0, 44.0], [140.0, 43.0],
          [136.0, 37.0], [131.0, 32.0]
        ]]
      }
    },
    // Madagascar
    {
      type: 'Feature',
      properties: { name: 'Madagascar', type: 'island' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [44.0, -13.0], [50.0, -13.0], [48.0, -22.0], [45.0, -25.5], [43.5, -20.0], [44.0, -13.0]
        ]]
      }
    },
    // Sri Lanka
    {
      type: 'Feature',
      properties: { name: 'Sri Lanka', type: 'island' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [80.0, 9.8], [81.8, 7.5], [81.0, 6.0], [79.8, 7.0], [80.0, 9.8]
        ]]
      }
    },
    // 2. Uttar Pradesh Regional Monitoring Sector Polygon
    {
      type: 'Feature',
      properties: { name: 'Uttar Pradesh Sector', type: 'sector' },
      geometry: {
        type: 'Polygon',
        coordinates: [[
          [77.3, 28.6], [77.6, 29.9], [78.5, 29.5], [79.4, 28.8], [80.5, 28.2],
          [81.8, 27.6], [82.8, 27.2], [83.8, 26.8], [84.4, 26.0], [83.5, 25.3],
          [83.0, 24.2], [82.2, 25.1], [81.8, 25.4], [80.3, 25.3], [78.5, 24.7],
          [78.6, 25.5], [78.0, 27.2], [77.4, 27.8], [77.3, 28.6]
        ]]
      }
    }
  ];

  // 3. Mathematical Graticule Grid Generator
  function generateGraticules() {
    var features = [];
    var lats = [-75, -60, -45, -30, -23.436, -15, 0, 15, 23.436, 30, 45, 60, 75];
    for (var i = 0; i < lats.length; i++) {
      var lat = lats[i];
      var coords = [];
      for (var lng = -180; lng <= 180; lng += 5) {
        coords.push([lng, lat]);
      }
      var isEq = lat === 0;
      var isTr = Math.abs(lat - 23.436) < 0.1 || Math.abs(lat + 23.436) < 0.1;
      features.push({
        type: 'Feature',
        properties: { kind: isEq ? 'equator' : (isTr ? 'tropic' : 'parallel'), lat: lat },
        geometry: { type: 'LineString', coordinates: coords }
      });
    }
    for (var m = -180; m < 180; m += 30) {
      var mcoords = [];
      for (var mlat = -85; mlat <= 85; mlat += 5) {
        mcoords.push([m, mlat]);
      }
      features.push({
        type: 'Feature',
        properties: { kind: m === 0 ? 'prime' : 'meridian', lng: m },
        geometry: { type: 'LineString', coordinates: mcoords }
      });
    }
    return { type: 'FeatureCollection', features: features };
  }

  // 4. Regional Power Grid Transmission Interconnects (Cyber Lines across UP Hubs)
  function generateRegionalGrid() {
    var hubs = {
      lucknow: [80.946, 26.847],
      kanpur: [80.332, 26.450],
      meerut: [77.706, 28.984],
      varanasi: [82.974, 25.318],
      agra: [78.008, 27.177],
      bareilly: [79.430, 28.367],
      gorakhpur: [83.373, 26.761],
      prayagraj: [81.846, 25.436]
    };
    var lines = [
      [hubs.lucknow, hubs.kanpur],
      [hubs.lucknow, hubs.bareilly],
      [hubs.lucknow, hubs.prayagraj],
      [hubs.lucknow, hubs.gorakhpur],
      [hubs.prayagraj, hubs.varanasi],
      [hubs.bareilly, hubs.meerut],
      [hubs.kanpur, hubs.agra],
      [hubs.meerut, hubs.agra]
    ];
    var features = [];
    lines.forEach(function (ln, idx) {
      features.push({
        type: 'Feature',
        properties: { id: 'corridor-' + idx },
        geometry: { type: 'LineString', coordinates: ln }
      });
    });
    return { type: 'FeatureCollection', features: features };
  }

  // 5. Geodetic Radar Circles centered on Lucknow / STSMN demo site
  function generateRadarCircles(cLng, cLat, radiiKm) {
    var features = [];
    radiiKm.forEach(function (rKm) {
      var coords = [];
      var steps = 64;
      var d = rKm / 6371;
      var latRad = cLat * Math.PI / 180;
      var lngRad = cLng * Math.PI / 180;
      for (var i = 0; i <= steps; i++) {
        var brng = (i * 2 * Math.PI) / steps;
        var pLat = Math.asin(Math.sin(latRad) * Math.cos(d) + Math.cos(latRad) * Math.sin(d) * Math.cos(brng));
        var pLng = lngRad + Math.atan2(Math.sin(brng) * Math.sin(d) * Math.cos(latRad), Math.cos(d) - Math.sin(latRad) * Math.sin(pLat));
        coords.push([pLng * 180 / Math.PI, pLat * 180 / Math.PI]);
      }
      features.push({
        type: 'Feature',
        properties: { radius: rKm },
        geometry: { type: 'LineString', coordinates: coords }
      });
    });
    return { type: 'FeatureCollection', features: features };
  }

  global.STSMN_LAND = { type: 'FeatureCollection', features: continents };
  global.STSMN_GRATICULES = generateGraticules();
  global.STSMN_GRID = generateRegionalGrid();
  global.STSMN_RADAR = generateRadarCircles(80.946, 26.847, [120, 280, 500]);
})(window);
