package com.stsmn.monitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

enum class MapViewMode {
    GLOBE_3D,    // 3D Space & Earth Orbit Engine
    STREET_2D,   // 2D Street Map (OpenStreetMap)
}

fun globeJson(devices: List<MapDevice>): String {
    val array = JSONArray()
    devices.filter { it.hasLocation }.forEach { device ->
        val obj = JSONObject()
        obj.put("id", device.id)
        obj.put("name", device.name)
        obj.put("lat", device.lat)
        obj.put("lng", device.lng)
        obj.put("state", device.state.name)
        obj.put("demo", device.demoLocation)
        obj.put("isLive", device.isLive)
        obj.put("risk", device.risk ?: 0)
        obj.put("temp", device.temperature ?: 0.0)
        obj.put("volt", device.voltage ?: 0.0)
        obj.put("amp", device.current ?: 0.0)
        obj.put("smoke", device.smoke ?: false)
        obj.put("flame", device.flame ?: false)
        obj.put("oil", device.oilLevel ?: 0)
        obj.put("relay", device.relay ?: false)
        obj.put("alarm", device.alarm ?: false)
        obj.put("ageMs", device.ageMs ?: 0L)
        array.put(obj)
    }
    return array.toString()
}

/**
 * Authentic 3D Earth Engine with Progressive Multi-Level Detail (Google Earth Style):
 * - Smooth continuous zoom from Space (1x) down to Substation & Transformer level (25x) - Zero sudden popping!
 * - Interactive Transformer ID Search bar with instant camera fly-to animation!
 * - Level 1 (Space Orbit): Atmospheric Rayleigh halo, ocean specular reflection, continents, night city lights.
 * - Level 2 (Subcontinent): Country boundaries, Indian subcontinent, Ganga & Yamuna rivers, major metros.
 * - Level 3 (UP Regional Power Grid): Uttar Pradesh state border, 400kV & 220kV transmission corridors, district nodes.
 * - Level 4 (Substation & Transformers): Substation yard layout, live voltage/temp badges, pulsing radar beacons!
 * - Direct Google Earth 3D launcher intent button (opens official com.google.earth app at exact transformer coordinates).
 */
