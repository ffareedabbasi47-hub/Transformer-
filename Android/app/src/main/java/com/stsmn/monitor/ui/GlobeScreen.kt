package com.stsmn.monitor.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

enum class MapViewMode {
    GLOBE_3D,    // 3D Space Orbit View
    STREET_2D,   // 5-Feet Street & Road Level Map (OpenStreetMap down to building level)
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
 * Unified 3D Earth Globe + 5-Feet Street Map + Google Street View Engine.
 * - Zoomed Out: 3D Earth Globe spinning in cosmic space with stars and India/UP glowing.
 * - Zoomed In: Seamlessly transitions to 5-feet street & road level map showing every single road worldwide!
 * - Real Google Street View (360° panorama) at the transformer's location.
 * 100% Offline Capable & Native Performance.
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

    // If user switched to 2D Street & Road Map (5-feet zoom)
    if (viewMode == MapViewMode.STREET_2D) {
        Box(Modifier.fillMaxSize()) {
            MapScreen(
                devices = devices,
                fleet = fleet,
                cloudEnabled = cloudEnabled,
                selectedId = selectedId,
                onSelect = onSelect,
                onOpenSettings = onOpenSettings,
                initialDeepZoom = true,
            )

            // Floating Button to return to 3D Space Orbit
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.92f))
                    .clickable { viewMode = MapViewMode.GLOBE_3D }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🌍", fontSize = 14.sp)
                    Text(
                        "3D Space Orbit",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
        return
    }

    // ----------------- 3D Space Earth Globe Mode -----------------
    var yaw by remember { mutableFloatStateOf(80.95f) }
    var pitch by remember { mutableFloatStateOf(24.0f) }
    var zoom by remember { mutableFloatStateOf(1.0f) }
    var isSpinning by remember { mutableStateOf(true) }
    var satellite by remember { mutableStateOf(true) }

    val selected = devices.firstOrNull { it.id == selectedId }

    // Auto-Spin Loop
    LaunchedEffect(isSpinning) {
        if (!isSpinning) return@LaunchedEffect
        while (true) {
            withFrameMillis {
                yaw = (yaw + 0.22f) % 360f
            }
        }
    }

