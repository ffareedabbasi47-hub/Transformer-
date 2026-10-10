package com.stsmn.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice
import com.stsmn.monitor.domain.MarkerState

/** Every transformer on one screen, most dangerous first. Tap one to see it on the map. */
@Composable
fun FleetScreen(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    onOpenOnMap: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val emergencies = devices.filter { it.state == MarkerState.EMERGENCY }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Fleet", "${devices.size} transformer(s) · realtime")
                if (cloudEnabled) CloudChip(fleet.cloud, fleet.publishing)
            }
        }
        if (emergencies.isNotEmpty()) {
            item { BigBanner("${emergencies.size} EMERGENCY: " + emergencies.joinToString { it.label }, StsmnColors.Emergency) }
        }
        if (!cloudEnabled) {
            item {
                AppCard {
                    Text("See every transformer live", fontWeight = FontWeight.Bold)
                    Text(
                        "Turn on the realtime cloud in Settings to watch all transformers, each with its own ID, status and location. " +
                            "Without it this screen only shows the transformer connected to this phone.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Button(onClick = onOpenSettings) { Text("Set up realtime cloud") }
                }
            }
        } else if (fleet.cloud == CloudState.AUTH_FAILED) {
            item {
                AppCard(container = StsmnColors.Emergency.copy(alpha = 0.14f)) {
                    Text("Server rejected the viewer key", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
                    Text("Check Settings → Realtime cloud. Retrying slowly so nothing gets locked out.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (fleet.cloud != CloudState.ONLINE) {
            item {
                AppCard(container = StsmnColors.Warning.copy(alpha = 0.14f)) {
                    Text("Reconnecting to the server…", fontWeight = FontWeight.Bold, color = StsmnColors.Warning)
                    Text("Values below are the last known state, not live.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (devices.isEmpty()) {
            item { Text("No transformers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp)) }
        }
        items(devices, key = { it.id }) { d -> DeviceRow(d) { onOpenOnMap(d.id) } }
    }
}

@Composable
private fun DeviceRow(d: MapDevice, onClick: () -> Unit) {
    val c = markerColor(d.state)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(c))
        Column(Modifier.weight(1f).alpha(if (d.isLive) 1f else 0.6f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (d.isLocal) Pill("THIS PHONE", StsmnColors.Info)
            }
            Text(d.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (d.status != null) "${fmtTemp(d.temperature)} · ${fmtVolt(d.voltage)} · ${fmtAmp(d.current)}" else "No data yet",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                (if (d.hasLocation) "📍 on map" else "📍 no location") + " · updated ${fmtAge(d.ageMs)}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(d.state.label, c)
            if (d.risk != null) Text("risk ${d.risk}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
