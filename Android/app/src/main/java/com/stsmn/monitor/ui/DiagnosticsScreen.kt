package com.stsmn.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.LiveSnapshot

/** Mockup #8: one checklist line per subsystem, green = OK, amber = unavailable, red = problem. */
@Composable
fun DiagnosticsScreen(live: LiveSnapshot, fleet: FleetState, cloudEnabled: Boolean) {
    val s = live.status
    val nowElapsed by rememberElapsedNow()
    val age = live.lastValidElapsedMs?.let { (nowElapsed - it).coerceAtLeast(0L) }
    val have = s != null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Diagnostics", "Health of every part of the system")
        AppCard {
            CheckRow("ESP32", when (live.connection) {
                ConnectionState.LIVE -> "Connected" to StsmnColors.Safe
                ConnectionState.STALE -> "Stale" to StsmnColors.Warning
                ConnectionState.CONNECTING -> "Connecting" to StsmnColors.Info
                ConnectionState.ERROR -> "Unreachable" to StsmnColors.Emergency
                ConnectionState.IDLE -> "Not connected" to StsmnColors.Stale
            })
            CheckRow("Wi-Fi (local)", if (live.wifiBound) "Connected" to StsmnColors.Safe else "Not found" to StsmnColors.Warning)
            CheckRow("Temperature sensor", sensorState(s?.temperature != null, have))
            CheckRow("Current sensor", sensorState(s?.current != null, have))
            CheckRow("Voltage sensor", sensorState(s?.voltage != null, have))
            CheckRow("Smoke sensor", if (have) "OK" to StsmnColors.Safe else "—" to StsmnColors.Stale)
            CheckRow("Flame sensor", if (have) "OK" to StsmnColors.Safe else "—" to StsmnColors.Stale)
            CheckRow("Oil-level (demo)", sensorState(s?.oilLevel != null, have))
            CheckRow("Relay", when {
                !have -> "—" to StsmnColors.Stale
                live.anomalies.isNotEmpty() -> "Fault" to StsmnColors.Emergency
                else -> "OK" to StsmnColors.Safe
            })
            CheckRow("JSON / API", when {
                !have -> "—" to StsmnColors.Stale
                live.parseErrors == 0L -> "OK" to StsmnColors.Safe
                else -> "${live.parseErrors} errors" to StsmnColors.Warning
            })
            if (cloudEnabled) {
                CheckRow("Realtime server", when (fleet.cloud) {
                    CloudState.ONLINE -> "Connected" to StsmnColors.Safe
                    CloudState.CONNECTING, CloudState.RETRYING -> "Reconnecting" to StsmnColors.Warning
                    CloudState.AUTH_FAILED -> "Key rejected" to StsmnColors.Emergency
                    CloudState.OFF -> "Off" to StsmnColors.Stale
                })
                CheckRow("Publishing this transformer", when (fleet.publishing) {
                    CloudState.ONLINE -> "Live" to StsmnColors.Safe
                    CloudState.CONNECTING, CloudState.RETRYING -> "Reconnecting" to StsmnColors.Warning
                    CloudState.AUTH_FAILED -> "Token rejected" to StsmnColors.Emergency
                    CloudState.OFF -> "Off" to StsmnColors.Stale
                })
            }
        }
        AppCard {
            InfoRow("Last packet", fmtAge(age))
            InfoRow("Firmware", s?.fw ?: "—")
            InfoRow("ESP32 uptime", s?.let { fmtUptime(it.uptimeMs) } ?: "—")
            InfoRow("Packets / errors", "${live.packetsReceived} / ${live.parseErrors + live.networkErrors}")
            if (live.lastError != null) {
                Text("Last error: ${live.lastError}", color = StsmnColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun sensorState(valid: Boolean, have: Boolean): Pair<String, Color> = when {
    !have -> "—" to StsmnColors.Stale
    valid -> "OK" to StsmnColors.Safe
    else -> "Unavailable" to StsmnColors.Warning
}

@Composable
private fun CheckRow(label: String, state: Pair<String, Color>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        val mark = when (state.second) {
            StsmnColors.Safe -> "✓"
            StsmnColors.Warning -> "!"
            StsmnColors.Emergency -> "✕"
            else -> "–"
        }
        Box(Modifier.size(22.dp).clip(CircleShape).background(state.second.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text(mark, color = state.second, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(label, modifier = Modifier.weight(1f).padding(start = 12.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(state.first, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = state.second)
    }
}
