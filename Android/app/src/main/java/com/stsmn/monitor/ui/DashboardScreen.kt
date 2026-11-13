package com.stsmn.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.data.AppSettings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import com.stsmn.monitor.domain.AlertContact
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.StsmnStatus
import com.stsmn.monitor.domain.SystemStatus

@Composable
fun DashboardScreen(
    live: LiveSnapshot,
    settings: AppSettings,
    fleet: FleetState,
    onConnect: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    resetStatus: String?,
    onReset: () -> Unit,
) {
    val s = live.status
    val nowElapsed by rememberElapsedNow()
    val ageMs = live.lastValidElapsedMs?.let { (nowElapsed - it).coerceAtLeast(0L) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = LocalBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("STSMN", style = androidx.compose.ui.text.TextStyle(brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onBackground)), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp))
                val id = s?.deviceId ?: "TR-001"
                Text("$id · ${settings.deviceName}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ConnectionChip(live.connection)
                if (settings.cloudEnabled) CloudChip(fleet.cloud, fleet.publishing)
            }
        }

        HeroCard(live, onConnect)

        if (live.isLive && s != null && !s.relay) BigBanner("SUPPLY CUT OFF", StsmnColors.Emergency)

        if (s != null && s.latched) {
            AppCard(container = StsmnColors.Emergency.copy(alpha = 0.14f)) {
                Text("OVER-CURRENT LOCK", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
                Text(
                    "Supply stays cut until you reset it." + if (s.resetWaitS > 0) " Reset possible in ${s.resetWaitS} s." else " Fix the cause first.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                )
                Button(onClick = onReset, enabled = live.isLive && s.resetWaitS == 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Reset lock") }
                resetStatus?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
        }

        if (live.connection != ConnectionState.LIVE && live.connection != ConnectionState.IDLE) {
            AppCard(container = StsmnColors.Warning.copy(alpha = 0.14f)) {
                Text(
                    if (s != null) "Showing last known values – NOT live." else "No data received yet.",
                    fontWeight = FontWeight.Bold, color = StsmnColors.Warning,
                )
                Text(
                    if (live.viaBluetooth) live.linkNote.ifBlank { "Bluetooth: keep the phone near the ESP32." } else "Connect your phone to the ESP32 Wi-Fi and check the address in Settings (${live.host}).",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        live.anomalies.forEach {
            AppCard(container = StsmnColors.Emergency.copy(alpha = 0.16f)) {
                Text(it, fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
            }
        }

        SensorGrid(s, dimmed = !live.isLive)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateToggle("Relay", s?.relay, MaterialTheme.colorScheme.primary, StsmnColors.Emergency, Modifier.weight(1f), dimmed = !live.isLive)
            StateToggle("Alarm", s?.alarm, StsmnColors.Emergency, StsmnColors.Stale, Modifier.weight(1f), dimmed = !live.isLive)
        }

        Text(
            if (s != null) "Last update: ${fmtAge(ageMs)} · packet #${s.seq}" else "Last update: never",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onOpenSensors, modifier = Modifier.weight(1f)) { Text("Sensor data") }
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f)) { Text("Diagnostics") }
        }
    }
}

@Composable
fun CloudChip(cloud: CloudState, publishing: CloudState) {
    val (label, color) = when (cloud) {
        CloudState.ONLINE -> "CLOUD LIVE" to StsmnColors.Safe
        CloudState.CONNECTING, CloudState.RETRYING -> "CLOUD…" to StsmnColors.Warning
        CloudState.AUTH_FAILED -> "CLOUD KEY WRONG" to StsmnColors.Emergency
        CloudState.OFF -> if (publishing == CloudState.ONLINE) "PUBLISHING" to StsmnColors.Safe else "CLOUD OFF" to StsmnColors.Stale
    }
    Pill(label, color)
}

@Composable
private fun HeroCard(live: LiveSnapshot, onConnect: () -> Unit) {
    val s = live.status
    val isLive = live.isLive && s != null
    val color: Color = if (isLive) statusColor(s!!.status) else StsmnColors.Stale
    val title = when {
        isLive -> s!!.status.name
        live.connection == ConnectionState.STALE -> "DATA STALE"
        live.connection == ConnectionState.ERROR -> "NO CONNECTION"
        live.connection == ConnectionState.CONNECTING -> "CONNECTING…"
        else -> "NOT CONNECTED"
    }
    val glyph = when {
        !isLive -> "…"
        s!!.status == SystemStatus.SAFE -> "✔"
        else -> "⚠"
    }
    val description = when {
        isLive -> when (s!!.status) {
            SystemStatus.SAFE -> "All readings are normal."
            SystemStatus.WARNING -> "Abnormal condition – inspection advised." + reasonSuffix(s.reason)
            SystemStatus.EMERGENCY -> "Critical fault – supply cut off by ESP32." + reasonSuffix(s.reason)
        }
        s != null -> "Last known: ${s.status.name} · risk ${s.risk} (not live)"
        else -> "Waiting for a valid packet from the transformer node."
    }
    val onCard = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(LocalCardRadius.current + 4.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.66f))
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(500f, 700f)))

            .background(Brush.radialGradient(listOf(color.copy(alpha = 0.20f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(0f, 0f), radius = 700f))
            .border(1.dp, color.copy(alpha = 0.45f), shape),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!isLive) RadarPulse(MaterialTheme.colorScheme.primary, Modifier.size(64.dp))
                else Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.16f))
                        .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(glyph, fontSize = 26.sp, color = color, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = color, maxLines = 2)
                    Text(
                        if (isLive) "Risk Score" else "Risk Score (last known)",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.6.sp,
                    )
                    Text(
                        (if (s != null && (isLive || live.status != null)) "${s.risk}" else "--") + " / 100",
                        style = AurelionType.number, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = onCard,
                    )
                }
                TransformerArt(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f), Modifier.size(width = 72.dp, height = 66.dp))
            }
            Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            if (live.connection == ConnectionState.IDLE) {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun SensorGrid(s: StsmnStatus?, dimmed: Boolean) {
    val na = "—"
    val ok = MaterialTheme.colorScheme.primary
    val oil = s?.oilLevel
    val oilLow = oil != null && oil < AppConfig.OIL_LOW_PERCENT
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("🌡️", "Temperature", if (s != null) fmtTemp(s.temperature) else na, ok, dimmed, Modifier.weight(1f))
            MetricTile("⚡", "Voltage", if (s != null) fmtVolt(s.voltage) else na, ok, dimmed, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile("〰️", "Current", if (s != null) fmtAmp(s.current) else na, ok, dimmed, Modifier.weight(1f))
            MetricTile(
                "💨", "Smoke", if (s == null) na else if (s.smoke) "Detected" else "Normal",
                if (s?.smoke == true) StsmnColors.Emergency else ok, dimmed, Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                "🔥", "Flame", if (s == null) na else if (s.flame) "Detected" else "None",
                if (s?.flame == true) StsmnColors.Emergency else ok, dimmed, Modifier.weight(1f),
            )
            MetricTile(
                "🛢️", "Oil level", if (s != null) fmtOil(oil) else na,
                if (oilLow) StsmnColors.Warning else ok, dimmed, Modifier.weight(1f),
                sub = if (s != null && oil != null && oilLow) "Low" else null,
            )
        }
    }
}

private fun reasonSuffix(r: String): String = if (r.isEmpty() || r == "OK") "" else " Reason: $r."