@Composable
fun GlobeScreen(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onUseFlatMap: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var viewMode by remember { mutableStateOf(MapViewMode.GLOBE_3D) }

    // If user explicitly switched to 2D Street Map (OpenStreetMap)
    if (viewMode == MapViewMode.STREET_2D) {
        Box(Modifier.fillMaxSize()) {
            MapScreen(
                devices = devices,
                fleet = fleet,
                cloudEnabled = cloudEnabled,
                selectedId = selectedId,
                onSelect = onSelect,
                onOpenSettings = onOpenSettings,
                initialDeepZoom = false, // Natural zoom level without sudden forced jump
            )

            // Floating Button to return to 3D Space Orbit
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.94f))
                    .clickable { viewMode = MapViewMode.GLOBE_3D }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🌍", fontSize = 14.sp)
                    Text(
                        "3D Globe",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
        return
    }

    // ----------------- 3D Spherical Earth Mode -----------------
    var yaw by remember { mutableFloatStateOf(80.95f) }
    var pitch by remember { mutableFloatStateOf(24.0f) }
    var zoom by remember { mutableFloatStateOf(1.0f) }
    var isSpinning by remember { mutableStateOf(true) }

    // Smooth Camera Flight Interpolation
    var targetYaw by remember { mutableFloatStateOf(80.95f) }
    var targetPitch by remember { mutableFloatStateOf(24.0f) }
    var targetZoom by remember { mutableFloatStateOf(1.0f) }
    var isFlying by remember { mutableStateOf(false) }

    fun flyTo(toYaw: Float, toPitch: Float, toZoom: Float) {
        targetYaw = toYaw
        targetPitch = toPitch.coerceIn(-75f, 75f)
        targetZoom = toZoom.coerceIn(0.65f, 25.0f)
        isSpinning = false
        isFlying = true
    }

    LaunchedEffect(isFlying) {
        if (!isFlying) return@LaunchedEffect
        while (isFlying) {
            withFrameMillis {
                val dYaw = targetYaw - yaw
                val dPitch = targetPitch - pitch
                val dZoom = targetZoom - zoom
                if (kotlin.math.abs(dYaw) < 0.15f && kotlin.math.abs(dPitch) < 0.15f && kotlin.math.abs(dZoom) < 0.05f) {
                    yaw = targetYaw
                    pitch = targetPitch
                    zoom = targetZoom
                    isFlying = false
                } else {
                    yaw += dYaw * 0.16f
                    pitch += dPitch * 0.16f
                    zoom += dZoom * 0.16f
                }
            }
        }
    }

    // Search state
    var searchQuery by remember { mutableStateOf("") }

    val selected = devices.firstOrNull { it.id == selectedId }

    // Auto-Spin Loop when idle in space view
    LaunchedEffect(isSpinning) {
        if (!isSpinning) return@LaunchedEffect
        while (true) {
            withFrameMillis {
                yaw = (yaw + 0.20f) % 360f
            }
        }
    }

    // Animated telemetry radar wave at site
    val transition = rememberInfiniteTransition(label = "beacon")
    val radarPing by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "radarPing",
    )

    // Pre-generate cosmic starfield
    val stars = remember {
        val r = Random(42)
        List(85) {
            Triple(r.nextFloat(), r.nextFloat(), r.nextFloat() * 1.5f + 0.5f)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF030712))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    isSpinning = false
                    isFlying = false
                    yaw = (yaw - pan.x * (0.35f / zoom)) % 360f
                    pitch = (pitch + pan.y * (0.35f / zoom)).coerceIn(-75f, 75f)
                    // Smooth, continuous zoom from 0.65x up to 25.0x without sudden forced jumps!
                    zoom = (zoom * gestureZoom).coerceIn(0.65f, 25.0f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    searchQuery = ""
                    onSelect(null)
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val baseR = minOf(widthPx, heightPx) * 0.36f
        val R = baseR * zoom

        val density = LocalDensity.current

        // 1. Native Photorealistic 3D Earth Canvas (Google Earth Visuals)
        Canvas(Modifier.fillMaxSize()) {
            drawSpaceBackground(widthPx, heightPx, stars)
            drawGlobeSphere(cx, cy, R, yaw, pitch, zoom, radarPing)
        }

        // 2. Landmarks & City Nodes (Progressive Level of Detail)
        MAP_LANDMARKS.forEach { lm ->
            if (zoom in lm.minZoom..lm.maxZoom) {
                val proj = project3D(lm.lat, lm.lng, yaw, pitch)
                if (proj.third > 0.08f) {
                    val lx = cx + proj.first * R
                    val ly = cy - proj.second * R
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    (lx - with(density) { 35.dp.toPx() }).toInt(),
                                    (ly - with(density) { 10.dp.toPx() }).toInt(),
                                )
                            }
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (lm.isCapital) Color(0xFF1E3A8A).copy(alpha = 0.88f)
                                else if (lm.isGridHub) Color(0xFF78350F).copy(alpha = 0.88f)
                                else Color(0xFF0F172A).copy(alpha = 0.80f),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            lm.name,
                            fontSize = if (lm.isCapital) 10.sp else 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (lm.isCapital) Color(0xFF93C5FD) else if (lm.isGridHub) Color(0xFFFDE68A) else Color(0xFFCBD5E1),
                        )
                    }
                }
            }
        }

        // 3. Transformer Live Pins on 3D Earth
        devices.forEach { d ->
            val lat = d.lat?.toFloat() ?: 26.85f
            val lng = d.lng?.toFloat() ?: 80.95f
            val proj = project3D(lat, lng, yaw, pitch)

            if (proj.third > 0.08f) {
                val screenX = cx + proj.first * R
                val screenY = cy - proj.second * R

                val isSelected = (d.id == selectedId)
                val statusColor = markerColor(d.state)

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (screenX - with(density) { (if (zoom >= 7f) 52.dp else 40.dp).toPx() }).toInt(),
                                (screenY - with(density) { (if (zoom >= 7f) 62.dp else 46.dp).toPx() }).toInt(),
                            )
                        }
                        .clickable { onSelect(d.id) },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (zoom >= 7.0f) {
                            // Rich Engineering Badge with live readings directly on 3D map!
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)
                                        else Color(0xFF0A1224).copy(alpha = 0.92f),
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color.White else statusColor,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                                        Text(
                                            d.label,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                        )
                                    }
                                    if (d.voltage != null || d.temperature != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(top = 1.dp),
                                        ) {
                                            Text(
                                                fmtVolt(d.voltage),
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF38BDF8),
                                            )
                                            Text("•", fontSize = 8.sp, color = Color(0xFF64748B))
                                            Text(
                                                fmtTemp(d.temperature),
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = StsmnColors.Safe,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Compact badge for space / continental scale
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)
                                        else Color(0xFF0A1224).copy(alpha = 0.88f),
                                    )
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    d.label,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) Color.White else Color(0xFFE2E8F0),
                                )
                            }
                        }

                        // Marker Pin Core
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(if (isSelected) 14.dp else 11.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                    }
                }
            }
        }

        // 4. Top Floating Search Bar & Quick Controls
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Interactive Search Bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.94f))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("🔍", fontSize = 15.sp)
                    Box(Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                "Search Transformer ID (e.g. 01, STSMN)...",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            textStyle = TextStyle(fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        }
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                            .clickable { viewMode = MapViewMode.STREET_2D }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("🛣️ 2D Map", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Live Search Suggestions Dropdown
            val matching = remember(searchQuery, devices) {
                if (searchQuery.isBlank()) emptyList()
                else devices.filter { d ->
                    d.id.contains(searchQuery, ignoreCase = true) ||
                    d.label.contains(searchQuery, ignoreCase = true) ||
                    d.name.contains(searchQuery, ignoreCase = true)
                }
            }

            AnimatedVisibility(visible = searchQuery.isNotBlank() && matching.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A).copy(alpha = 0.98f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    matching.take(4).forEach { d ->
                        val stColor = markerColor(d.state)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    flyTo(d.lng?.toFloat() ?: 80.95f, d.lat?.toFloat() ?: 26.85f, 12.0f)
                                    onSelect(d.id)
                                    searchQuery = ""
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(stColor))
                                Column {
                                    Text(d.label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text(
                                        "${d.name} • ${d.voltage?.let { fmtVolt(it) } ?: "230V"} • ${d.temperature?.let { fmtTemp(it) } ?: "45°C"}",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8),
                                    )
                                }
                            }
                            Text("Fly ✈️", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // Scale & Zoom Level Indicator Chips
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ScaleChip("🌌 Space", isActive = zoom < 2.0f) {
                    flyTo(80.95f, 24.0f, 1.0f)
                    isSpinning = true
                }
                ScaleChip("🇮🇳 India", isActive = zoom in 2.0f..4.8f) {
                    flyTo(80.95f, 24.0f, 3.2f)
                }
                ScaleChip("⚡ UP Grid", isActive = zoom in 4.8f..10.0f) {
                    flyTo(80.95f, 26.85f, 7.5f)
                }
                ScaleChip("📍 Substation", isActive = zoom >= 10.0f) {
                    flyTo(80.95f, 26.85f, 15.0f)
                }
            }
        }

        // 5. Bottom Controls & Telemetry Overlay
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Selected Transformer Card with Google Earth 3D, 2D Street Map & 360° Street View
            if (selected != null) {
                UnifiedGlobeDeviceCard(
                    d = selected,
                    onClose = { onSelect(null) },
                    onOpenStreetView = {
                        val lat = selected.lat ?: 26.8467
                        val lng = selected.lng ?: 80.9462
                        openStreetView(ctx, lat, lng)
                    },
                    onOpenGoogleEarth = {
                        val lat = selected.lat ?: 26.8467
                        val lng = selected.lng ?: 80.9462
                        openGoogleEarth(ctx, lat, lng)
                    },
                    onZoomToRoads = {
                        viewMode = MapViewMode.STREET_2D
                    },
                )
            }

            // Floating Navigation & Action Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.94f))
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = {
                        flyTo(80.95f, 24.0f, 1.0f)
                        isSpinning = true
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("🌍 Orbit", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = { viewMode = MapViewMode.STREET_2D },
                    modifier = Modifier.weight(1.05f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("🛣️ 2D Map", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = {
                        val lat = selected?.lat ?: 26.8467
                        val lng = selected?.lng ?: 80.9462
                        openGoogleEarth(ctx, lat, lng)
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("🌍 Earth 3D", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = {
                        val lat = selected?.lat ?: 26.8467
                        val lng = selected?.lng ?: 80.9462
                        openStreetView(ctx, lat, lng)
                    },
                    modifier = Modifier.weight(0.95f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("📸 Pano", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = { isSpinning = !isSpinning },
                    modifier = Modifier.weight(0.7f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text(if (isSpinning) "⏹️" else "▶️", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }

            Text(
                "Pinch to zoom smoothly from orbit to substation • Search transformer above",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF64748B),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun ScaleChip(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.90f)
                else Color(0xFF0F172A).copy(alpha = 0.85f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = if (isActive) Color.White else Color(0xFF94A3B8),
        )
    }
}

// -----------------------------------------------------------------------------
// 3D Spherical Orthographic Projection (Pure Math)
// -----------------------------------------------------------------------------
private fun project3D(latDeg: Float, lngDeg: Float, yawDeg: Float, pitchDeg: Float): Triple<Float, Float, Float> {
    val phi = Math.toRadians(latDeg.toDouble())
    val lam = Math.toRadians((lngDeg - yawDeg).toDouble())
    val pitch = Math.toRadians(pitchDeg.toDouble())

    val cosPhi = cos(phi)
    val sinPhi = sin(phi)
    val cosLam = cos(lam)
    val sinLam = sin(lam)

    val cosP = cos(pitch)
    val sinP = sin(pitch)

    val x = (cosPhi * sinLam).toFloat()
    val y = (sinPhi * cosP - cosPhi * cosLam * sinP).toFloat()
    val z = (sinPhi * sinP + cosPhi * cosLam * cosP).toFloat()

    return Triple(x, y, z)
}

// -----------------------------------------------------------------------------
// Canvas Drawing Routines (Google Earth / Blue Marble Photorealism)
// -----------------------------------------------------------------------------
private fun DrawScope.drawSpaceBackground(w: Float, h: Float, stars: List<Triple<Float, Float, Float>>) {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF0F1E3A), Color(0xFF060D1E), Color(0xFF02040A)),
            center = Offset(w / 2f, h / 2f),
            radius = maxOf(w, h),
        ),
    )

    stars.forEach { (sx, sy, r) ->
        drawCircle(
            color = Color.White.copy(alpha = 0.75f),
            radius = r,
            center = Offset(sx * w, sy * h),
        )
    }
}

private fun DrawScope.drawGlobeSphere(
    cx: Float,
    cy: Float,
    R: Float,
    yaw: Float,
    pitch: Float,
    zoom: Float,
    radarPing: Float,
) {
    // 1. Atmospheric Outer Rayleigh Scattering Glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x5538BDF8),
                Color(0x350EA5E9),
                Color(0x150284C7),
                Color(0x000284C7),
            ),
            center = Offset(cx, cy),
            radius = R * 1.32f,
        ),
        radius = R * 1.32f,
        center = Offset(cx, cy),
    )

    // 2. Earth Sphere Boundary Clipping Path
    val sphereClip = Path().apply {
        addOval(Rect(cx - R, cy - R, cx + R, cy + R))
    }

    clipPath(sphereClip) {
        // 2a. Photorealistic Ocean Hydrosphere (Deep Oceanic Abyss to Sunlit Azure)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF16487E), // Sunlit mid-ocean blue
                    Color(0xFF0C2B52), // Deep royal ocean
                    Color(0xFF061833), // Abyssal deep ocean
                    Color(0xFF020A16), // Dark edge
                ),
                center = Offset(cx - R * 0.30f, cy - R * 0.30f),
                radius = R * 1.25f,
            ),
            topLeft = Offset(cx - R, cy - R),
            size = Size(R * 2, R * 2),
        )

        // 2b. Specular Sun Glint on Ocean Surface (Natural 3D Curvature Highlight)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x45E0F2FE),
                    Color(0x2038BDF8),
                    Color(0x000284C7),
                ),
                center = Offset(cx - R * 0.32f, cy - R * 0.32f),
                radius = R * 0.65f,
            ),
            radius = R * 0.65f,
            center = Offset(cx - R * 0.32f, cy - R * 0.32f),
        )

        // 2c. Graticule (Latitude & Longitude Grid - Google Earth Style)
        drawGraticule(cx, cy, R, yaw, pitch)

        // 2d. Polar Ice Continents (Antarctica & Greenland - Brilliant Ice White)
        drawGlobePolygon(
            pts = NATIVE_ANTARCTICA, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
            fillColor = Color(0xFFF1F5F9), strokeColor = Color(0xFFE2E8F0), strokeWidth = 1.2f,
        )
        drawGlobePolygon(
            pts = NATIVE_GREENLAND, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
            fillColor = Color(0xFFF8FAFC), strokeColor = Color(0xFFE2E8F0), strokeWidth = 1.2f,
        )

        // 2e. Major Continents & Landmasses (Natural Vegetation Green + Coastal Shelf Cyan)
        val contVegColor = Color(0xFF1F6D3E)
        val contCoastColor = Color(0xFF38A169)
        NATIVE_CONTINENTS.forEach { pts ->
            drawGlobePolygon(
                pts = pts, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
                fillColor = contVegColor, strokeColor = contCoastColor, strokeWidth = 1.2f,
            )
        }

        // 2f. Major Deserts (Sahara, Arabia, Thar, Australian Outback - Warm Golden Sand)
        val desertColor = Color(0xFFC09848)
        val desertStroke = Color(0xFFD4AF37)
        NATIVE_DESERTS.forEach { pts ->
            drawGlobePolygon(
                pts = pts, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
                fillColor = desertColor, strokeColor = desertStroke, strokeWidth = 1.0f,
            )
        }

        // 2g. Mountain Ridges (Himalayas - High Elevation & Snow)
        drawGlobePolygon(
            pts = NATIVE_HIMALAYAS, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
            fillColor = Color(0xFF8B8579), strokeColor = Color(0xFFCBD5E1), strokeWidth = 1.4f,
        )

        // 2h. Major World Islands (UK, Japan, Madagascar, Indonesia, Sri Lanka, New Zealand)
        NATIVE_ISLANDS.forEach { pts ->
            drawGlobePolygon(
                pts = pts, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
                fillColor = Color(0xFF237644), strokeColor = Color(0xFF4ADE80), strokeWidth = 1.2f,
            )
        }

        // 2i. Indian Subcontinent (High Precision Border & Emerald Fill)
        drawGlobePolygon(
            pts = NATIVE_INDIA, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
            fillColor = Color(0xFF15803D),
            strokeColor = Color(0xFF38BDF8),
            strokeWidth = 2.2f,
        )

        // 2j. Neighboring Country Borders (Pakistan, Nepal, Bangladesh)
        if (zoom >= 1.8f) {
            drawGlobePolyline(BORDER_PAKISTAN, cx, cy, R, yaw, pitch, color = Color(0x88CBD5E1), strokeWidth = 1.2f)
            drawGlobePolyline(BORDER_NEPAL, cx, cy, R, yaw, pitch, color = Color(0x88CBD5E1), strokeWidth = 1.2f)
            drawGlobePolyline(BORDER_BANGLADESH, cx, cy, R, yaw, pitch, color = Color(0x88CBD5E1), strokeWidth = 1.2f)
        }

        // 2k. Major Rivers (Ganga & Yamuna)
        if (zoom >= 2.0f) {
            drawGlobePolyline(RIVER_GANGA, cx, cy, R, yaw, pitch, color = Color(0xFF0284C7).copy(alpha = 0.85f), strokeWidth = 2.2f)
            drawGlobePolyline(RIVER_YAMUNA, cx, cy, R, yaw, pitch, color = Color(0xFF38BDF8).copy(alpha = 0.80f), strokeWidth = 1.8f)
        }

        // 2l. Uttar Pradesh State (Ajrara Demo Site - Glowing Amber)
        drawGlobePolygon(
            pts = NATIVE_UP, cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
            fillColor = Color(0x77F59E0B),
            strokeColor = Color(0xFFFBBF24),
            strokeWidth = if (zoom >= 4.5f) 3.2f else 2.4f,
        )

        // 2m. High-Voltage Electrical Power Grid Corridors (400kV & 220kV UPPTCL Lines)
        if (zoom >= 4.5f) {
            POWER_GRID_LINES_400KV.forEach { line ->
                drawGlobePolyline(line, cx, cy, R, yaw, pitch, color = Color(0xFFFBBF24).copy(alpha = 0.85f), strokeWidth = 2.4f)
            }
            POWER_GRID_LINES_220KV.forEach { line ->
                drawGlobePolyline(line, cx, cy, R, yaw, pitch, color = Color(0xFF38BDF8).copy(alpha = 0.75f), strokeWidth = 1.6f)
            }
        }

        // 2n. Substation Yard Layout Blueprint (at zoom >= 10.0f)
        if (zoom >= 10.0f) {
            val pSub = project3D(AppConfig.UP_CENTER_LAT.toFloat(), AppConfig.UP_CENTER_LNG.toFloat(), yaw, pitch)
            if (pSub.third > 0.05f) {
                val sx = cx + pSub.first * R
                val sy = cy - pSub.second * R
                val yardSize = 42f * (zoom / 10f)

                // Substation Yard Fence
                drawRect(
                    color = Color(0x44FBBF24),
                    topLeft = Offset(sx - yardSize, sy - yardSize),
                    size = Size(yardSize * 2, yardSize * 2),
                    style = Stroke(width = 1.8f),
                )
                // 33kV Main Busbar
                drawLine(
                    color = Color(0xFFFBBF24),
                    start = Offset(sx - yardSize * 0.85f, sy - yardSize * 0.35f),
                    end = Offset(sx + yardSize * 0.85f, sy - yardSize * 0.35f),
                    strokeWidth = 2.6f,
                )
                // 11kV Feeder Bays
                drawLine(
                    color = Color(0xFF38BDF8),
                    start = Offset(sx - yardSize * 0.5f, sy - yardSize * 0.35f),
                    end = Offset(sx - yardSize * 0.5f, sy + yardSize * 0.7f),
                    strokeWidth = 1.8f,
                )
                drawLine(
                    color = Color(0xFF38BDF8),
                    start = Offset(sx, sy - yardSize * 0.35f),
                    end = Offset(sx, sy + yardSize * 0.7f),
                    strokeWidth = 1.8f,
                )
                drawLine(
                    color = Color(0xFF38BDF8),
                    start = Offset(sx + yardSize * 0.5f, sy - yardSize * 0.35f),
                    end = Offset(sx + yardSize * 0.5f, sy + yardSize * 0.7f),
                    strokeWidth = 1.8f,
                )
            }
        }

        // 2o. Pulsing Telemetry Beacon at Ajrara Site (26.85°N, 80.95°E)
        val pBeacon = project3D(26.85f, 80.95f, yaw, pitch)
        if (pBeacon.third > 0.05f) {
            val bx = cx + pBeacon.first * R
            val by = cy - pBeacon.second * R
            val waveRadius = 6f + radarPing * 26f

            drawCircle(
                color = Color(0xFFFBBF24).copy(alpha = 1f - radarPing),
                radius = waveRadius,
                center = Offset(bx, by),
                style = Stroke(width = 2.4f),
            )
            drawCircle(color = Color(0xFFF59E0B), radius = 5.5f, center = Offset(bx, by))
            drawCircle(color = Color.White, radius = 2.2f, center = Offset(bx, by))
        }

        // 2p. Night-Side City Lights (World Power Grids on Dark Hemisphere)
        if (zoom < 6.0f) {
            drawNightCityLights(cx, cy, R, yaw, pitch)
        }

        // 2q. Inner Atmospheric Rim Glow (Fresnel Rayleigh Effect along Sphere Limb)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x0038BDF8),
                    Color(0x1538BDF8),
                    Color(0x5538BDF8),
                    Color(0xAA67E8F9),
                ),
                center = Offset(cx, cy),
                radius = R,
            ),
            radius = R,
            center = Offset(cx, cy),
        )

        // 2r. Spherical Shadow / Terminator (Night-Side Shading for True 3D Depth)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x00000000),
                    Color(0x15020617),
                    Color(0x45020617),
                    Color(0x85020617),
                ),
                center = Offset(cx - R * 0.28f, cy - R * 0.28f),
                radius = R * 1.30f,
            ),
            topLeft = Offset(cx - R, cy - R),
            size = Size(R * 2, R * 2),
        )
    }

    // 3. Crisp Blue Atmospheric Perimeter Ring
    drawCircle(
        color = Color(0xFF38BDF8).copy(alpha = 0.85f),
        radius = R,
        center = Offset(cx, cy),
        style = Stroke(width = 2.0f),
    )
}

