package com.stsmn.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.StsmnStatus

private data class SensorRow(
    val name: String,
    val value: String,
    val valid: Boolean,
    val fraction: Float?,   // position inside the sensor's measuring range, null for on/off sensors
    val rangeText: String,
    val accent: Color,
    val trend: List<Double?> = emptyList(),
)

@Composable
fun SensorsScreen(live: LiveSnapshot) {
    val s = live.status
    val dimmed = !live.isLive
    val rows: List<SensorRow> = if (s == null) emptyList() else listOf(
        SensorRow("Temperature (DS18B20)", fmtTemp(s.temperature), s.temperature != null,
            s.temperature?.let { rangeFraction(it, 0.0, 125.0) }, "0 – 125 °C", StsmnColors.Warning, live.recent.map { it.temperature }),
        SensorRow("Voltage (0–25 V module)", fmtVolt(s.voltage), s.voltage != null,
            s.voltage?.let { rangeFraction(it, AppConfig.VOLTAGE_MIN_V, AppConfig.VOLTAGE_MAX_V) }, "0 – 25 V", StsmnColors.Info, live.recent.map { it.voltage }),
        SensorRow("Current (ACS712 5A)", fmtAmp(s.current), s.current != null,
            s.current?.let { rangeFraction(Math.abs(it), 0.0, AppConfig.CURRENT_MAX_A) }, "0 – 5 A", StsmnColors.Info, live.recent.map { it.current }),
        SensorRow("Smoke / gas (MQ-2)", signalText(s.smokePct, s.smoke, "DETECTED", "Normal"), true, s.smokePct?.let { it / 100f }, "Signal strength (0 – 100 %)",
            if (s.smoke || (s.smokePct ?: 0) >= 15) StsmnColors.Warning else StsmnColors.Safe),
        SensorRow("Flame sensor", signalText(s.flamePct, s.flame, "DETECTED", "None"), true, s.flamePct?.let { it / 100f }, "Signal strength (0 – 100 %)",
            if (s.flame || (s.flamePct ?: 0) >= 15) StsmnColors.Emergency else StsmnColors.Safe),
        SensorRow("Oil level (water demo)", fmtOil(s.oilLevel), s.oilLevel != null,
            s.oilLevel?.let { it / 100f },
            "Low below ${AppConfig.OIL_LOW_PERCENT} %",
            if ((s.oilLevel ?: 100) < AppConfig.OIL_LOW_PERCENT) StsmnColors.Warning else StsmnColors.Safe,
            live.recent.map { it.oilLevel?.toDouble() }),
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Sensor Data", "Exact readings, validity and the last ${AppConfig.TREND_POINTS} samples")
        if (s == null) {
            AppCard { Text("No data yet. Connect to the ESP32 first.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            if (dimmed) {
                AppCard(container = StsmnColors.Warning.copy(alpha = 0.14f)) {
                    Text("Last known values (${fmtAge(live.packetAgeMs)}) – not live.", color = StsmnColors.Warning, fontWeight = FontWeight.Bold)
                }
            }
            rows.forEach { SensorCard(it, dimmed) }
            SignalCard(s, dimmed)   // right below the oil level card
            AppCard {
                InfoRow("Device", "${s.deviceId} · fw ${s.fw}")
                InfoRow("ESP32 uptime", fmtUptime(s.uptimeMs))
            }
        }
    }
}

private fun strengthWord(pct: Int): String = when {
    pct <= 0 -> "none"
    pct < 34 -> "weak"
    pct < 67 -> "medium"
    else -> "strong"
}

private fun signalText(pct: Int?, on: Boolean, onText: String, offText: String): String = when {
    pct == null -> if (on) onText else offText
    pct <= 0 && !on -> offText
    else -> "$pct %  ${strengthWord(pct)}"
}

private fun rangeFraction(v: Double, min: Double, max: Double): Float =
    ((v - min) / (max - min)).toFloat().coerceIn(0f, 1f)

@Composable
private fun SensorCard(r: SensorRow, dimmed: Boolean) {
    AppCard {
        Column(Modifier.alpha(if (dimmed) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.valid) Pill("VALID", StsmnColors.Safe) else Pill("UNAVAILABLE", StsmnColors.Warning)
            }
            Text(r.value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (r.valid) r.accent else StsmnColors.Stale)
            if (r.trend.isNotEmpty()) Sparkline(r.trend, if (r.valid) r.accent else StsmnColors.Stale)
            else if (r.fraction != null && r.valid) {
                LinearProgressIndicator(progress = { r.fraction }, modifier = Modifier.fillMaxWidth(), color = r.accent)
            }
            Text(r.rangeText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How strong each danger signal is right now (percent of its range), as reported by the ESP32. */
@Composable
private fun SignalCard(s: StsmnStatus?, dimmed: Boolean) {
    if (s == null) return
    val rows = listOf(
        "Fire (flame)" to s.flamePct, "Smoke / gas" to s.smokePct, "Temperature" to s.tempPct,
        "Current" to s.currentPct, "Voltage" to s.voltagePct,
    )
    if (rows.all { it.second == null }) return   // older firmware: nothing to show
    AppCard {
        Text("Signal strength", fontWeight = FontWeight.Bold)
        Text(
            if (dimmed) "Last known values - NOT live." else "Percent of the danger range. Weak signals are shown too.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { (label, pct) ->
            val (word, color) = when {
                pct == null -> "no data / not fitted" to StsmnColors.Stale
                pct <= 0 -> "none" to StsmnColors.Safe
                pct < 34 -> "weak" to StsmnColors.Warning
                pct < 67 -> "medium" to StsmnColors.Warning
                else -> "strong" to StsmnColors.Emergency
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontSize = 14.sp)
                Text(if (pct == null) "-" else "$pct%  $word", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
            }
            LinearProgressIndicator(
                progress = { (pct ?: 0) / 100f },
                color = color, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}
