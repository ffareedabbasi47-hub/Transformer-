package com.stsmn.monitor.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice
import com.stsmn.monitor.domain.MarkerState
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

fun markerColor(state: MarkerState): Color = Color(markerColorInt(state))

/** osmdroid setup: no API key; tiles are cached in app storage so a visited area works offline. */
private object MapConfig {
    private var done = false
    fun ensure(ctx: Context) {
        if (done) return
        val base = File(ctx.filesDir, "osmdroid").also { it.mkdirs() }
        Configuration.getInstance().apply {
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
            userAgentValue = ctx.packageName // required by the OpenStreetMap tile usage policy
            tileFileSystemCacheMaxBytes = AppConfig.MAP_TILE_CACHE_BYTES
            tileFileSystemCacheTrimBytes = AppConfig.MAP_TILE_CACHE_BYTES * 3 / 4
        }
        done = true
    }
}

private fun createMapView(ctx: Context, onFirstLayout: () -> Unit): MapView {
    MapConfig.ensure(ctx)
    return MapView(ctx).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        setTilesScaledToDpi(true)
        setMinZoomLevel(AppConfig.MAP_MIN_ZOOM)
        setMaxZoomLevel(AppConfig.MAP_MAX_ZOOM)
        controller.setZoom(AppConfig.MAP_INITIAL_ZOOM)
        controller.setCenter(GeoPoint(AppConfig.UP_CENTER_LAT, AppConfig.UP_CENTER_LNG))
        addOnFirstLayoutListener { _, _, _, _, _ -> onFirstLayout() }
    }
}

/**
 * Keeps ONE osmdroid Marker per transformer and updates it in place. Work happens only when something
 * visible changed (position, state, label, selection), so a 1-second UI tick costs nothing.
 */
private class MarkerSet {
    private val markers = HashMap<String, Marker>()
    private val signatures = HashMap<String, String>()

    fun sync(map: MapView, devices: List<MapDevice>, selectedId: String?, onTap: (String) -> Unit) {
        var changed = false
        val wanted = devices.filter { it.hasLocation }.associateBy { it.id }
        for (id in markers.keys.toList()) if (id !in wanted) {
            map.overlays.remove(markers.remove(id)); signatures.remove(id); changed = true
        }
        for ((id, d) in wanted) {
            val sig = "${d.lat}|${d.lng}|${d.label}|${d.state.name}|${d.demoLocation}|${id == selectedId}"
            if (signatures[id] == sig) continue
            val marker = markers.getOrPut(id) { Marker(map).also { map.overlays.add(it) } }
            val icon = MarkerIcons.get(
                map.context, d.label, d.state.label, if (d.demoLocation) "DEMO LOCATION" else null, markerColorInt(d.state), id == selectedId,
            )
            marker.position = GeoPoint(d.lat!!, d.lng!!)
            marker.setIcon(icon.drawable)
            marker.setAnchor(Marker.ANCHOR_CENTER, icon.anchorY)
            marker.setInfoWindow(null)
            marker.setOnMarkerClickListener { _, _ -> onTap(id); true }
            signatures[id] = sig
            changed = true
        }
        if (changed) map.invalidate()
    }

    fun clear() { markers.clear(); signatures.clear() }
}

@Composable
internal fun rememberHasInternet(): State<Boolean> {
    val ctx = LocalContext.current
    return produceState(initialValue = true) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        while (true) {
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            value = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            delay(AppConfig.MAP_INTERNET_CHECK_MS)
        }
    }
}

private fun fitAll(map: MapView, devices: List<MapDevice>) {
    val pts = devices.filter { it.hasLocation }.map { GeoPoint(it.lat!!, it.lng!!) }
    when {
        pts.isEmpty() -> map.controller.animateTo(GeoPoint(AppConfig.UP_CENTER_LAT, AppConfig.UP_CENTER_LNG), AppConfig.MAP_INITIAL_ZOOM, 600L)
        pts.size == 1 -> map.controller.animateTo(pts[0], AppConfig.MAP_CENTER_ZOOM, 600L)
        else -> {
            // Transformers at (almost) the same spot give a zero-size box: centre on them instead of zooming into nothing.
            val latSpan = pts.maxOf { it.latitude } - pts.minOf { it.latitude }
            val lngSpan = pts.maxOf { it.longitude } - pts.minOf { it.longitude }
            if (latSpan < 0.0005 && lngSpan < 0.0005) map.controller.animateTo(pts[0], AppConfig.MAP_CENTER_ZOOM, 600L)
            else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 120)
        }
    }
}

