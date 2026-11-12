package com.stsmn.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.FleetFilter
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice
import com.stsmn.monitor.domain.StateFilter

private data class MapTarget(val lat: Double, val lon: Double, val zoom: Double)

private data class GlobeStart(val lat: Double, val lon: Double)

@Composable
fun EarthScreen(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    fullscreen: Boolean = false,
    onFullscreen: (Boolean) -> Unit = {},
) {
    var target by remember { mutableStateOf<MapTarget?>(null) }
    var globeStart by remember { mutableStateOf<GlobeStart?>(null) }
    val lastCam = remember { doubleArrayOf(Double.NaN, Double.NaN, 0.0) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var activeNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val active = activeNames.mapNotNull { n -> StateFilter.values().firstOrNull { it.name == n } }.toSet()

    val visible = FleetFilter.apply(devices, query, active)
    val filtering = query.isNotBlank() || active.isNotEmpty()

    val flat = target != null || selectedId != null

    BackHandler(enabled = fullscreen) { onFullscreen(false) }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = flat, animationSpec = tween(450), label = "earth-map") { isFlat ->
            if (isFlat) {
                val t = target
                MapScreen(
                    visible, fleet, cloudEnabled, selectedId, onSelect, onOpenSettings,
                    startLat = t?.lat, startLng = t?.lon, startZoom = t?.zoom,
                    onCamera = { la, lo, z -> lastCam[0] = la; lastCam[1] = lo; lastCam[2] = z },
                )
            } else {
                val gs = globeStart
                NativeGlobe(
                    visible, fleet, cloudEnabled,
                    onOpenMap = { lat, lon, zoom, id -> onSelect(id); globeStart = null; target = MapTarget(lat, lon, zoom) },
                    onUnavailable = { target = MapTarget(22.5, 78.96, 4.0) },
                    startLat = gs?.lat, startLon = gs?.lon, startScale = if (gs != null) 2.3f else 1f,
                )
            }
        }

        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp).panel(16.dp, 0.84f).padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailButton("List", false) { showList = true }
            RailButton("Search", showSearch || filtering) { showSearch = !showSearch }
            RailButton(if (fullscreen) "Exit full" else "Full", fullscreen) { onFullscreen(!fullscreen) }
            if (flat) RailButton("Globe", false) {
                val la = lastCam[0]; val lo = lastCam[1]
                globeStart = if (!la.isNaN() && !lo.isNaN()) GlobeStart(la, lo) else null
                onSelect(null); target = null
            }
        }

        AnimatedVisibility(
            visible = showSearch,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp, start = 12.dp, end = 12.dp),
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 4 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { -it / 4 },
        ) {
            SearchPanel(
                all = devices, shown = visible, query = query, onQuery = { query = it },
                active = active,
                onToggle = { f -> activeNames = if (f in active) activeNames - f.name else activeNames + f.name },
                onClear = { query = ""; activeNames = emptyList() },
                onPick = { d ->
                    onSelect(d.id)
                    if (target == null && d.hasLocation) target = MapTarget(d.lat!!, d.lng!!, 11.0)
                    showSearch = false
                },
                onClose = { showSearch = false },
            )
        }
        if (!showSearch && filtering) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 58.dp).panel(14.dp, 0.92f).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Showing ${visible.size} of ${devices.size}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { query = ""; activeNames = emptyList() }) { Text("Clear") }
            }
        }

        if (showList) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                TextButton(onClick = { showList = false }, modifier = Modifier.padding(start = 8.dp)) { Text("‹  Back to map") }
                Box(Modifier.weight(1f)) {
                    FleetScreen(
                        devices, fleet, cloudEnabled,
                        onOpenOnMap = { id -> onSelect(id); showList = false },
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun RailButton(label: String, highlighted: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent, tween(180), label = "rail")
    Box(
        Modifier.size(width = 52.dp, height = 42.dp).clip(RoundedCornerShape(12.dp))
            .background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SearchPanel(
    all: List<MapDevice>, shown: List<MapDevice>, query: String, onQuery: (String) -> Unit,
    active: Set<StateFilter>, onToggle: (StateFilter) -> Unit, onClear: () -> Unit,
    onPick: (MapDevice) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
) {
    val counts = FleetFilter.counts(all)
    Column(
        modifier.fillMaxWidth().panel(18.dp, 0.97f).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Search transformer (e.g. TR-001)") },
            )
            TextButton(onClick = onClose) { Text("Done") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StateFilter.values().forEach { f ->
                val on = f in active
                val chipBg by animateColorAsState(
                    if (on) chipColor(f).copy(alpha = 0.30f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), tween(160), label = "chip",
                )
                val c = when (f) {
                    StateFilter.SAFE -> StsmnColors.Safe; StateFilter.WARNING -> StsmnColors.Warning
                    StateFilter.EMERGENCY -> StsmnColors.Emergency; StateFilter.OFFLINE -> StsmnColors.Stale
                }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(chipBg)
                        .clickable { onToggle(f) }.padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Text(" ${f.label.take(4)} ${counts[f] ?: 0}", fontSize = 11.sp, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 1, softWrap = false)
                }
            }
        }
        Text(
            if (shown.isEmpty()) "No transformer matches." else "${shown.size} of ${all.size} shown" + if (active.isNotEmpty() || query.isNotBlank()) "" else " · tap a status to filter",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(d) }.padding(vertical = 8.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(markerColor(d.state)))
                    Column(Modifier.weight(1f)) {
                        Text(d.label, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(d.name + if (!d.hasLocation) " · no location" else "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(d.state.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (query.isNotBlank() || active.isNotEmpty()) TextButton(onClick = onClear) { Text("Clear search and filters") }
    }
}

private fun chipColor(f: StateFilter): Color = when (f) {
    StateFilter.SAFE -> StsmnColors.Safe; StateFilter.WARNING -> StsmnColors.Warning
    StateFilter.EMERGENCY -> StsmnColors.Emergency; StateFilter.OFFLINE -> StsmnColors.Stale
}
