package com.stsmn.monitor.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.MapDevice

/**
 * Bottom sheet for the selected transformer. Collapsed: identity, status, location type, connection and three key
 * readings. Expanded: every sensor. Only real values are shown; anything the transformer did not send is "—", and
 * readings from a transformer that is not live are dimmed and labelled "last known".
 */
@Composable
fun TransformerSheet(d: MapDevice, expanded: Boolean, onToggle: () -> Unit, onClose: () -> Unit, onFlyTo: () -> Unit) {
    val color = markerColor(d.state)
    val ctx = LocalContext.current
    val na = "—"
    val coords = if (d.hasLocation) String.format(java.util.Locale.US, "%.5f, %.5f", d.lat, d.lng) else "not set"
    val hasData = d.status != null

    Column(
        Modifier.fillMaxWidth().animateContentSize().panel(22.dp, 0.97f).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        // drag-handle style toggle
        Box(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Text("⚡", fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text(d.label, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text(d.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Pill(d.state.label, color)
            TextButton(onClick = onClose) { Text("Close") }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (d.locationLive) Pill("LIVE GPS", StsmnColors.Safe)
            else if (d.demoLocation) Pill("DEMO LOCATION", StsmnColors.Warning) else Pill("CONFIGURED COORDINATES", StsmnColors.Info)
            Pill(if (d.isLive) "LIVE" else "NOT LIVE", if (d.isLive) StsmnColors.Safe else StsmnColors.Warning)
        }
        Text(
            (if (d.isLocal) "Direct ESP32 link" else "Realtime server") + " · updated " + fmtAge(d.ageMs) + " · " + coords,
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
        if (!d.isLive) {
            Text(
                if (hasData) "Not live. Values below are the LAST KNOWN readings." else "No data received from this transformer yet.",
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = StsmnColors.Warning, modifier = Modifier.padding(top = 4.dp),
            )
        }

        val key = listOf(
            "Risk" to (d.risk?.let { "$it/100" } ?: na),
            "Temp" to (if (hasData) fmtTemp(d.temperature) else na),
            "Current" to (if (hasData) fmtAmp(d.current) else na),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).alpha(if (d.isLive) 1f else 0.5f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            key.forEach { (l, v) -> Tile(l, v, Modifier.weight(1f)) }
        }

        if (expanded) {
            val all = listOf(
                "Status" to (d.status?.name ?: na),
                "Voltage" to (if (hasData) fmtVolt(d.voltage) else na),
                "Smoke" to (d.smoke?.let { if (it) "DETECTED" else "Normal" } ?: na),
                "Flame" to (d.flame?.let { if (it) "DETECTED" else "None" } ?: na),
                "Oil level (demo)" to (if (hasData) fmtOil(d.oilLevel) else na),
                "Relay" to (d.relay?.let { if (it) "ON (supply)" else "OFF (cutoff)" } ?: na),
                "Alarm" to (d.alarm?.let { if (it) "ON" else "OFF" } ?: na),
            )
            Column(
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp).alpha(if (d.isLive) 1f else 0.5f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                all.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (l, v) -> Tile(l, v, Modifier.weight(1f)) }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFlyTo, enabled = d.hasLocation, modifier = Modifier.weight(1f)) { Text("Fly to", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { openGoogleEarth(ctx, d.lat!!, d.lng!!) }, enabled = d.hasLocation, modifier = Modifier.weight(1f)) { Text("Earth", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { openStreetView(ctx, d.lat!!, d.lng!!) }, enabled = d.hasLocation, modifier = Modifier.weight(1f)) { Text("Street", maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = onToggle, modifier = Modifier.weight(1f)) { Text(if (expanded) "Less" else "All", maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
