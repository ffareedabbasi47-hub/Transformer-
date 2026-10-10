@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stsmn.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stsmn.monitor.BuildConfig
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.data.AppSettings
import com.stsmn.monitor.data.SettingsStore
import com.stsmn.monitor.data.ThemeMode
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.stsmn.monitor.domain.CloudRules
import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.LocationSource
import com.stsmn.monitor.domain.SiteLocationRules

@Composable
fun SettingsScreen(
    live: LiveSnapshot,
    settings: AppSettings,
    fleet: FleetState,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(settings.deviceHost) }
    var deviceName by rememberSaveable { mutableStateOf(settings.deviceName) }
    var hostError by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var latText by rememberSaveable { mutableStateOf(settings.siteLat?.toString() ?: "") }
    var lngText by rememberSaveable { mutableStateOf(settings.siteLng?.toString() ?: "") }
    var sourceSel by rememberSaveable { mutableStateOf(settings.siteSource) }
    var locMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var locIsError by rememberSaveable { mutableStateOf(false) }
    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var viewerKey by rememberSaveable { mutableStateOf(settings.viewerKey) }
    var cloudId by rememberSaveable { mutableStateOf(settings.cloudDeviceId) }
    var deviceToken by rememberSaveable { mutableStateOf(settings.deviceToken) }
    var cloudMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudErr by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Settings", "Connection, realtime cloud, display")
        OutlinedButton(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) { Text("Profile · ${settings.profileName.ifBlank { "Unnamed" }}") }

        // ---------------- Connection ----------------
        SectionLabel("Connection")
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConnectionChip(live.connection)
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live.connection == ConnectionState.IDLE) {
                    Button(onClick = onConnect) { Text("Connect") }
                } else {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
            OutlinedTextField(
                value = host, onValueChange = { host = it; hostError = false },
                label = { Text("ESP32 address") }, singleLine = true, isError = hostError,
                supportingText = { Text(if (hostError) "Use a private IP address like 192.168.4.1 (192.168.x.x, 10.x.x.x or 172.16-31.x.x)" else "Firmware default (set in config.h): ${AppConfig.DEFAULT_DEVICE_HOST}") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = deviceName, onValueChange = { deviceName = it.take(AppConfig.MAX_TEXT_LENGTH) },
                label = { Text("Device name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = {
                    if (SettingsStore.isValidHost(host)) {
                        onUpdate { it.copy(deviceHost = host.trim(), deviceName = deviceName) }
                    } else hostError = true
                },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Save") }

            Text("Refresh rate", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppConfig.POLL_INTERVAL_CHOICES_MS.forEach { ms ->
                    FilterChip(selected = settings.pollIntervalMs == ms, onClick = { onUpdate { it.copy(pollIntervalMs = ms) } }, label = { Text(secLabel(ms)) })
                }
            }
            Text("Mark data stale after", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppConfig.STALE_TIMEOUT_CHOICES_MS.forEach { ms ->
                    FilterChip(selected = settings.staleTimeoutMs == ms, onClick = { onUpdate { it.copy(staleTimeoutMs = ms) } }, label = { Text(secLabel(ms)) })
                }
            }
            SwitchRow("Connect automatically on launch", settings.autoConnect) { v -> onUpdate { it.copy(autoConnect = v) } }
        }

        // ---------------- Realtime cloud ----------------
        SectionLabel("Realtime cloud (all transformers live)")
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant)
                CloudChip(fleet.cloud, fleet.publishing)
            }
            Text(
                "Watch every transformer on the map, and share this phone's transformer with everyone. " +
                    "Only encrypted wss:// servers are accepted (plain ws:// only for a private LAN address). " +
                    "Keys are stored in this app's private storage and never shown again in full.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it.take(200); cloudMsg = null },
                label = { Text("Server address") }, placeholder = { Text("wss://your-server.example.com/ws") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = viewerKey, onValueChange = { viewerKey = it.take(128); cloudMsg = null },
                label = { Text("Viewer key (to watch all)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cloudId, onValueChange = { cloudId = it.uppercase().take(24); cloudMsg = null },
                    label = { Text("This transformer ID") }, placeholder = { Text("TR-001") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = deviceToken, onValueChange = { deviceToken = it.take(128); cloudMsg = null },
                label = { Text("Transformer token (to publish)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (cloudMsg != null) {
                Text(cloudMsg!!, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), color = if (cloudErr) StsmnColors.Emergency else StsmnColors.Safe)
            }
            SwitchRow("Realtime cloud on", settings.cloudEnabled) { v ->
                if (v && serverUrl.isBlank()) { cloudErr = true; cloudMsg = "Enter and save the server address first." }
                else onUpdate { it.copy(cloudEnabled = v) }
            }
            Button(
                onClick = {
                    val url = serverUrl.trim()
                    val id = cloudId.trim().uppercase()
                    when {
                        url.isNotEmpty() && !CloudRules.isValidServerUrl(url) -> { cloudErr = true; cloudMsg = "Address must start with wss:// (or ws:// + a private LAN IP)." }
                        id.isNotEmpty() && !CloudRules.isValidDeviceId(id) -> { cloudErr = true; cloudMsg = "ID: letters, digits and dash, 2-24 chars, e.g. TR-001." }
                        id.isNotEmpty() && deviceToken.isBlank() -> { cloudErr = true; cloudMsg = "Enter the token for $id (the server admin creates it)." }
                        else -> {
                            onUpdate { it.copy(serverUrl = url, viewerKey = viewerKey, cloudDeviceId = id, deviceToken = deviceToken) }
                            cloudErr = false; cloudMsg = "Saved."
                        }
                    }
                },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Save cloud settings") }
        }

        // ---------------- Site location (map) ----------------
        SectionLabel("Site location (Live Map)")
        AppCard {
            Text(
                "Fixed coordinates of the transformer. The phone's GPS is not used. Until real coordinates are " +
                    "entered, the map shows a DEMO location.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText, onValueChange = { latText = it; locMessage = null },
                    label = { Text("Latitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lngText, onValueChange = { lngText = it; locMessage = null },
                    label = { Text("Longitude") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            Text("Coordinates are", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sourceSel == LocationSource.DEMO, onClick = { sourceSel = LocationSource.DEMO }, label = { Text("DEMO") })
                FilterChip(selected = sourceSel == LocationSource.MEASURED, onClick = { sourceSel = LocationSource.MEASURED }, label = { Text("Real site") })
            }
            if (locMessage != null) {
                Text(
                    locMessage!!, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    color = if (locIsError) StsmnColors.Emergency else StsmnColors.Safe,
                )
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val lat = latText.trim().replace(',', '.').toDoubleOrNull()
                    val lng = lngText.trim().replace(',', '.').toDoubleOrNull()
                    when {
                        lat == null || lng == null -> { locIsError = true; locMessage = "Enter numbers, e.g. 26.8467 and 80.9462." }
                        !SiteLocationRules.isValid(lat, lng) -> { locIsError = true; locMessage = "Latitude must be -90 to 90 and longitude -180 to 180." }
                        sourceSel == LocationSource.MEASURED && SiteLocationRules.isDemoCoordinate(lat, lng) -> {
                            locIsError = true
                            locMessage = "These are the built-in demo coordinates. Enter the real site or choose DEMO."
                        }
                        else -> {
                            onUpdate { it.copy(siteLat = lat, siteLng = lng, siteSource = sourceSel) }
                            locIsError = false
                            locMessage = if (SiteLocationRules.isInsideUttarPradesh(lat, lng)) "Saved."
                            else "Saved. Note: these coordinates are outside Uttar Pradesh."
                        }
                    }
                }) { Text("Save location") }
                OutlinedButton(onClick = {
                    latText = AppConfig.DEMO_LAT.toString(); lngText = AppConfig.DEMO_LNG.toString()
                    sourceSel = LocationSource.DEMO
                    onUpdate { it.copy(siteLat = AppConfig.DEMO_LAT, siteLng = AppConfig.DEMO_LNG, siteSource = LocationSource.DEMO) }
                    locIsError = false; locMessage = "Reset to the DEMO location."
                }) { Text("Use demo") }
            }
            TextButton(onClick = {
                latText = ""; lngText = ""
                onUpdate { it.copy(siteLat = null, siteLng = null) }
                locIsError = false; locMessage = "Coordinates cleared. The map hides the marker until you enter new ones."
            }) { Text("Clear coordinates") }
        }

        // ---------------- Display ----------------
        SectionLabel("Display")
        AppCard {
            Text("Theme", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.values().forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode, onClick = { onUpdate { it.copy(themeMode = mode) } },
                        label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
            SwitchRow("Keep screen on (exhibition)", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
        }

        // ---------------- Diagnostics ----------------
        SectionLabel("Diagnostics")
        AppCard {
            val s = live.status
            InfoRow("ESP32 address", live.host)
            InfoRow("Phone Wi-Fi network", if (live.wifiBound) "Found" else "Not found")
            InfoRow("Connection", live.connection.name)
            InfoRow("Last valid packet", fmtAge(live.packetAgeMs))
            InfoRow("Packet sequence", s?.seq?.toString() ?: "—")
            InfoRow("Packets received", live.packetsReceived.toString())
            InfoRow("JSON parse errors", live.parseErrors.toString())
            InfoRow("Network errors", live.networkErrors.toString())
            InfoRow("Duplicate packets", live.duplicatePackets.toString())
            InfoRow("Failed polls in a row", live.consecutiveFailures.toString())
            InfoRow("Firmware", s?.fw ?: "—")
            InfoRow("ESP32 uptime", s?.let { fmtUptime(it.uptimeMs) } ?: "—")
            InfoRow("Relay state", s?.let { if (it.relay) "ON (supply allowed)" else "OFF (cutoff)" } ?: "—")
            InfoRow("Temperature sensor", validity(s?.temperature != null, s != null))
            InfoRow("Voltage sensor", validity(s?.voltage != null, s != null))
            InfoRow("Current sensor", validity(s?.current != null, s != null))
            InfoRow("Oil level sensor", validity(s?.oilLevel != null, s != null))
            if (live.lastError != null) {
                Text("Last error: ${live.lastError}", color = StsmnColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---------------- Safety ----------------
        SectionLabel("Safety")
        AppCard {
            Text("Protection runs on the ESP32", fontWeight = FontWeight.Bold)
            Text(
                "Risk thresholds and the automatic cutoff are set in the ESP32 firmware (config.h) and cannot be changed from " +
                    "this app. If this phone is off or disconnected, the ESP32 keeps protecting the system.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---------------- Data / About ----------------
        SectionLabel("Data")
        AppCard {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear event history") }
        }
        SectionLabel("About")
        AppCard {
            InfoRow("App", "STSMN Monitor")
            InfoRow("Version", BuildConfig.VERSION_NAME)
            InfoRow("Link", if (settings.cloudEnabled) "Local Wi-Fi + realtime cloud" else "Local Wi-Fi · no internet needed")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear event history?") },
            text = { Text("All stored events will be deleted from this phone.") },
            confirmButton = { TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun secLabel(ms: Long): String = if (ms % 1000L == 0L) "${ms / 1000} s" else "${ms / 1000.0} s"

private fun validity(valid: Boolean, haveData: Boolean): String = when {
    !haveData -> "—"
    valid -> "Valid"
    else -> "Unavailable"
}