@Composable
fun MapScreen(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    initialDeepZoom: Boolean = false,
) {
    val ctx = LocalContext.current
    val hasInternet by rememberHasInternet()
    var mapReady by remember { mutableStateOf(false) }
    val mapResult = remember { runCatching { createMapView(ctx) { mapReady = true } } }
    val mapView = mapResult.getOrNull()
    val markerSet = remember { MarkerSet() }
    val selected = devices.firstOrNull { it.id == selectedId }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView?.onPause()
            mapView?.onDetach() // release tile threads/overlays: the map is rebuilt when the tab is opened again
            markerSet.clear()
        }
    }

    // Map failed to start: live data still works through the list card.
    if (mapView == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("Live Map", "${devices.size} transformer(s)")
            AppCard(container = StsmnColors.Emergency.copy(alpha = 0.14f)) {
                Text("Map could not be loaded", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
                Text(
                    "${mapResult.exceptionOrNull()?.message ?: "Unknown error"}. Live data, alerts and the ESP32's protection are not affected.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            devices.forEach { DeviceCard(it, onClose = null) }
        }
        return
    }

    // Focus the camera when a device is picked (from a marker or from the Fleet tab).
    DisposableEffectKey(selected?.id, mapReady, initialDeepZoom) {
        if (mapReady && selected != null && selected.hasLocation) {
            val targetZoom = if (initialDeepZoom) AppConfig.MAP_MAX_ZOOM else maxOf(mapView.zoomLevelDouble, AppConfig.MAP_CENTER_ZOOM)
            mapView.controller.animateTo(GeoPoint(selected.lat!!, selected.lng!!), targetZoom, 600L)
        }
    }
    // If deep zoom is requested without a selection, center onto first transformer or demo site at 5-feet level
    var initialDeepDone by remember { mutableStateOf(false) }
    DisposableEffectKey(mapReady, initialDeepZoom) {
        if (mapReady && initialDeepZoom && !initialDeepDone) {
            val target = devices.firstOrNull { it.hasLocation }?.let { GeoPoint(it.lat!!, it.lng!!) }
                ?: GeoPoint(AppConfig.DEMO_LAT, AppConfig.DEMO_LNG)
            mapView.controller.animateTo(target, AppConfig.MAP_MAX_ZOOM, 600L)
            initialDeepDone = true
        }
    }
    // Zoom to the whole fleet once, when the first positions are known (only if not deep zooming).
    var fitted by remember { mutableStateOf(false) }
    DisposableEffectKey(mapReady, devices.count { it.hasLocation }, fitted) {
        if (mapReady && !fitted && selectedId == null && !initialDeepZoom && devices.any { it.hasLocation }) {
            fitAll(mapView, devices); fitted = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { m -> markerSet.sync(m, devices, selectedId) { id -> onSelect(id) } },
        )

        // ---------------- Top: one compact status row ----------------
        Column(Modifier.align(Alignment.TopCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryBar(devices, fleet, cloudEnabled)
            if (cloudEnabled && fleet.cloud != CloudState.ONLINE) {
                Banner(
                    when (fleet.cloud) {
                        CloudState.AUTH_FAILED -> "Viewer key rejected by the server – check Settings → Realtime cloud"
                        CloudState.OFF -> "Realtime cloud is off"
                        else -> "Reconnecting to the realtime server… markers show last known state (STALE)"
                    }, StsmnColors.Warning,
                )
            }
            if (!hasInternet) Banner("No internet: map background may be blank (cached tiles only). Markers still work.", StsmnColors.Info)
        }

        if (!mapReady) {
            Text("Loading map…", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        }

        if (devices.none { it.hasLocation }) {
            AppCard(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("No transformer positions yet", fontWeight = FontWeight.Bold)
                Text(
                    if (cloudEnabled) "Positions appear as soon as a transformer is registered with coordinates on the server, " +
                        "or enter this transformer's site coordinates in Settings."
                    else "Enter the site coordinates in Settings, or turn on the realtime cloud to see every transformer.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }

        // ---------------- Floating 5ft & Road Zoom Controls ----------------
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { mapView.controller.zoomIn() },
                contentAlignment = Alignment.Center,
            ) {
                Text("➕", fontSize = 16.sp)
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { mapView.controller.zoomOut() },
                contentAlignment = Alignment.Center,
            ) {
                Text("➖", fontSize = 16.sp)
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
                    .clickable {
                        val pt = selected?.let { GeoPoint(it.lat ?: AppConfig.DEMO_LAT, it.lng ?: AppConfig.DEMO_LNG) }
                            ?: devices.firstOrNull { it.hasLocation }?.let { GeoPoint(it.lat!!, it.lng!!) }
                            ?: GeoPoint(AppConfig.DEMO_LAT, AppConfig.DEMO_LNG)
                        mapView.controller.animateTo(pt, AppConfig.MAP_MAX_ZOOM, 600L)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("🛣️", fontSize = 16.sp)
            }
        }

        // ---------------- Bottom: buttons, selected-device card ----------------
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Legend()
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { fitAll(mapView, devices) }, enabled = devices.any { it.hasLocation }) { Text("Show all") }
                    OutlinedButton(
                        onClick = { selected?.let { s -> if (s.hasLocation) mapView.controller.animateTo(GeoPoint(s.lat!!, s.lng!!), AppConfig.MAP_MAX_ZOOM, 600L) } },
                        enabled = selected?.hasLocation == true,
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    ) { Text("Center") }
                }
            }
            if (selected != null) {
                DeviceCard(selected, onClose = { onSelect(null) }, modifier = Modifier.heightIn(max = 340.dp))
            }
            Text(
                "© OpenStreetMap contributors",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** Runs [block] whenever [key1]/[key2]/[key3] change (small wrapper so the effects above read clearly). */
@Composable
private fun DisposableEffectKey(key1: Any?, key2: Any?, key3: Any? = null, block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key1, key2, key3) { block() }
}

@Composable
internal fun SummaryBar(devices: List<MapDevice>, fleet: FleetState, cloudEnabled: Boolean, title: String = "LIVE MAP") {
    val emergencies = devices.count { it.state == MarkerState.EMERGENCY }
    val warnings = devices.count { it.state == MarkerState.WARNING }
    val safe = devices.count { it.state == MarkerState.SAFE }
    val off = devices.count { it.state == MarkerState.OFFLINE || it.state == MarkerState.STALE }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
        Count(emergencies, MarkerState.EMERGENCY); Count(warnings, MarkerState.WARNING)
        Count(safe, MarkerState.SAFE); Count(off, MarkerState.OFFLINE)
        if (cloudEnabled) CloudChip(fleet.cloud, fleet.publishing)
    }
}

@Composable
internal fun Count(n: Int, state: MarkerState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.alpha(if (n == 0) 0.45f else 1f)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(markerColor(state)))
        Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Banner(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun Legend() {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendRow(MarkerState.SAFE, "SAFE"); LegendRow(MarkerState.WARNING, "WARNING")
        LegendRow(MarkerState.EMERGENCY, "EMERGENCY"); LegendRow(MarkerState.OFFLINE, "OFFLINE / STALE")
    }
}

@Composable
private fun LegendRow(state: MarkerState, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(markerColor(state)))
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Detail card for one transformer. Old readings are clearly marked as "last known", never live. */
@Composable
fun DeviceCard(d: MapDevice, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
    val color = markerColor(d.state)
    val na = "—"
    val coords = if (d.hasLocation) String.format(java.util.Locale.US, "%.5f, %.5f", d.lat, d.lng) else "not set"
    AppCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(d.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(d.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(d.state.label, color)
            if (onClose != null) TextButton(onClick = onClose) { Text("Close") }
        }
        if (d.hasLocation) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { openGoogleEarth(ctx, d.lat!!, d.lng!!) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("🌍 Google Earth")
                }
                OutlinedButton(
                    onClick = { openStreetView(ctx, d.lat!!, d.lng!!) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("📸 Street View")
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            InfoRow("Source", if (d.isLocal) "This phone (direct ESP32)" else "Realtime server")
            InfoRow("Location", if (d.demoLocation) "$coords (DEMO)" else coords)
            InfoRow("Last update", fmtAge(d.ageMs))
            if (!d.isLive) {
                Text(
                    if (d.status != null) "Not live. Values below are the LAST KNOWN readings." else "No data received from this transformer yet.",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StsmnColors.Warning, modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            Column(Modifier.alpha(if (d.isLive) 1f else 0.45f)) {
                InfoRow("Status", d.status?.name ?: na)
                InfoRow("Risk score", d.risk?.let { "$it / 100" } ?: na)
                InfoRow("Temperature", if (d.status != null) fmtTemp(d.temperature) else na)
                InfoRow("Voltage", if (d.status != null) fmtVolt(d.voltage) else na)
                InfoRow("Current", if (d.status != null) fmtAmp(d.current) else na)
                InfoRow("Smoke", d.smoke?.let { if (it) "DETECTED" else "Normal" } ?: na)
                InfoRow("Flame", d.flame?.let { if (it) "DETECTED" else "None" } ?: na)
                InfoRow("Oil level (demo)", if (d.status != null) fmtOil(d.oilLevel) else na)
                InfoRow("Relay", d.relay?.let { if (it) "ON (supply allowed)" else "OFF (cutoff)" } ?: na)
                InfoRow("Alarm", d.alarm?.let { if (it) "ON" else "OFF" } ?: na)
            }
        }
    }
}
