@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stsmn.monitor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rounded surface card used everywhere for a consistent look. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.US),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun connectionColor(state: ConnectionState): Color = when (state) {
    ConnectionState.LIVE -> StsmnColors.Safe
    ConnectionState.CONNECTING -> StsmnColors.Info
    ConnectionState.STALE -> StsmnColors.Warning
    ConnectionState.ERROR -> StsmnColors.Emergency
    ConnectionState.IDLE -> StsmnColors.Stale
}

@Composable
fun ConnectionChip(state: ConnectionState) {
    val label = when (state) {
        ConnectionState.LIVE -> "LIVE"
        ConnectionState.CONNECTING -> "CONNECTING"
        ConnectionState.STALE -> "STALE"
        ConnectionState.ERROR -> "ERROR · RETRYING"
        ConnectionState.IDLE -> "NOT CONNECTED"
    }
    val color = connectionColor(state)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Semi-circular risk gauge. [risk] == null renders an empty gauge with "--". */
@Composable
fun RiskGauge(risk: Int?, color: Color, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(targetValue = (risk ?: 0).coerceIn(0, 100) / 100f, label = "risk")
    val animatedColor by animateColorAsState(targetValue = color, label = "riskColor")
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(modifier = modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track, startAngle = 150f, sweepAngle = 240f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (risk != null && fraction > 0f) {
                drawArc(
                    color = animatedColor, startAngle = 150f, sweepAngle = 240f * fraction, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(risk?.toString() ?: "--", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
            Text("RISK / 100", fontSize = 11.sp, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Small tile used on the dashboard grid. [dimmed] = data is not live. */
@Composable
fun SensorTile(
    label: String,
    value: String,
    accent: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    sub: String? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp).alpha(if (dimmed) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent)
            Text(sub ?: " ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------- Formatting helpers ----------
fun fmtTemp(v: Double?) = v?.let { String.format(Locale.US, "%.1f °C", it) } ?: "N/A"
fun fmtVolt(v: Double?) = v?.let { String.format(Locale.US, "%.2f V", it) } ?: "N/A"
fun fmtAmp(v: Double?) = v?.let { String.format(Locale.US, "%.2f A", it) } ?: "N/A"
fun fmtOil(v: Int?) = v?.let { "$it %" } ?: "N/A"

fun fmtAge(ageMs: Long?): String = when {
    ageMs == null -> "never"
    ageMs < 1000 -> "just now"
    ageMs < 60_000 -> "${ageMs / 1000}s ago"
    else -> "${ageMs / 60_000}m ${(ageMs / 1000) % 60}s ago"
}

fun fmtUptime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}

fun fmtDateTime(ms: Long): String =
    SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(ms))

fun fmtClock(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
