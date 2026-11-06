package com.stsmn.monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.data.EarthTexture
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.GlobeMath
import com.stsmn.monitor.domain.MapDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val HOME_LAT = 22.5
private const val HOME_LON = 78.96

/**
 * Native 3D Earth: a real satellite picture wrapped on a sphere and drawn by our own code (no WebView, no WebGL),
 * so it works on every phone. Drag to rotate, pinch or tap "Zoom in" to dive into the satellite map.
 */
@Composable
fun NativeGlobe(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    onOpenMap: (lat: Double, lon: Double, zoom: Double, deviceId: String?) -> Unit,
    onUnavailable: () -> Unit,
) {
    val ctx = LocalContext.current
    var texture by remember { mutableStateOf<IntArray?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val t = EarthTexture.load(ctx) { progress = it }
        if (t == null) failed = true else texture = t
    }

    var centerLat by remember { mutableDoubleStateOf(HOME_LAT) }
    var centerLon by remember { mutableDoubleStateOf(HOME_LON) }
    var scale by remember { mutableFloatStateOf(1f) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val stars = remember {
        val rnd = Random(7)
        List(140) { Triple(rnd.nextFloat(), rnd.nextFloat(), 0.25f + rnd.nextFloat() * 0.6f) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF02040A))) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val baseDiameter = min(wPx, hPx) * 0.9f
        val diameter = baseDiameter * scale
        val radius = diameter / 2f
        val cx = wPx / 2f
        val cy = hPx / 2f
        val renderSize = min(576, maxOf(240, baseDiameter.toInt()))

        // Re-draw the sphere whenever the view moves; a newer position cancels an older, still running render.
        LaunchedEffect(texture, renderSize) {
            val tex = texture ?: return@LaunchedEffect
            snapshotFlow { centerLat to centerLon }.collectLatest { (lat, lon) ->
                frame = withContext(Dispatchers.Default) { renderSphere(tex, renderSize, lat, lon, this) }
            }
        }
        // Pinching far enough dives into the satellite map at the current position.
        LaunchedEffect(scale) {
            if (scale >= 1.9f) onOpenMap(centerLat, centerLon, 4.0, null)
        }

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val r = min(size.width, size.height) * 0.45f * scale
                    val moved = GlobeMath.dragCenter(centerLat, centerLon, pan.x.toDouble(), pan.y.toDouble(), r.toDouble())
                    centerLat = moved.first
                    centerLon = moved.second
                    scale = (scale * zoom).coerceIn(0.6f, 2.2f)
                }
            },
        ) {
            stars.forEach { (sx, sy, a) -> drawCircle(Color.White.copy(alpha = a), radius = 1.2f, center = Offset(sx * size.width, sy * size.height)) }
            drawCircle(
                brush = Brush.radialGradient(
                    0.78f to Color(0x773D8BFF), 1.0f to Color.Transparent,
                    center = Offset(cx, cy), radius = radius * 1.3f,
                ),
                radius = radius * 1.3f, center = Offset(cx, cy),
            )
        }
        frame?.let {
            Image(
                bitmap = it, contentDescription = "3D Earth", filterQuality = FilterQuality.Medium,
                modifier = Modifier.align(Alignment.Center).size(with(density) { diameter.toDp() }),
            )
        }

        // Transformer markers on the visible hemisphere.
        if (frame != null) {
            devices.filter { it.hasLocation }.forEach { d ->
                val s = GlobeMath.project(d.lat!!, d.lng!!, centerLat, centerLon)
                if (s.visible) {
                    val px = cx + (s.x * radius).toFloat()
                    val py = cy - (s.y * radius).toFloat()
                    val half = with(density) { 22.dp.toPx() }
                    Column(
                        Modifier.offset { IntOffset((px - half).roundToInt(), (py - half).roundToInt()) }
                            .size(44.dp).clickable { onOpenMap(d.lat!!, d.lng!!, 11.0, d.id) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(markerColor(d.state)).border(2.dp, Color.White, CircleShape))
                        Text(d.label, fontSize = 9.sp, color = Color.White, modifier = Modifier.background(Color(0x99000000)).padding(horizontal = 3.dp))
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryBar(devices, fleet, cloudEnabled, title = "3D EARTH")
        }

        when {
            failed -> Column(
                Modifier.align(Alignment.Center).padding(24.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Earth imagery could not be downloaded (internet needed the first time).", color = MaterialTheme.colorScheme.onSurface)
                Button(onClick = onUnavailable) { Text("Open satellite map") }
            }
            frame == null -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Loading Earth imagery… $progress/${EarthTexture.TILE_COUNT}", color = Color.White)
                LinearProgressIndicator(progress = { progress / EarthTexture.TILE_COUNT.toFloat() }, modifier = Modifier.size(width = 220.dp, height = 4.dp))
            }
            else -> Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Drag to rotate · pinch or tap Zoom in", fontSize = 11.sp, color = Color(0xCCFFFFFF))
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { centerLat = HOME_LAT; centerLon = HOME_LON; scale = 1f }) { Text("India", maxLines = 1, softWrap = false) }
                    Button(onClick = { onOpenMap(centerLat, centerLon, 5.0, null) }) { Text("Zoom in", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** Draws the textured sphere as ARGB pixels for the given view centre. Runs off the main thread; cancellable. */
private fun renderSphere(tex: IntArray, size: Int, latDeg: Double, lonDeg: Double, scope: CoroutineScope): ImageBitmap {
    val ts = EarthTexture.SIZE
    val out = IntArray(size * size)
    val phi0 = Math.toRadians(latDeg)
    val sinP = sin(phi0)
    val cosP = cos(phi0)
    val inv = 2.0 / size
    val quarterPi = 4.0 * PI
    for (j in 0 until size) {
        scope.ensureActive()
        val y = 1.0 - (j + 0.5) * inv
        for (i in 0 until size) {
            val x = (i + 0.5) * inv - 1.0
            val r2 = x * x + y * y
            if (r2 >= 1.0) continue
            val z = sqrt(1.0 - r2)
            var s = z * sinP + y * cosP
            if (s > 0.99999) s = 0.99999 else if (s < -0.99999) s = -0.99999
            val lon = lonDeg + Math.toDegrees(atan2(x, z * cosP - y * sinP))
            var u = (lon + 180.0) / 360.0
            u -= floor(u)
            var v = 0.5 - ln((1.0 + s) / (1.0 - s)) / quarterPi
            if (v < 0.0) v = 0.0 else if (v > 0.9999) v = 0.9999
            val c = tex[(v * ts).toInt() * ts + (u * ts).toInt().coerceAtMost(ts - 1)]
            val shade = 0.6 + 0.4 * sqrt(z)                        // darker towards the rim: reads as a sphere
            val rim = 1.0 - z
            val glow = rim * rim * rim * 0.6                        // thin atmosphere on the edge
            val r = (((c shr 16) and 255) * shade).let { it + (90 - it) * glow }
            val g = (((c shr 8) and 255) * shade).let { it + (160 - it) * glow }
            val b = ((c and 255) * shade).let { it + (255 - it) * glow }
            var a = 255
            if (r2 > 0.99) a = (255 * ((1.0 - r2) / 0.01)).toInt().coerceIn(0, 255)   // smooth edge
            out[j * size + i] = (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
        }
    }
    val bmp = android.graphics.Bitmap.createBitmap(out, size, size, android.graphics.Bitmap.Config.ARGB_8888)
    return bmp.asImageBitmap()
}
