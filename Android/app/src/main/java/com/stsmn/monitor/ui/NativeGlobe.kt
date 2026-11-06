package com.stsmn.monitor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.stsmn.monitor.data.EarthTexture
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.GlobeMath
import com.stsmn.monitor.domain.MapDevice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

private const val HOME_LAT = 22.5
private const val HOME_LON = 78.96
private const val DIVE_SCALE = 2.0f

/** Where the camera is gliding to (button presses); null while the user is steering. */
private class Glide(val lat: Double, val lon: Double, val scale: Float, val seconds: Double = 1.6) {
    var t0 = -1.0
    var sLat = 0.0; var sLon = 0.0; var sScale = 1f
}

private fun easeInOut(p: Double): Double = if (p < 0.5) 4 * p * p * p else 1 - Math.pow(-2 * p + 2, 3.0) / 2

/**
 * Native 3D Earth drawn on the GPU (OpenGL ES): real satellite imagery on a sphere. Drag to spin (with momentum),
 * pinch to zoom; zooming in far enough continues into the satellite map at the same spot.
 */
@Composable
fun NativeGlobe(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    onOpenMap: (lat: Double, lon: Double, zoom: Double, deviceId: String?) -> Unit,
    onUnavailable: () -> Unit,
    startLat: Double? = null,
    startLon: Double? = null,
    startScale: Float = 1f,
) {
    val ctx = LocalContext.current
    var texture by remember { mutableStateOf<Bitmap?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val t = EarthTexture.load(ctx) { progress = it }
        if (t == null) failed = true else texture = t
    }

    val cam = remember { GlobeCam().also { c -> if (startLat != null && startLon != null) { c.lat = startLat.coerceIn(-80.0, 80.0); c.lon = startLon; c.scale = startScale } } }
    var tick by remember { mutableLongStateOf(0L) }          // changes every frame: markers follow the globe
    var moving by remember { mutableStateOf(false) }
    var glide by remember { mutableStateOf<Glide?>(null) }
    val motion = remember { doubleArrayOf(0.0, 0.0, 0.0) }   // [vLat deg/s, vLon deg/s, last gesture time (s)]
    var handedOff by remember { mutableStateOf(startScale >= DIVE_SCALE) }
    // Arriving from the flat map: start close and fly out to the full Earth, centred on where the map was looking.
    LaunchedEffect(Unit) { if (startLat != null && startScale > 1f) glide = Glide(cam.lat, cam.lon, 1f, 2.0) }
    val density = LocalDensity.current.density

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF02040A))) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()

        fun diveZoom(): Double {
            val diameter = 2.0 * globeRadiusPx(wPx, hPx, cam.scale)
            return (ln(PI * diameter / (256.0 * density)) / ln(2.0)).coerceIn(3.0, 6.0)
        }

        // One animation loop: momentum after a swipe, button glides, and the hand-off to the flat map.
        LaunchedEffect(ready) {
            var last = 0L
            var idleSince = System.nanoTime() / 1e9
            var spun = 0.0
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016 else min((now - last) / 1e9, 0.05)
                    last = now
                    val t = now / 1e9
                    val touching = t - motion[2] < 0.12
                    val g = glide
                    var busy = touching
                    if (touching) {
                        if (t - motion[2] > 0.05) { motion[0] *= 0.8; motion[1] *= 0.8 }   // finger resting: kill speed
                    } else if (g != null) {
                        if (g.t0 < 0) { g.t0 = t; g.sLat = cam.lat; g.sLon = cam.lon; g.sScale = cam.scale }
                        val p = ((t - g.t0) / g.seconds).coerceIn(0.0, 1.0)
                        val e = easeInOut(p)
                        cam.lat = g.sLat + (g.lat - g.sLat) * e
                        cam.lon = GlobeMath.normLon(g.sLon + GlobeMath.normLon(g.lon - g.sLon) * e)
                        cam.scale = (g.sScale + (g.scale - g.sScale) * e).toFloat()
                        busy = true
                        idleSince = t; spun = 99.0
                        if (p >= 1.0) glide = null
                    } else if (abs(motion[0]) > 1.5 || abs(motion[1]) > 1.5) {
                        cam.lat = (cam.lat + motion[0] * dt).coerceIn(-80.0, 80.0)
                        cam.lon = GlobeMath.normLon(cam.lon + motion[1] * dt)
                        val damp = exp(-dt * 3.2)
                        motion[0] *= damp; motion[1] *= damp
                        busy = true
                    }
                    if (touching) { idleSince = t; spun = 99.0 }                       // any touch ends the intro drift for good
                    if (!busy && spun < 10.0 && ready && t - idleSince > 5.0 && cam.scale < 1.3f) {
                        val step = 1.0 * dt                                              // a slow, natural turn (1 deg/s, at most 10 deg)
                        cam.lon = GlobeMath.normLon(cam.lon + step); spun += step
                    }
                    moving = busy
                    tick = now
                    if (handedOff && cam.scale < 1.8f) handedOff = false      // re-arm after a fly-out
                    if (!handedOff && ready && cam.scale >= DIVE_SCALE) {
                        handedOff = true
                        onOpenMap(cam.lat, cam.lon, diveZoom(), null)
                    }
                }
            }
        }

        // The GPU globe.
        val owner = LocalLifecycleOwner.current
        var surface by remember { mutableStateOf<GlobeSurface?>(null) }
        DisposableEffect(owner, surface) {
            val s = surface
            val obs = LifecycleEventObserver { _, e ->
                when (e) { Lifecycle.Event.ON_RESUME -> s?.onResume(); Lifecycle.Event.ON_PAUSE -> s?.onPause(); else -> Unit }
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs); s?.onPause() }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                GlobeSurface(c, cam, onFailure = { failed = true }, onReady = { ready = true }).also { surface = it }
            },
            update = { v -> texture?.let { v.setTexture(it) } },
        )

        // Touch layer (above the GPU view): double-tap flies closer, drag spins, pinch zooms.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { glide = Glide(cam.lat, cam.lon, (cam.scale * 1.5f).coerceAtMost(DIVE_SCALE + 0.1f), 0.7) })
            }.pointerInput(Unit) {
                var lastEvent = 0L
                detectTransformGestures { _, pan, zoom, _ ->
                    glide = null
                    val now = System.nanoTime()
                    val dtS = ((now - lastEvent) / 1e9).coerceIn(0.008, 0.1)
                    lastEvent = now
                    val r = globeRadiusPx(size.width.toFloat(), size.height.toFloat(), cam.scale).toDouble()
                    val moved = GlobeMath.dragCenter(cam.lat, cam.lon, pan.x.toDouble(), pan.y.toDouble(), r)
                    val dLat = moved.first - cam.lat
                    val dLon = GlobeMath.normLon(moved.second - cam.lon)
                    cam.lat = moved.first
                    cam.lon = moved.second
                    cam.scale = (cam.scale * zoom).coerceIn(0.6f, 2.4f)
                    motion[0] = motion[0] * 0.5 + (dLat / dtS) * 0.5
                    motion[1] = motion[1] * 0.5 + (dLon / dtS) * 0.5
                    motion[2] = now / 1e9
                }
            },
        )

        // Transformer markers: positions come from the same camera as the GPU picture, so they stay glued to the ground.
        if (ready) {
            devices.filter { it.hasLocation }.forEach { d ->
                val la = d.lat!!
                val lo = d.lng!!
                Box(
                    Modifier
                        .offset {
                            tick
                            val s = GlobeMath.project(la, lo, cam.lat, cam.lon)
                            val r = globeRadiusPx(wPx, hPx, cam.scale)
                            val half = 22.dp.toPx()
                            IntOffset((wPx / 2f + (s.x * r).toFloat() - half).roundToInt(), (hPx / 2f - (s.y * r).toFloat() - half).roundToInt())
                        }
                        .size(44.dp)
                        .graphicsLayer {
                            tick
                            alpha = if (GlobeMath.project(la, lo, cam.lat, cam.lon).visible) 1f else 0f
                        }
                        .clickable {
                            if (GlobeMath.project(la, lo, cam.lat, cam.lon).visible) onOpenMap(la, lo, 11.0, d.id)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(16.dp).clip(CircleShape).background(markerColor(d.state)).border(2.dp, Color.White, CircleShape))
                        if (!moving) Text(
                            d.label, fontSize = 9.sp, color = Color.White, maxLines = 1, softWrap = false,
                            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
                        )
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryBar(devices, fleet, cloudEnabled, title = "3D EARTH")
        }

        when {
            failed -> Column(
                Modifier.align(Alignment.Center).padding(24.dp).panel(16.dp, 0.95f).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("The 3D Earth could not start (needs internet the first time, and OpenGL).", color = MaterialTheme.colorScheme.onSurface)
                Button(onClick = onUnavailable) { Text("Open satellite map") }
            }
            !ready -> Column(
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
                if (!moving) Text("Drag to spin · pinch or double-tap to zoom", fontSize = 11.sp, color = Color(0xCCFFFFFF))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { glide = Glide(HOME_LAT, HOME_LON, 1f, 1.8) }) { Text("India", maxLines = 1, softWrap = false) }
                    Button(onClick = {
                        val site = devices.firstOrNull { it.hasLocation }
                        glide = if (site != null) Glide(site.lat!!, site.lng!!, DIVE_SCALE + 0.1f, 2.6) else Glide(cam.lat, cam.lon, DIVE_SCALE + 0.1f, 1.4)
                    }) { Text(if (devices.any { it.hasLocation }) "Fly to site" else "Zoom in", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}
