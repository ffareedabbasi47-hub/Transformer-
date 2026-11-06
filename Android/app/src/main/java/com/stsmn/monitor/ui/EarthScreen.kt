package com.stsmn.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice

/** Where the flat satellite map should start when it takes over from the globe. */
private data class MapTarget(val lat: Double, val lon: Double, val zoom: Double)

/**
 * The one Map screen. It opens on a native 3D Earth (real satellite imagery, India first); zooming in or tapping a
 * transformer dives into the satellite map at that place. 🌍 on the left edge returns to the globe, 📋 opens the list.
 */
@Composable
fun EarthScreen(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var target by remember { mutableStateOf<MapTarget?>(null) }     // null = globe
    var showList by rememberSaveable { mutableStateOf(false) }
    // Fleet tab "open on map" arrives as selectedId: show that device on the flat map.
    val flat = target != null || selectedId != null

    Box(Modifier.fillMaxSize()) {
        if (flat) {
            val t = target
            MapScreen(
                devices, fleet, cloudEnabled, selectedId, onSelect, onOpenSettings,
                startLat = t?.lat, startLng = t?.lon, startZoom = t?.zoom,
            )
        } else {
            NativeGlobe(
                devices, fleet, cloudEnabled,
                onOpenMap = { lat, lon, zoom, id -> onSelect(id); target = MapTarget(lat, lon, zoom) },
                onUnavailable = { target = MapTarget(22.5, 78.96, 4.0) },
            )
        }

        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { showList = true }, contentAlignment = Alignment.Center) {
                Text("List", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            if (flat) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { onSelect(null); target = null },
                    contentAlignment = Alignment.Center,
                ) { Text("Globe", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
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
