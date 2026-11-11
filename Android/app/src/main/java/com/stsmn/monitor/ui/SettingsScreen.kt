@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.stsmn.monitor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
    onRequestBlePermission: () -> Unit,
    onDisconnect: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenProfile: () -> Unit,
    contactStatus: String?,
    whatsappStatus: String?,
    onSaveContact: (String, String) -> String?,
    flameTuning: com.stsmn.monitor.domain.FlameTuning?,
    flameLive: com.stsmn.monitor.domain.FlameLive?,
    flameStatus: String?,
    onRefreshFlame: () -> Unit,
    onPushFlame: (com.stsmn.monitor.domain.FlameTuning, Boolean) -> Unit,
) {
    var phoneText by rememberSaveable { mutableStateOf(settings.alertPhone) }
    var waKeyText by rememberSaveable { mutableStateOf(settings.whatsappKey) }
    var contactErr by rememberSaveable { mutableStateOf<String?>(null) }
    var host by rememberSaveable { mutableStateOf(settings.deviceHost) }
    var blePw by rememberSaveable { mutableStateOf(settings.blePassword) }
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
            Text("Link to the ESP32", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !settings.useBluetooth, onClick = { onUpdate { it.copy(useBluetooth = false) } }, label = { Text("Wi-Fi") })
                FilterChip(selected = settings.useBluetooth, onClick = { onUpdate { it.copy(useBluetooth = true) }; onRequestBlePermission() }, label = { Text("Bluetooth") })
            }
            if (settings.useBluetooth) {
                Text(
                    live.linkNote.ifBlank { "Bluetooth: press Connect" },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "With Bluetooth the phone keeps its normal internet, so WhatsApp can be sent automatically while the app is open.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedButton(onClick = onRequestBlePermission, modifier = Modifier.padding(top = 6.dp)) { Text("Allow Bluetooth permission") }
                OutlinedTextField(
                    value = blePw, onValueChange = { blePw = it.filter { c -> !c.isWhitespace() }.take(64) },
                    label = { Text("Device password (only to save the SMS number over Bluetooth)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(onClick = { onUpdate { it.copy(blePassword = blePw) } }, modifier = Modifier.padding(top = 6.dp)) { Text("Save password") }
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
        // ---------------- Flame sensor tuning (live on the ESP32) ----------------
        SectionLabel("Flame sensor (live on ESP32)")
        FlameTuningCard(flameTuning, flameLive, flameStatus, live.connection == com.stsmn.monitor.domain.ConnectionState.LIVE || live.connection == com.stsmn.monitor.domain.ConnectionState.STALE, onRefreshFlame, onPushFlame)

        // ---------------- Alert contact ----------------
        SectionLabel("Alert number (SMS + WhatsApp)")
        AppCard {
            Text(
                "Save YOUR mobile number here. In an EMERGENCY the ESP32 sends an SMS to it from its SIM module (needs a 2G SIM with credit), " +
                    "and the app prepares a WhatsApp message to the same number. The number is stored on the ESP32 and in this app only.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = phoneText, onValueChange = { phoneText = it.take(20); contactErr = null },
                label = { Text("Mobile number") }, placeholder = { Text("75050 45948  or  +91 75050 45948") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = waKeyText, onValueChange = { waKeyText = it.take(64); contactErr = null },
                label = { Text("WhatsApp auto-send key (optional)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "WhatsApp is sent automatically by the app (no button) while it is open and the phone has internet, using the free " +
                    "callmebot.com service. One-time setup there: get your key for this number, then paste it above. " +
                    "That service then sees the number and the alert text. Without a key only the SMS from the ESP32 is sent.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
            Button(onClick = { contactErr = onSaveContact(phoneText, waKeyText) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Save number") }
            contactErr?.let { Text(it, color = StsmnColors.Emergency, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            contactStatus?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            whatsappStatus?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            live.deviceAlertPhone?.let {
                Text(if (it.isEmpty()) "ESP32: no SMS number saved yet." else "ESP32 SMS number: $it",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

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
            Text("Brightness (used by the \"System\" theme)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
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

        // ---------------- Appearance (UI-only options, stored separately from the app settings) ----------------
        val uiStore = LocalUiPrefsStore.current
        if (uiStore != null) {
            val ui = uiStore.current
            val accent = MaterialTheme.colorScheme.primary
            SectionLabel("Text size & scaling")
            Text("Adjust the text size of the whole app for comfortable reading:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextSizeChoice.values().forEach { c ->
                    val on = ui.textSize == c
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(if (on) 2.dp else 1.dp, if (on) accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .clickable { uiStore.update { it.copy(textSize = c) } }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(c.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) accent else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text("(${c.factor}x)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }

            SectionLabel("App theme & colour palette")
            Text("Choose your favourite colour style (${AppTheme.values().size} themes):", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppTheme.values().toList().chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { t ->
                        val on = ui.theme == t
                        Row(
                            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(if (on) 2.dp else 1.dp, if (on) accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                                .clickable { uiStore.update { it.copy(theme = t) } }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(t.palette.accent).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                            Text(t.label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            Text("Corners", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerChoice.values().forEach { c ->
                    FilterChip(selected = ui.corners == c, onClick = { uiStore.update { it.copy(corners = c) } }, label = { Text(c.label) })
                }
            }
            AppCard { SwitchRow("Reduce motion (fewer animations)", ui.reduceMotion) { v -> uiStore.update { it.copy(reduceMotion = v) } } }
            OutlinedButton(
                onClick = { uiStore.update { UiPrefs() } }, modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, accent),
            ) { Text("Reset to STSMN defaults", color = accent) }
            Text(
                "\"System\" theme follows Light / Dark / System under Display. Appearance only changes how the app looks.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun FlameTuningCard(
    tuning: com.stsmn.monitor.domain.FlameTuning?,
    liveFlame: com.stsmn.monitor.domain.FlameLive?,
    status: String?,
    connected: Boolean,
    onRefresh: () -> Unit,
    onPush: (com.stsmn.monitor.domain.FlameTuning, Boolean) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(connected) {
        while (connected) { onRefresh(); kotlinx.coroutines.delay(1500) }
    }
    AppCard {
        if (!connected) { Text("Connect to the ESP32 over Wi-Fi to change the flame settings.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return@AppCard }
        if (tuning == null) { Text(status ?: "Reading flame settings from the ESP32...", fontSize = 13.sp); return@AppCard }
        var local by androidx.compose.runtime.remember(tuning) { mutableStateOf(tuning) }
        liveFlame?.let {
            Text(
                if (!it.usable) "Sensor: " + when (it.why) { 1 -> "learning the normal light (keep flame away)"; 2 -> "AO wire noisy: DO-only mode, only a flickering DO counts"; 3 -> "AO direction wrong: DO-only mode"; else -> "AO not usable" } + ", DO ${if (it.doOn) "ON" else "off"}"
                else "Sensor now: normal light ${it.base} mV, change ${it.dev} mV, flicker ${it.reversals}${if (it.flicker) " (flame-like)" else ""}, DO ${if (it.doOn) "ON" else "off"}, signal ${it.pct}%" + if (it.critical) "  FLAME!" else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TuneSlider("Sensitivity: needs ${local.minDelta} mV change", "Higher = ignores light better, needs a bigger flame", local.minDelta.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.MIN_DELTA_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.MIN_DELTA_RANGE.last.toFloat(), 0,
            { local = local.copy(minDelta = it.toInt()) }, { onPush(local, false) })
        TuneSlider("Flicker strictness: ${"%.1f".format(local.flicker)}", "Higher = steady light/noise ignored more, flame must flicker harder", local.flicker.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.FLICKER_RANGE.start.toFloat()..com.stsmn.monitor.domain.FlameTuning.FLICKER_RANGE.endInclusive.toFloat(), 0,
            { local = local.copy(flicker = it.toDouble()) }, { onPush(local, false) })
        TuneSlider("Flicker count: ${local.reversals}", "Higher = needs more flicker in 0.6 s", local.reversals.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.REVERSALS_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.REVERSALS_RANGE.last.toFloat(), 0,
            { local = local.copy(reversals = it.toInt()) }, { onPush(local, false) })
        TuneSlider("Confirm time: ${local.confirmMs} ms", "Higher = slower alarm, fewer false alarms", local.confirmMs.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.CONFIRM_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.CONFIRM_RANGE.last.toFloat(), 0,
            { local = local.copy(confirmMs = (it / 50f).toInt() * 50) }, { onPush(local, false) })
        TuneSlider("Accept steady light after: ${local.rebaseSec} s", "Sunlight/lamp that does not flicker becomes normal after this time", local.rebaseSec.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.REBASE_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.REBASE_RANGE.last.toFloat(), 0,
            { local = local.copy(rebaseSec = it.toInt()) }, { onPush(local, false) })
        SwitchRow("DO pin must agree (fewer false alarms)", local.requireDo) { local = local.copy(requireDo = it); onPush(local, false) }
        OutlinedButton(onClick = { onPush(local, true) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Re-learn light now (no flame in front!)") }
        status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun TuneSlider(title: String, hint: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 10.dp))
    androidx.compose.material3.Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, onValueChangeFinished = onDone)
    Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun secLabel(ms: Long): String = if (ms % 1000L == 0L) "${ms / 1000} s" else "${ms / 1000.0} s"

private fun validity(valid: Boolean, haveData: Boolean): String = when {
    !haveData -> "—"
    valid -> "Valid"
    else -> "Unavailable"
}
