@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stsmn.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.domain.AlertBuilder
import com.stsmn.monitor.domain.EventRecord
import com.stsmn.monitor.domain.EventType
import com.stsmn.monitor.domain.LiveSnapshot

private val FILTERS = listOf("All", "Safe", "Warning", "Emergency", "Connection")

private fun eventColor(e: EventRecord): Color = when {
    e.type == EventType.CONNECTION_LOST -> StsmnColors.Emergency
    e.type == EventType.CONNECTION_RESTORED -> StsmnColors.Safe
    e.type == EventType.DEVICE_RESTART -> StsmnColors.Info
    e.status == "EMERGENCY" || e.type == EventType.EMERGENCY -> StsmnColors.Emergency
    e.status == "WARNING" -> StsmnColors.Warning
    e.status == "SAFE" -> StsmnColors.Safe
    else -> StsmnColors.Warning
}

private fun isConnectionEvent(t: EventType) =
    t == EventType.CONNECTION_LOST || t == EventType.CONNECTION_RESTORED || t == EventType.DEVICE_RESTART

/** Mockup screen 6 filters: All / Safe / Warning / Emergency, plus Connection events. */
private fun matches(filter: Int, e: EventRecord): Boolean = when (filter) {
    1 -> !isConnectionEvent(e.type) && e.status == "SAFE"
    2 -> !isConnectionEvent(e.type) && e.status == "WARNING"
    3 -> !isConnectionEvent(e.type) && (e.status == "EMERGENCY" || e.type == EventType.EMERGENCY)
    4 -> isConnectionEvent(e.type)
    else -> true
}

@Composable
fun HistoryScreen(live: LiveSnapshot, events: List<EventRecord>, onClear: () -> Unit) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val alerts = AlertBuilder.build(live)
    val shown = events.filter { matches(filter, it) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("History", "Active alerts and past events (stored on this phone)") }

        item { SectionLabel("Active alerts") }
        if (alerts.isEmpty()) {
            item {
                AppCard(container = StsmnColors.Safe.copy(alpha = 0.12f)) {
                    Text("No active alerts", fontWeight = FontWeight.Bold, color = StsmnColors.Safe)
                    Text("Live data is arriving and all conditions are normal.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(alerts) { a ->
                val c = alertColor(a.level)
                AppCard(container = c.copy(alpha = 0.12f)) {
                    Text(a.title, fontWeight = FontWeight.Bold, color = c)
                    Text(a.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { SectionLabel("Event log") }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FILTERS.forEachIndexed { i, label ->
                        FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
                    }
                }
                TextButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) { Text("Clear") }
            }
        }
        if (shown.isEmpty()) {
            item {
                Text("No events yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
        } else {
            items(shown) { EventRow(it) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear event history?") },
            text = { Text("All ${events.size} stored events will be deleted from this phone.") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EventRow(e: EventRecord) {
    val c = eventColor(e)
    val glyph = when (c) {
        StsmnColors.Safe -> "✓"
        StsmnColors.Info -> "↻"
        else -> "!"
    }
    val title = if (isConnectionEvent(e.type)) e.type.label.uppercase() else (e.status ?: e.type.label).uppercase()
    AppCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Text(glyph, color = c, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.ExtraBold, color = c, fontSize = 15.sp)
                Text(
                    (if (e.risk != null) "Risk: ${e.risk}  |  " else "") + e.message,
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                )
                if (e.relay != null) {
                    Text(
                        "Relay " + if (e.relay) "ON" else "OFF",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtClock(e.timestampMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.deviceId, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