private fun DrawScope.drawGlobePolyline(
    pts: List<Pair<Float, Float>>,
    cx: Float,
    cy: Float,
    R: Float,
    yaw: Float,
    pitch: Float,
    color: Color,
    strokeWidth: Float = 1.5f,
) {
    val path = Path()
    var started = false
    for (pt in pts) {
        val p = project3D(pt.second, pt.first, yaw, pitch)
        if (p.third > 0.05f) {
            val sx = cx + p.first * R
            val sy = cy - p.second * R
            if (!started) { path.moveTo(sx, sy); started = true }
            else { path.lineTo(sx, sy) }
        } else {
            started = false
        }
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawNightCityLights(
    cx: Float,
    cy: Float,
    R: Float,
    yaw: Float,
    pitch: Float,
) {
    WORLD_CITY_LIGHTS.forEach { (lat, lng, pwr) ->
        val p = project3D(lat, lng, yaw, pitch)
        if (p.third > 0.05f) {
            val sx = cx + p.first * R
            val sy = cy - p.second * R

            val dx = p.first - (-0.3f)
            val dy = (-p.second) - (-0.3f)
            val distSun = dx * dx + dy * dy
            val nightFactor = (distSun * 0.9f).coerceIn(0.25f, 1.0f)

            val baseRadius = (pwr * 1.2f).coerceAtLeast(1.5f)
            drawCircle(
                color = Color(0x66F59E0B).copy(alpha = 0.45f * nightFactor),
                radius = baseRadius * 2.2f,
                center = Offset(sx, sy),
            )
            drawCircle(
                color = Color(0xFFFFEE88).copy(alpha = 0.85f * nightFactor),
                radius = baseRadius,
                center = Offset(sx, sy),
            )
        }
    }
}

private fun DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    R: Float,
    yaw: Float,
    pitch: Float,
) {
    val gridColor = Color(0x2838BDF8)

    // Latitude Parallels
    for (lat in -60..60 step 30) {
        val path = Path()
        var started = false
        for (lng in -180..180 step 6) {
            val p = project3D(lat.toFloat(), lng.toFloat(), yaw, pitch)
            if (p.third > 0.02f) {
                val sx = cx + p.first * R
                val sy = cy - p.second * R
                if (!started) { path.moveTo(sx, sy); started = true }
                else { path.lineTo(sx, sy) }
            } else {
                started = false
            }
        }
        drawPath(path, color = gridColor, style = Stroke(width = 0.9f))
    }

    // Equator (0° Latitude) in Golden Accent
    val eqPath = Path()
    var eqStarted = false
    for (lng in -180..180 step 5) {
        val p = project3D(0f, lng.toFloat(), yaw, pitch)
        if (p.third > 0.02f) {
            val sx = cx + p.first * R
            val sy = cy - p.second * R
            if (!eqStarted) { eqPath.moveTo(sx, sy); eqStarted = true }
            else { eqPath.lineTo(sx, sy) }
        } else {
            eqStarted = false
        }
    }
    drawPath(eqPath, color = Color(0x55FBBF24), style = Stroke(width = 1.4f))

    // Tropic of Cancer (23.5° N - passes through India)
    val cancerPath = Path()
    var cancerStarted = false
    for (lng in -180..180 step 6) {
        val p = project3D(23.5f, lng.toFloat(), yaw, pitch)
        if (p.third > 0.02f) {
            val sx = cx + p.first * R
            val sy = cy - p.second * R
            if (!cancerStarted) { cancerPath.moveTo(sx, sy); cancerStarted = true }
            else { cancerPath.lineTo(sx, sy) }
        } else {
            cancerStarted = false
        }
    }
    drawPath(cancerPath, color = Color(0x3838BDF8), style = Stroke(width = 1.0f))

    // Longitude Meridians
    for (lng in -180..150 step 30) {
        val mPath = Path()
        var mStarted = false
        for (lat in -80..80 step 5) {
            val p = project3D(lat.toFloat(), lng.toFloat(), yaw, pitch)
            if (p.third > 0.02f) {
                val sx = cx + p.first * R
                val sy = cy - p.second * R
                if (!mStarted) { mPath.moveTo(sx, sy); mStarted = true }
                else { mPath.lineTo(sx, sy) }
            } else {
                mStarted = false
            }
        }
        drawPath(mPath, color = gridColor, style = Stroke(width = 0.8f))
    }
}

private fun DrawScope.drawGlobePolygon(
    pts: List<Pair<Float, Float>>,
    cx: Float,
    cy: Float,
    R: Float,
    yaw: Float,
    pitch: Float,
    fillColor: Color,
    strokeColor: Color,
    strokeWidth: Float = 1.2f,
) {
    var hasVisible = false
    for (pt in pts) {
        val p = project3D(pt.second, pt.first, yaw, pitch)
        if (p.third > 0.05f) { hasVisible = true; break }
    }
    if (!hasVisible) return

    val path = Path()
    var first = true
    for (pt in pts) {
        val p = project3D(pt.second, pt.first, yaw, pitch)
        val sx = cx + p.first * R
        val sy = cy - p.second * R
        if (first) { path.moveTo(sx, sy); first = false }
        else { path.lineTo(sx, sy) }
    }
    path.close()

    drawPath(path, color = fillColor, style = Fill)
    drawPath(path, color = strokeColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

// -----------------------------------------------------------------------------
// Unified Telemetry Card with Google Earth 3D, Street View & 2D Map
// -----------------------------------------------------------------------------
@Composable
private fun UnifiedGlobeDeviceCard(
    d: MapDevice,
    onClose: () -> Unit,
    onOpenStreetView: () -> Unit,
    onOpenGoogleEarth: () -> Unit,
    onZoomToRoads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = markerColor(d.state)
    val coords = if (d.hasLocation) String.format(java.util.Locale.US, "%.5f, %.5f", d.lat, d.lng) else "26.84670, 80.94620"

    AppCard(
        modifier = modifier.fillMaxWidth(),
        container = color.copy(alpha = 0.14f),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (d.demoLocation) "Ajrara Demo Site · $coords" else "${d.name} · $coords",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(d.state.label, color)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp).padding(start = 4.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
            }
        }

        // Live Telemetry Tiles
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlobeMetricTile("TEMP", fmtTemp(d.temperature), color = StsmnColors.Safe, Modifier.weight(1f))
            GlobeMetricTile("VOLT", fmtVolt(d.voltage), color = MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            GlobeMetricTile("CURR", fmtAmp(d.current), color = MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            GlobeMetricTile("RISK", d.risk?.let { "$it/100" } ?: "--", color = color, Modifier.weight(1f))
        }

        // Action Buttons: Google Earth 3D, 2D Street Map, 360° Street View
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onOpenGoogleEarth,
                modifier = Modifier.weight(1.1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Text("🌍 Google Earth", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onZoomToRoads,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Text("🛣️ 2D Map", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onOpenStreetView,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
            ) {
                Text("📸 Street View", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun GlobeMetricTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1, softWrap = false)
        }
    }
}

// -----------------------------------------------------------------------------
// Landmarks & Grid Hub Labels
// -----------------------------------------------------------------------------
private data class MapLandmark(
    val name: String,
    val lat: Float,
    val lng: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val isCapital: Boolean = false,
    val isGridHub: Boolean = false,
)

private val MAP_LANDMARKS = listOf(
    // Country & World Metros
    MapLandmark("New Delhi", 28.61f, 77.20f, 1.8f, 10.0f, isCapital = true),
    MapLandmark("Mumbai", 19.07f, 72.87f, 1.8f, 5.5f),
    MapLandmark("Kolkata", 22.57f, 88.36f, 1.8f, 5.5f),
    MapLandmark("Bengaluru", 12.97f, 77.59f, 1.8f, 5.5f),
    MapLandmark("NEPAL", 28.39f, 84.12f, 2.0f, 5.0f),
    MapLandmark("BANGLADESH", 23.68f, 90.35f, 2.0f, 5.0f),

    // UP State & Regional Grid Nodes
    MapLandmark("⚡ Lucknow (UP Grid HQ)", 26.85f, 80.95f, 4.0f, 16.0f, isCapital = true, isGridHub = true),
    MapLandmark("⚡ Meerut (400kV Hub)", 28.98f, 77.70f, 4.5f, 16.0f, isGridHub = true),
    MapLandmark("Hapur (220kV Substation)", 28.73f, 77.78f, 6.0f, 18.0f, isGridHub = true),
    MapLandmark("Kanpur (Industrial Grid)", 26.45f, 80.33f, 4.5f, 16.0f, isGridHub = true),
    MapLandmark("Agra (220kV Hub)", 27.18f, 78.01f, 4.5f, 16.0f, isGridHub = true),
    MapLandmark("Varanasi (Eastern Grid)", 25.32f, 82.97f, 4.5f, 16.0f, isGridHub = true),
    MapLandmark("Prayagraj (Triveni Sangam)", 25.43f, 81.84f, 5.0f, 16.0f),
    MapLandmark("Ghaziabad (NCR)", 28.67f, 77.45f, 5.5f, 16.0f),
    MapLandmark("Bareilly (Northern Grid)", 28.37f, 79.43f, 5.0f, 16.0f),
    MapLandmark("Aligarh (Grid Node)", 27.89f, 78.08f, 5.5f, 16.0f),
    MapLandmark("Moradabad (Grid Node)", 28.84f, 78.78f, 5.5f, 16.0f),
)

// -----------------------------------------------------------------------------
// Power Grid Transmission Corridors (400kV & 220kV UPPTCL)
// -----------------------------------------------------------------------------
private val POWER_GRID_LINES_400KV = listOf(
    listOf(Pair(77.20f, 28.61f), Pair(77.70f, 28.98f)), // Delhi <-> Meerut
    listOf(Pair(77.70f, 28.98f), Pair(77.78f, 28.73f)), // Meerut <-> Hapur/Ajrara
    listOf(Pair(77.20f, 28.61f), Pair(78.01f, 27.18f)), // Delhi <-> Agra
    listOf(Pair(78.01f, 27.18f), Pair(80.33f, 26.45f)), // Agra <-> Kanpur
    listOf(Pair(80.33f, 26.45f), Pair(80.95f, 26.85f)), // Kanpur <-> Lucknow
    listOf(Pair(80.33f, 26.45f), Pair(81.84f, 25.43f)), // Kanpur <-> Prayagraj
    listOf(Pair(81.84f, 25.43f), Pair(82.97f, 25.32f)), // Prayagraj <-> Varanasi
)

private val POWER_GRID_LINES_220KV = listOf(
    listOf(Pair(77.78f, 28.73f), Pair(78.78f, 28.84f)), // Hapur <-> Moradabad
    listOf(Pair(78.78f, 28.84f), Pair(79.43f, 28.37f)), // Moradabad <-> Bareilly
    listOf(Pair(79.43f, 28.37f), Pair(80.95f, 26.85f)), // Bareilly <-> Lucknow
    listOf(Pair(77.20f, 28.61f), Pair(77.45f, 28.67f), Pair(77.78f, 28.73f)), // Delhi <-> Ghaziabad <-> Hapur
    listOf(Pair(78.01f, 27.18f), Pair(78.08f, 27.89f)), // Agra <-> Aligarh
)

// -----------------------------------------------------------------------------
// Rivers & Neighboring Country Borders
// -----------------------------------------------------------------------------
private val RIVER_GANGA = listOf(
    Pair(78.60f, 30.15f), Pair(78.16f, 29.95f), Pair(78.78f, 28.84f), Pair(79.50f, 27.80f),
    Pair(80.33f, 26.45f), Pair(81.84f, 25.43f), Pair(82.97f, 25.32f), Pair(85.14f, 25.60f), Pair(88.36f, 22.57f),
)

private val RIVER_YAMUNA = listOf(
    Pair(77.50f, 30.50f), Pair(77.20f, 28.61f), Pair(77.70f, 27.50f), Pair(78.01f, 27.18f),
    Pair(79.00f, 26.80f), Pair(81.84f, 25.43f),
)

private val BORDER_PAKISTAN = listOf(
    Pair(68.2f, 23.7f), Pair(71.0f, 25.0f), Pair(71.5f, 28.0f), Pair(74.0f, 31.5f), Pair(74.8f, 32.5f), Pair(74.5f, 35.0f),
)

private val BORDER_NEPAL = listOf(
    Pair(80.1f, 28.8f), Pair(81.3f, 28.2f), Pair(83.0f, 27.5f), Pair(85.2f, 27.2f), Pair(88.1f, 26.8f),
)

private val BORDER_BANGLADESH = listOf(
    Pair(88.5f, 26.3f), Pair(89.0f, 25.2f), Pair(89.0f, 22.0f), Pair(92.3f, 21.0f), Pair(92.5f, 23.7f), Pair(88.5f, 26.3f),
)

// -----------------------------------------------------------------------------
// Topographical Vector Geometries (Longitude, Latitude)
// -----------------------------------------------------------------------------
private val NATIVE_INDIA = listOf(
    Pair(68f, 24f), Pair(70f, 21f), Pair(72f, 19f), Pair(73f, 15f), Pair(76f, 9f),
    Pair(77.5f, 8f), Pair(79.8f, 11f), Pair(80f, 13f), Pair(81f, 16f), Pair(83f, 18f),
    Pair(85f, 19.5f), Pair(87f, 21.5f), Pair(90f, 22f), Pair(92f, 24f), Pair(95f, 27f),
    Pair(92f, 28f), Pair(88f, 27f), Pair(85f, 28f), Pair(80f, 30f), Pair(77f, 32f),
    Pair(75f, 35f), Pair(73f, 33f), Pair(71f, 30f), Pair(68f, 24f),
)

private val NATIVE_UP = listOf(
    Pair(77.2f, 28.5f), Pair(77.8f, 30.2f), Pair(79.2f, 29.8f), Pair(80.5f, 28.8f),
    Pair(82.2f, 27.8f), Pair(84.2f, 27.2f), Pair(84.4f, 25.4f), Pair(83.0f, 24.0f),
    Pair(81.5f, 24.8f), Pair(79.8f, 24.6f), Pair(78.4f, 25.5f), Pair(77.4f, 27.2f),
)

private val NATIVE_HIMALAYAS = listOf(
    Pair(74f, 35f), Pair(78f, 33f), Pair(82f, 30f), Pair(86f, 28f), Pair(92f, 28f),
    Pair(95f, 29f), Pair(92f, 32f), Pair(86f, 33f), Pair(80f, 35f), Pair(75f, 37f), Pair(74f, 35f),
)

private val NATIVE_ANTARCTICA = listOf(
    Pair(-180f, -75f), Pair(-140f, -74f), Pair(-100f, -72f), Pair(-60f, -65f), Pair(-40f, -73f),
    Pair(0f, -70f), Pair(40f, -68f), Pair(80f, -67f), Pair(120f, -66f), Pair(160f, -70f), Pair(180f, -75f),
)

private val NATIVE_GREENLAND = listOf(
    Pair(-45f, 60f), Pair(-35f, 65f), Pair(-20f, 70f), Pair(-18f, 76f), Pair(-25f, 81f),
    Pair(-38f, 83f), Pair(-55f, 82f), Pair(-65f, 76f), Pair(-55f, 70f), Pair(-50f, 64f), Pair(-45f, 60f),
)

private val NATIVE_DESERTS = listOf(
    listOf(
        Pair(-12f, 28f), Pair(5f, 32f), Pair(25f, 30f), Pair(32f, 28f), Pair(33f, 22f),
        Pair(30f, 18f), Pair(20f, 16f), Pair(10f, 15f), Pair(0f, 16f), Pair(-10f, 18f),
        Pair(-15f, 22f), Pair(-12f, 28f),
    ),
    listOf(
        Pair(35f, 29f), Pair(42f, 30f), Pair(48f, 30f), Pair(50f, 26f), Pair(55f, 24f),
        Pair(59f, 22f), Pair(55f, 18f), Pair(50f, 14f), Pair(44f, 13f), Pair(43f, 16f),
        Pair(40f, 20f), Pair(36f, 26f), Pair(35f, 29f),
    ),
    listOf(
        Pair(69f, 28f), Pair(73f, 29f), Pair(75f, 27f), Pair(73f, 25f), Pair(70f, 25f), Pair(69f, 28f),
    ),
    listOf(
        Pair(120f, -22f), Pair(130f, -20f), Pair(138f, -24f), Pair(138f, -30f),
        Pair(130f, -31f), Pair(122f, -28f), Pair(120f, -22f),
    ),
)

private val NATIVE_ISLANDS = listOf(
    listOf(
        Pair(-10f, 52f), Pair(-8f, 55f), Pair(-5f, 58f), Pair(-2f, 58f), Pair(1f, 53f),
        Pair(1f, 51f), Pair(-5f, 50f), Pair(-6f, 52f), Pair(-10f, 52f),
    ),
    listOf(
        Pair(130f, 31f), Pair(133f, 34f), Pair(137f, 35f), Pair(141f, 38f), Pair(141f, 43f),
        Pair(145f, 44f), Pair(142f, 41f), Pair(138f, 37f), Pair(135f, 35f), Pair(131f, 33f), Pair(130f, 31f),
    ),
    listOf(
        Pair(44f, -12f), Pair(49f, -13f), Pair(50f, -16f), Pair(48f, -22f), Pair(47f, -25f),
        Pair(44f, -25f), Pair(43f, -21f), Pair(44f, -16f), Pair(44f, -12f),
    ),
    listOf(
        Pair(95f, 5f), Pair(105f, -5f), Pair(115f, -8f), Pair(125f, -8f), Pair(135f, -5f),
        Pair(145f, -5f), Pair(140f, -2f), Pair(130f, -2f), Pair(120f, 1f), Pair(110f, 2f),
        Pair(100f, 3f), Pair(95f, 5f),
    ),
    listOf(
        Pair(79.8f, 9.5f), Pair(81.3f, 8.5f), Pair(81.8f, 7.0f), Pair(80.5f, 5.9f),
        Pair(79.7f, 7.2f), Pair(79.8f, 9.5f),
    ),
    listOf(
        Pair(172f, -35f), Pair(178f, -38f), Pair(175f, -42f), Pair(170f, -46f),
        Pair(167f, -45f), Pair(170f, -41f), Pair(172f, -35f),
    ),
)

private val NATIVE_CONTINENTS = listOf(
    // Africa
    listOf(
        Pair(-5f, 36f), Pair(10f, 37f), Pair(25f, 32f), Pair(33f, 31f), Pair(35f, 27f),
        Pair(43f, 13f), Pair(51f, 11f), Pair(48f, 4f), Pair(41f, -4f), Pair(40f, -15f),
        Pair(35f, -24f), Pair(28f, -34f), Pair(18f, -34f), Pair(15f, -28f), Pair(12f, -17f),
        Pair(8f, -4f), Pair(2f, 5f), Pair(-7f, 4f), Pair(-13f, 8f), Pair(-17f, 15f),
        Pair(-16f, 21f), Pair(-13f, 28f), Pair(-9f, 30f), Pair(-5f, 36f),
    ),
    // Europe
    listOf(
        Pair(-9f, 43f), Pair(-9f, 36f), Pair(-3f, 37f), Pair(2f, 42f), Pair(8f, 44f),
        Pair(15f, 40f), Pair(18f, 38f), Pair(24f, 35f), Pair(29f, 41f), Pair(35f, 42f),
        Pair(38f, 47f), Pair(30f, 48f), Pair(24f, 46f), Pair(15f, 46f), Pair(8f, 47f),
        Pair(-1f, 47f), Pair(-4f, 48f), Pair(-1f, 50f), Pair(4f, 52f), Pair(9f, 56f),
        Pair(10f, 60f), Pair(15f, 65f), Pair(25f, 71f), Pair(30f, 70f), Pair(40f, 67f),
        Pair(50f, 60f), Pair(40f, 52f), Pair(28f, 54f), Pair(18f, 54f), Pair(8f, 54f),
        Pair(2f, 51f), Pair(-3f, 48f), Pair(-9f, 43f),
    ),
    // Asia & Siberia
    listOf(
        Pair(40f, 67f), Pair(60f, 70f), Pair(80f, 72f), Pair(110f, 76f), Pair(140f, 73f),
        Pair(165f, 68f), Pair(178f, 65f), Pair(160f, 56f), Pair(142f, 53f), Pair(135f, 45f),
        Pair(130f, 38f), Pair(122f, 30f), Pair(118f, 24f), Pair(108f, 18f), Pair(105f, 10f),
        Pair(101f, 3f), Pair(104f, 1f), Pair(98f, 8f), Pair(98f, 16f), Pair(90f, 22f),
        Pair(83f, 18f), Pair(80f, 13f), Pair(77.5f, 8f), Pair(76f, 9f), Pair(73f, 15f),
        Pair(70f, 21f), Pair(68f, 24f), Pair(62f, 25f), Pair(56f, 26f), Pair(50f, 28f),
        Pair(44f, 20f), Pair(38f, 28f), Pair(35f, 33f), Pair(40f, 38f), Pair(50f, 40f),
        Pair(53f, 53f), Pair(50f, 60f), Pair(40f, 67f),
    ),
    // Australia
    listOf(
        Pair(115f, -32f), Pair(118f, -35f), Pair(130f, -32f), Pair(138f, -36f), Pair(145f, -38f),
        Pair(150f, -37f), Pair(153f, -27f), Pair(148f, -20f), Pair(142f, -10f), Pair(135f, -12f),
        Pair(131f, -12f), Pair(125f, -15f), Pair(115f, -22f), Pair(113f, -26f), Pair(115f, -32f),
    ),
    // North America
    listOf(
        Pair(-165f, 65f), Pair(-150f, 68f), Pair(-130f, 70f), Pair(-95f, 72f), Pair(-80f, 65f),
        Pair(-85f, 55f), Pair(-75f, 50f), Pair(-64f, 45f), Pair(-70f, 42f), Pair(-75f, 35f),
        Pair(-80f, 25f), Pair(-82f, 25f), Pair(-90f, 29f), Pair(-97f, 26f), Pair(-98f, 20f),
        Pair(-90f, 16f), Pair(-84f, 9f), Pair(-77f, 8f), Pair(-83f, 10f), Pair(-95f, 16f),
        Pair(-105f, 22f), Pair(-113f, 27f), Pair(-117f, 32f), Pair(-122f, 37f), Pair(-125f, 48f),
        Pair(-135f, 55f), Pair(-150f, 60f), Pair(-165f, 65f),
    ),
    // South America
    listOf(
        Pair(-79f, 9f), Pair(-74f, 11f), Pair(-65f, 10f), Pair(-50f, 0f), Pair(-35f, -8f),
        Pair(-38f, -15f), Pair(-43f, -23f), Pair(-50f, -30f), Pair(-58f, -34f), Pair(-65f, -42f),
        Pair(-68f, -54f), Pair(-75f, -50f), Pair(-73f, -40f), Pair(-72f, -30f), Pair(-77f, -12f),
        Pair(-80f, -2f), Pair(-79f, 9f),
    ),
)

// -----------------------------------------------------------------------------
// World City Power Grids (Night-Side Illumination)
// -----------------------------------------------------------------------------
private val WORLD_CITY_LIGHTS = listOf(
    Triple(26.85f, 80.95f, 3f), // Lucknow / Ajrara Site
    Triple(28.61f, 77.20f, 3f), // Delhi NCR
    Triple(19.07f, 72.87f, 3f), // Mumbai
    Triple(12.97f, 77.59f, 2.5f), // Bengaluru
    Triple(22.57f, 88.36f, 2.5f), // Kolkata
    Triple(17.38f, 78.48f, 2f), // Hyderabad
    Triple(13.08f, 80.27f, 2f), // Chennai
    Triple(23.02f, 72.57f, 2f), // Ahmedabad
    Triple(25.31f, 82.97f, 2f), // Varanasi
    Triple(27.18f, 78.01f, 2f), // Agra
    Triple(35.67f, 139.65f, 3f), // Tokyo
    Triple(39.90f, 116.40f, 3f), // Beijing
    Triple(31.23f, 121.47f, 3f), // Shanghai
    Triple(1.35f, 103.81f, 2.5f), // Singapore
    Triple(25.20f, 55.27f, 2.5f), // Dubai
    Triple(30.04f, 31.23f, 2.5f), // Cairo
    Triple(51.50f, -0.12f, 3f), // London
    Triple(48.85f, 2.35f, 3f), // Paris
    Triple(55.75f, 37.61f, 2.5f), // Moscow
    Triple(40.71f, -74.00f, 3f), // New York
    Triple(34.05f, -118.24f, 3f), // Los Angeles
    Triple(-23.55f, -46.63f, 3f), // Sao Paulo
    Triple(-33.86f, 151.20f, 2.5f), // Sydney
)
