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
        SensorRow("Smoke / gas (MQ-2)", if (s.smoke) "DETECTED" else "Normal", true, null, "Demo indicator",
            if (s.smoke) StsmnColors.Warning else StsmnColors.Safe),
        SensorRow("Flame sensor", if (s.flame) "DETECTED" else "None", true, null, "Digital input",
            if (s.flame) StsmnColors.Emergency else StsmnColors.Safe),
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
            AppCard {
                InfoRow("Device", "${s.deviceId} · fw ${s.fw}")
                InfoRow("ESP32 uptime", fmtUptime(s.uptimeMs))
            }
        }
    }
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