    // Animated radar wave for Ajrara / Lucknow demo site
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
        List(70) {
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
                    yaw = (yaw - pan.x * (0.35f / zoom)) % 360f
                    pitch = (pitch + pan.y * (0.35f / zoom)).coerceIn(-75f, 75f)
                    val nextZoom = (zoom * gestureZoom)
                    // If user zooms in deeply (>2.6x), automatically transition to 5-feet Street Map!
                    if (nextZoom > 2.65f) {
                        viewMode = MapViewMode.STREET_2D
                    } else {
                        zoom = nextZoom.coerceIn(0.65f, 2.65f)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onSelect(null) }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        val baseR = minOf(widthPx, heightPx) * 0.36f
        val R = baseR * zoom

        val density = LocalDensity.current

        // 1. Native 3D Hardware Canvas
        Canvas(Modifier.fillMaxSize()) {
            drawSpaceBackground(widthPx, heightPx, stars)
            drawGlobeSphere(cx, cy, R, yaw, pitch, satellite, radarPing)
        }

        // 2. Transformer Live Pins on 3D Earth
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
                                (screenX - with(density) { 45.dp.toPx() }).toInt(),
                                (screenY - with(density) { 50.dp.toPx() }).toInt(),
                            )
                        }
                        .clickable { onSelect(d.id) },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)
                                    else Color(0xFF0A1224).copy(alpha = 0.88f),
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "STSMN-${d.id.removePrefix("STSMN-")}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSelected) Color.White else Color(0xFFE2E8F0),
                            )
                        }
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

        // 3. Top Floating Status & Mode Switch Chip
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.88f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(StsmnColors.Safe))
                    Text(
                        "3D EARTH GLOBE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "• ${devices.size} TR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF94A3B8),
                    )
                }
            }

            // Quick Switch directly to Street Map (5ft Zoom)
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.90f))
                    .clickable { viewMode = MapViewMode.STREET_2D }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    "🛣️ 5ft Street Map",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        // 4. Bottom Controls & Telemetry Overlay
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Selected Transformer Card with Street View & 5ft Road Zoom
            if (selected != null) {
                UnifiedGlobeDeviceCard(
                    d = selected,
                    onClose = { onSelect(null) },
                    onOpenStreetView = {
                        val lat = selected.lat ?: 26.8467
                        val lng = selected.lng ?: 80.9462
                        openStreetView(ctx, lat, lng)
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
                    .background(Color(0xFF0F172A).copy(alpha = 0.92f))
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Button(
                    onClick = {
                        yaw = 80.95f
                        pitch = 24.0f
                        zoom = 1.0f
                        isSpinning = true
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("🌍 Orbit", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = { viewMode = MapViewMode.STREET_2D },
                    modifier = Modifier.weight(1.1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("🛣️ 5ft Roads", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = {
                        isSpinning = false
                        yaw = 80.95f
                        pitch = 26.85f
                        zoom = 2.2f
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("📍 UP Site", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = {
                        val lat = selected?.lat ?: 26.8467
                        val lng = selected?.lng ?: 80.9462
                        openStreetView(ctx, lat, lng)
                    },
                    modifier = Modifier.weight(1.1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text("📸 360° Pano", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = { isSpinning = !isSpinning },
                    modifier = Modifier.weight(0.9f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                ) {
                    Text(if (isSpinning) "⏹️" else "▶️", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }

            Text(
                "Pinch to zoom from space orbit down to 5-feet road level • Tap 360° Pano for Street View",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF64748B),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
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
// Canvas Drawing Routines
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
    isSat: Boolean,
    radarPing: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x6638BDF8), Color(0x1A38BDF8), Color(0x0038BDF8)),
            center = Offset(cx, cy),
            radius = R * 1.25f,
        ),
        radius = R * 1.25f,
        center = Offset(cx, cy),
    )

    val sphereClip = Path().apply {
        addOval(Rect(cx - R, cy - R, cx + R, cy + R))
    }

    clipPath(sphereClip) {
        drawRect(
            brush = Brush.radialGradient(
                colors = if (isSat) listOf(Color(0xFF19497D), Color(0xFF0D2F57), Color(0xFF05162B), Color(0xFF01060D))
                else listOf(Color(0xFF1B3B60), Color(0xFF0F243E), Color(0xFF06111E), Color(0xFF010408)),
                center = Offset(cx - R * 0.35f, cy - R * 0.35f),
                radius = R * 1.15f,
            ),
            topLeft = Offset(cx - R, cy - R),
            size = androidx.compose.ui.geometry.Size(R * 2, R * 2),
        )

        drawGraticule(cx, cy, R, yaw, pitch, isSat)

        val contColor = if (isSat) Color(0xFF2E654E) else Color(0xFF334155)
        val contStroke = if (isSat) Color(0xFF4ADE80) else Color(0xFF38BDF8)
        NATIVE_CONTINENTS.forEach { pts ->
            drawGlobePolygon(pts, cx, cy, R, yaw, pitch, fillColor = contColor, strokeColor = contStroke)
        }

        drawGlobePolygon(
            pts = NATIVE_INDIA,
            cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
            fillColor = if (isSat) Color(0xFF16A34A) else Color(0xFF2563EB),
            strokeColor = Color(0xFF38BDF8),
            strokeWidth = 2.4f,
        )

        drawGlobePolygon(
            pts = NATIVE_UP,
            cx = cx, cy = cy, R = R, yaw = yaw, pitch = pitch,
            fillColor = Color(0x66F59E0B),
            strokeColor = Color(0xFFFBBF24),
            strokeWidth = 2.8f,
        )

        val pBeacon = project3D(26.85f, 80.95f, yaw, pitch)
        if (pBeacon.third > 0.05f) {
            val bx = cx + pBeacon.first * R
            val by = cy - pBeacon.second * R

            val waveRadius = 6f + radarPing * 22f
            drawCircle(
                color = Color(0xFFFBBF24).copy(alpha = 1f - radarPing),
                radius = waveRadius,
                center = Offset(bx, by),
                style = Stroke(width = 2.2f),
            )

            drawCircle(color = Color(0xFFF59E0B), radius = 5f, center = Offset(bx, by))
            drawCircle(color = Color.White, radius = 2f, center = Offset(bx, by))
        }
    }

    drawCircle(
        color = Color(0xFF38BDF8).copy(alpha = 0.75f),
        radius = R,
        center = Offset(cx, cy),
        style = Stroke(width = 2.2f),
    )
}

private fun DrawScope.drawGraticule(
    cx: Float,
    cy: Float,
    R: Float,
    yaw: Float,
    pitch: Float,
    isSat: Boolean,
) {
    val gridColor = if (isSat) Color(0x3338BDF8) else Color(0x3D60A5FA)

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
        drawPath(path, color = gridColor, style = Stroke(width = 1f))
    }

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
    drawPath(eqPath, color = Color(0x66FBBF24), style = Stroke(width = 1.6f))
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
// Unified Telemetry Card with Street View & 5ft Road Zoom Buttons
// -----------------------------------------------------------------------------
@Composable
private fun UnifiedGlobeDeviceCard(
    d: MapDevice,
    onClose: () -> Unit,
    onOpenStreetView: () -> Unit,
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

        // Action Buttons: 360° Street View & 5-Feet Road Zoom
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onZoomToRoads,
                modifier = Modifier.weight(1.2f),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
            ) {
                Text("🛣️ 5ft Road Map", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onOpenStreetView,
                modifier = Modifier.weight(1.2f),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
            ) {
                Text("📸 360° Street View", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
// Vector Polygons (Longitude, Latitude)
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
