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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice

/**
 * The one Map screen: a real 3D satellite Earth you can zoom, tilt and switch between Satellite and Streets.
 * Nothing to choose: if this phone cannot draw the 3D view, the same screen quietly uses the native satellite map
 * (same markers, same buttons). The list of all transformers opens over the map with the 📋 button.
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
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("stsmn_ui", android.content.Context.MODE_PRIVATE) }
    // "earth_failed" is written by RealEarthScreen when the 3D view could not draw on this phone.
    var native by rememberSaveable { mutableStateOf(prefs.getBoolean("earth_failed", false)) }
    var showList by rememberSaveable { mutableStateOf(false) }

    MapDiag.engine = if (native) "engine: native satellite map" else "engine: 3D Earth"
    Box(Modifier.fillMaxSize()) {
        if (native) {
            MapScreen(devices, fleet, cloudEnabled, selectedId, onSelect, onOpenSettings)
        } else {
            RealEarthScreen(devices, fleet, cloudEnabled, selectedId, onSelect, onOpenSettings, onUseFlatMap = { native = true })
        }

        // Floating controls on the left edge (the right edge is used by the map's own zoom buttons).
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                .clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { showList = true }, contentAlignment = Alignment.Center) {
                Text("📋", fontSize = 18.sp)
            }
            if (native) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable {
                        prefs.edit().putBoolean("earth_failed", false).apply()
                        native = false
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("🌍", fontSize = 18.sp) }
            }
        }

        // Diagnostics line: temporary, so one screenshot shows what this phone is doing.
        Text(
            MapDiag.engine + " · " + MapDiag.probe + (if (native) "" else " · " + MapDiag.web),
            fontSize = 9.sp, maxLines = 3, color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
        )

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
