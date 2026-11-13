package com.stsmn.monitor.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.rememberUpdatedState
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
import com.stsmn.monitor.domain.BaseLayer
import com.stsmn.monitor.domain.Clustering
import com.stsmn.monitor.domain.MapLayers
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice
import com.stsmn.monitor.domain.MarkerState
import com.stsmn.monitor.domain.TileProbe
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

fun markerColor(state: MarkerState): Color = Color(markerColorInt(state))

private object MapConfig {
    private var done = false
    fun ensure(ctx: Context) {
        if (done) return
        val base = File(ctx.filesDir, "osmdroid").also { it.mkdirs() }
        Configuration.getInstance().apply {
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
            userAgentValue = ctx.packageName
            tileFileSystemCacheMaxBytes = AppConfig.MAP_TILE_CACHE_BYTES
            tileFileSystemCacheTrimBytes = AppConfig.MAP_TILE_CACHE_BYTES * 3 / 4
        }
        done = true
    }
}

private fun esriSource(name: String, service: String, ext: String, maxZoom: Int) = object : OnlineTileSourceBase(
    name, 0, maxZoom, 256, ext, arrayOf(MapLayers.esriBase(service)),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

private val ESRI_SATELLITE = esriSource("EsriWorldImagery", MapLayers.IMAGERY_SERVICE, ".jpg", 19)
private val ESRI_TERRAIN = esriSource("EsriWorldTopo", MapLayers.TERRAIN_SERVICE, ".jpg", 19)
private val ESRI_ROADS = esriSource("EsriRoads", MapLayers.ROADS_SERVICE, ".png", 19)

private fun sourceFor(base: BaseLayer) = when (base) {
    BaseLayer.SATELLITE -> ESRI_SATELLITE
    BaseLayer.TERRAIN -> ESRI_TERRAIN
    BaseLayer.STREETS -> TileSourceFactory.MAPNIK
}

private fun createMapView(ctx: Context, onFirstLayout: () -> Unit): MapView {
    MapConfig.ensure(ctx)
    return MapView(ctx).apply {
        setTileSource(ESRI_SATELLITE)
        overlayManager.tilesOverlay.setLoadingBackgroundColor(0xFF0B3D6E.toInt())
        overlayManager.tilesOverlay.setLoadingLineColor(0xFF0B3D6E.toInt())
        setMultiTouchControls(true)
        setTilesScaledToDpi(true)
        setMinZoomLevel(AppConfig.MAP_MIN_ZOOM)
        setMaxZoomLevel(AppConfig.MAP_MAX_ZOOM)
        controller.setZoom(AppConfig.MAP_INITIAL_ZOOM)
        controller.setCenter(GeoPoint(AppConfig.UP_CENTER_LAT, AppConfig.UP_CENTER_LNG))
        addOnFirstLayoutListener { _, _, _, _, _ -> onFirstLayout() }
    }
}

private class Wanted(val sig: String, val lat: Double, val lng: Double, val icon: () -> MarkerIcon, val tap: () -> Unit)

private fun clusterIcon(ctx: Context, n: Int, color: Int): MarkerIcon {
    val d = ctx.resources.displayMetrics.density
    val size = (46 * d).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f - 2 * d
    p.color = color; c.drawCircle(size / 2f, size / 2f, r, p)
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = android.graphics.Color.WHITE
    c.drawCircle(size / 2f, size / 2f, r, p)
    p.style = android.graphics.Paint.Style.FILL; p.textSize = 16 * d; p.textAlign = android.graphics.Paint.Align.CENTER; p.isFakeBoldText = true
    c.drawText(n.toString(), size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
    return MarkerIcon(android.graphics.drawable.BitmapDrawable(ctx.resources, bmp), 0.5f)
}

private class MarkerSet {
    private val markers = HashMap<String, Marker>()
    private val signatures = HashMap<String, String>()

    fun sync(map: MapView, devices: List<MapDevice>, selectedId: String?, zoom: Double, onTap: (String) -> Unit) {
        val density = map.context.resources.displayMetrics.density.toDouble()
        val located = devices.filter { it.hasLocation }
        val byId = located.associateBy { it.id }
        val wanted = LinkedHashMap<String, Wanted>()

        fun single(d: MapDevice) {
            wanted["d:" + d.id] = Wanted(
                "${d.lat}|${d.lng}|${d.label}|${d.state.name}|${d.demoLocation}|${d.id == selectedId}", d.lat!!, d.lng!!,
                { MarkerIcons.get(map.context, d.label, d.state.label, if (d.demoLocation) "DEMO LOCATION" else null, markerColorInt(d.state), d.id == selectedId) },
                { onTap(d.id) },
            )
        }
        located.firstOrNull { it.id == selectedId }?.let { single(it) }
        val items = located.filter { it.id != selectedId }.map { Clustering.Item(it.id, it.lat!!, it.lng!!, it.state) }
        for (c in Clustering.cluster(items, zoom, 256.0 * density, 56.0 * density)) {
            if (c.size == 1) { single(byId.getValue(c.members[0].id)); continue }
            val key = "c:" + c.members.joinToString(",") { it.id }
            val worst = c.worst
            wanted[key] = Wanted(
                "$key|${worst.name}|${c.lat}|${c.lng}", c.lat, c.lng,
                { clusterIcon(map.context, c.size, markerColorInt(worst)) },
                {
                    val pts = c.members.map { GeoPoint(it.lat, it.lng) }
                    val span = maxOf(pts.maxOf { it.latitude } - pts.minOf { it.latitude }, pts.maxOf { it.longitude } - pts.minOf { it.longitude })
                    if (span < 0.00002) map.controller.animateTo(GeoPoint(c.lat, c.lng), minOf(map.zoomLevelDouble + 3.0, AppConfig.MAP_MAX_ZOOM), 500L)
                    else map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 140)
                },
            )
        }

        var changed = false
        for (key in markers.keys.toList()) if (key !in wanted) {
            map.overlays.remove(markers.remove(key)); signatures.remove(key); changed = true
        }
        for ((key, w) in wanted) {
            if (signatures[key] == w.sig) continue
            val marker = markers.getOrPut(key) { Marker(map).also { map.overlays.add(it) } }
            val icon = w.icon()
            marker.position = GeoPoint(w.lat, w.lng)
            marker.setIcon(icon.drawable)
            marker.setAnchor(Marker.ANCHOR_CENTER, icon.anchorY)
            marker.setInfoWindow(null)
            marker.setOnMarkerClickListener { _, _ -> w.tap(); true }
            signatures[key] = w.sig
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
    startLat: Double? = null,
    startLng: Double? = null,
    startZoom: Double? = null,
    onCamera: (lat: Double, lon: Double, zoom: Double) -> Unit = { _, _, _ -> },
) {
    val ctx = LocalContext.current
    val hasInternet by rememberHasInternet()
    var mapReady by remember { mutableStateOf(false) }
    val mapResult = remember { runCatching { createMapView(ctx) { mapReady = true } } }
    val mapView = mapResult.getOrNull()
    val markerSet = remember { MarkerSet() }
    val selected = devices.firstOrNull { it.id == selectedId }
    var base by remember { mutableStateOf(BaseLayer.SATELLITE) }
    var roads by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var sheetExpanded by remember { mutableStateOf(false) }
    val zoomState = remember { mutableDoubleStateOf(AppConfig.MAP_INITIAL_ZOOM) }

    var probe by remember { mutableStateOf<TileProbe.Result?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val r = TileProbe.run(ctx.packageName)
            probe = r
            if (r.any) break
            delay(15_000)
        }
    }
    LaunchedEffect(probe) { if (probe?.choice() == TileProbe.Choice.STREETS) base = BaseLayer.STREETS }
    androidx.compose.runtime.LaunchedEffect(base, mapView) {
        mapView?.setTileSource(sourceFor(base))
        mapView?.invalidate()
    }

    val roadsOverlay = remember { arrayOfNulls<TilesOverlay>(1) }
    LaunchedEffect(roads, mapView) {
        val m = mapView ?: return@LaunchedEffect
        val existing = roadsOverlay[0]
        if (roads && existing == null) {
            val provider = MapTileProviderBasic(m.context.applicationContext)
            provider.tileSource = ESRI_ROADS
            val o = TilesOverlay(provider, m.context)
            o.setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT)
            o.setLoadingLineColor(android.graphics.Color.TRANSPARENT)
            m.overlays.add(0, o)
            roadsOverlay[0] = o
        } else if (!roads && existing != null) {
            m.overlays.remove(existing); existing.onDetach(m); roadsOverlay[0] = null
        }
        m.invalidate()
    }
    val cameraCallback by rememberUpdatedState(onCamera)

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
            mapView?.onDetach()
            roadsOverlay[0]?.let { o -> mapView?.let { m -> m.overlays.remove(o); o.onDetach(m) }; roadsOverlay[0] = null }
            markerSet.clear()
        }
    }

    if (mapView == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = LocalBottomInset.current),
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

    DisposableEffect(mapView) {
        fun report() { val c = mapView.mapCenter; cameraCallback(c.latitude, c.longitude, mapView.zoomLevelDouble) }
        val l = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { report(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { zoomState.doubleValue = mapView.zoomLevelDouble; report(); return false }
        }
        mapView.addMapListener(l)
        onDispose { mapView.removeMapListener(l) }
    }

    DisposableEffectKey(selected?.id, mapReady, initialDeepZoom) {
        if (mapReady && selected != null && selected.hasLocation) {
            val targetZoom = if (initialDeepZoom) AppConfig.MAP_MAX_ZOOM
                             else if (mapView.zoomLevelDouble < AppConfig.MAP_INITIAL_ZOOM) AppConfig.MAP_CENTER_ZOOM
                             else mapView.zoomLevelDouble
            mapView.controller.animateTo(GeoPoint(selected.lat!!, selected.lng!!), targetZoom, 600L)
        }
    }

    var initialDeepDone by remember { mutableStateOf(false) }
    DisposableEffectKey(mapReady, initialDeepZoom) {
        if (mapReady && initialDeepZoom && !initialDeepDone) {
            val target = devices.firstOrNull { it.hasLocation }?.let { GeoPoint(it.lat!!, it.lng!!) }
                ?: GeoPoint(AppConfig.DEMO_LAT, AppConfig.DEMO_LNG)
            mapView.controller.animateTo(target, AppConfig.MAP_MAX_ZOOM, 600L)
            initialDeepDone = true
        }
    }

    val hasStart = startLat != null && startLng != null
    var startDone by remember { mutableStateOf(false) }
    DisposableEffectKey(mapReady, startDone) {
        if (mapReady && hasStart && !startDone) {
            mapView.controller.setZoom(startZoom ?: 5.0)
            mapView.controller.setCenter(GeoPoint(startLat!!, startLng!!))
            startDone = true
        }
    }

    var fitted by remember { mutableStateOf(false) }
    DisposableEffectKey(mapReady, devices.count { it.hasLocation }, fitted) {
        if (mapReady && !fitted && selectedId == null && !hasStart && !initialDeepZoom && devices.any { it.hasLocation }) {
            fitAll(mapView, devices); fitted = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { m -> markerSet.sync(m, devices, selectedId, zoomState.doubleValue) { id -> onSelect(id) } },
        )

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
            when {
                probe?.any == false -> Banner("No map server reachable from this phone (no internet, or blocked). Markers and live data still work.", StsmnColors.Warning)
                probe?.choice() == TileProbe.Choice.STREETS -> Banner("Satellite server is not reachable here: showing the street map instead.", StsmnColors.Info)
                !hasInternet -> Banner("No internet: map background may be blank (cached tiles only). Markers still work.", StsmnColors.Info)
            }
        }

        if (!mapReady) {
            Column(
                Modifier.align(Alignment.Center).panel(16.dp, 0.92f).padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Loading map…", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                androidx.compose.material3.LinearProgressIndicator(Modifier.size(width = 140.dp, height = 3.dp))
            }
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

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).panel(16.dp, 0.86f).padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZoomKey("+") { mapView.controller.zoomIn() }
            ZoomKey("−") { mapView.controller.zoomOut() }
            ZoomKey("Max", small = true) {
                val pt = selected?.takeIf { it.hasLocation }?.let { GeoPoint(it.lat!!, it.lng!!) }
                    ?: devices.firstOrNull { it.hasLocation }?.let { GeoPoint(it.lat!!, it.lng!!) }
                    ?: GeoPoint(AppConfig.DEMO_LAT, AppConfig.DEMO_LNG)
                mapView.controller.animateTo(pt, AppConfig.MAP_MAX_ZOOM, 900L)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!(selected != null && sheetExpanded)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Legend()
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnimatedVisibility(showLayers, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            LayerPanel(base, roads, onBase = { base = it }, onRoads = { roads = it })
                        }
                        OutlinedButton(
                            onClick = { showLayers = !showLayers },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        ) { Text("Layers · ${base.label}" + if (roads) " + Roads" else "", maxLines = 1, softWrap = false) }
                        Button(onClick = { fitAll(mapView, devices) }, enabled = devices.any { it.hasLocation }) { Text("Show all") }
                    }
                }
            }
            var lastSelected by remember { mutableStateOf<MapDevice?>(null) }
            LaunchedEffect(selected) { if (selected != null) lastSelected = selected }
            AnimatedVisibility(
                visible = selected != null,
                enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            ) {
                val d = selected ?: lastSelected
                if (d != null) TransformerSheet(
                    d, expanded = sheetExpanded, onToggle = { sheetExpanded = !sheetExpanded },
                    onClose = { onSelect(null); sheetExpanded = false },
                    onFlyTo = {
                        if (d.hasLocation) mapView.controller.animateTo(GeoPoint(d.lat!!, d.lng!!), maxOf(mapView.zoomLevelDouble, AppConfig.MAP_CENTER_ZOOM), 900L)
                    },
                )
            }
            Text(
                MapLayers.attribution(base, roads),
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun ZoomKey(label: String, small: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = if (small) 11.sp else 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LayerPanel(base: BaseLayer, roads: Boolean, onBase: (BaseLayer) -> Unit, onRoads: (Boolean) -> Unit) {
    Column(
        Modifier.panel(14.dp, 0.96f).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("MAP LAYER", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
        BaseLayer.values().forEach { b ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onBase(b) }.padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.RadioButton(selected = base == b, onClick = { onBase(b) }, modifier = Modifier.size(20.dp))
                Text(b.label, fontSize = 13.sp, fontWeight = if (base == b) FontWeight.Bold else FontWeight.Normal)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (base == BaseLayer.STREETS) "Roads (already shown)" else "Roads & labels", fontSize = 13.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = roads && base != BaseLayer.STREETS, onCheckedChange = onRoads, enabled = base != BaseLayer.STREETS)
        }
    }
}

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
        Modifier.panel(14.dp, 0.94f).padding(horizontal = 12.dp, vertical = 8.dp),
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
    Box(Modifier.panel(12.dp, 0.94f).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun Legend() {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.panel(14.dp, 0.88f).clickable { open = !open }.padding(horizontal = 10.dp, vertical = 8.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (!open) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(MarkerState.SAFE, MarkerState.WARNING, MarkerState.EMERGENCY, MarkerState.OFFLINE).forEach {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(markerColor(it)))
                }
                Text("Legend", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
        } else {
            LegendRow(MarkerState.SAFE, "SAFE"); LegendRow(MarkerState.WARNING, "WARNING")
            LegendRow(MarkerState.EMERGENCY, "EMERGENCY"); LegendRow(MarkerState.OFFLINE, "OFFLINE / STALE")
        }
    }
}

@Composable
private fun LegendRow(state: MarkerState, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(markerColor(state)))
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

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
