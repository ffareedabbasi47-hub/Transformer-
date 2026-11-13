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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    resetStatus: String?,
    onReset: () -> Unit,
) {
    var espTok by rememberSaveable { mutableStateOf(settings.espToken) }
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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = LocalBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Settings", "Connection, realtime cloud aur display")
        OutlinedButton(onClick = onOpenProfile, modifier = Modifier.fillMaxWidth()) { Text("Profile · ${settings.profileName.ifBlank { "Naam nahi daala" }}") }

        SectionLabel("Connection")
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConnectionChip(live.connection)
            }
            Text("ESP32 se kaise judna hai", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !settings.useBluetooth, onClick = { onUpdate { it.copy(useBluetooth = false) } }, label = { Text("Wi-Fi") })
                FilterChip(selected = settings.useBluetooth, onClick = { onUpdate { it.copy(useBluetooth = true) }; onRequestBlePermission() }, label = { Text("Bluetooth") })
            }
            if (settings.useBluetooth) {
                Text(
                    live.linkNote.ifBlank { "Bluetooth: Connect dabao" },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Bluetooth se phone ka normal internet chalta rehta hai, isliye app khula hone par WhatsApp apne aap bhej sakte hain.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedButton(onClick = onRequestBlePermission, modifier = Modifier.padding(top = 6.dp)) { Text("Bluetooth ki permission do") }
                OutlinedTextField(
                    value = blePw, onValueChange = { blePw = it.filter { c -> !c.isWhitespace() }.take(64) },
                    label = { Text("Device password (sirf Bluetooth se SMS number save karne ke liye)") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(onClick = { onUpdate { it.copy(blePassword = blePw) } }, modifier = Modifier.padding(top = 6.dp)) { Text("Password save karo") }
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
                label = { Text("ESP32 ka address") }, singleLine = true, isError = hostError,
                supportingText = { Text(if (hostError) "Private IP address daalo, jaise 192.168.4.1 (192.168.x.x, 10.x.x.x ya 172.16-31.x.x)" else "Firmware ka default (config.h me set hai): ${AppConfig.DEFAULT_DEVICE_HOST}") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = deviceName, onValueChange = { deviceName = it.take(AppConfig.MAX_TEXT_LENGTH) },
                label = { Text("Device ka naam") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = {
                    if (SettingsStore.isValidHost(host)) {
                        onUpdate { it.copy(deviceHost = host.trim(), deviceName = deviceName) }
                    } else hostError = true
                },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Save karo") }

            Text("Refresh kitni der me", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppConfig.POLL_INTERVAL_CHOICES_MS.forEach { ms ->
                    FilterChip(selected = settings.pollIntervalMs == ms, onClick = { onUpdate { it.copy(pollIntervalMs = ms) } }, label = { Text(secLabel(ms)) })
                }
            }
            Text("Data purana kab maane", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppConfig.STALE_TIMEOUT_CHOICES_MS.forEach { ms ->
                    FilterChip(selected = settings.staleTimeoutMs == ms, onClick = { onUpdate { it.copy(staleTimeoutMs = ms) } }, label = { Text(secLabel(ms)) })
                }
            }
            SwitchRow("App khulte hi apne aap connect karo", settings.autoConnect) { v -> onUpdate { it.copy(autoConnect = v) } }
        }

        SectionLabel("ESP32 password aur over-current lock")
        AppCard {
            Text(
                "Ye wahi hai jo ESP32 ka Wi-Fi password hai. Flame settings badalne, alert number save karne ya lock reset karne ke liye chahiye. Ye sirf simple password hai, encryption nahi.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = espTok, onValueChange = { espTok = it.take(64) }, label = { Text("ESP32 password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(onClick = { onUpdate { it.copy(espToken = espTok) } }, modifier = Modifier.padding(top = 6.dp)) { Text("Password save karo") }
            val st = live.status
            if (st != null && st.latched) {
                Text(
                    "OVER-CURRENT LOCK LAGA HAI: supply band rahegi." + if (st.resetWaitS > 0) " ${st.resetWaitS} second baad reset ho payega." else " Pehle dikkat theek karo, phir reset karo.",
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp),
                )
                Button(onClick = onReset, enabled = st.resetWaitS == 0, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Over-current lock reset karo") }
            }
            resetStatus?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
        }

        SectionLabel("Flame sensor (ESP32 par live)")
        FlameTuningCard(flameTuning, flameLive, flameStatus, live.connection == com.stsmn.monitor.domain.ConnectionState.LIVE || live.connection == com.stsmn.monitor.domain.ConnectionState.STALE, onRefreshFlame, onPushFlame)

        SectionLabel("Alert number (SMS + WhatsApp)")
        AppCard {
            Text(
                "Yaha apna mobile number save karo. EMERGENCY me ESP32 apne SIM module se is number par SMS bhejta hai (2G SIM aur balance chahiye), " +
                    "aur app usi number par WhatsApp message tayyar karti hai. Number sirf ESP32 me aur is app me store hota hai.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = phoneText, onValueChange = { phoneText = it.take(20); contactErr = null },
                label = { Text("Mobile number") }, placeholder = { Text("75050 45948  ya  +91 75050 45948") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = waKeyText, onValueChange = { waKeyText = it.take(64); contactErr = null },
                label = { Text("WhatsApp auto-send key (zaroori nahi)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "WhatsApp app khud bhejti hai (koi button nahi), jab app khuli ho aur phone me internet ho. Ye free callmebot.com service se hota hai. " +
                    "Wahan ek baar setup karna padta hai: is number ki key lo aur upar paste karo. " +
                    "Us service ko number aur alert ka text dikhta hai. Key ke bina sirf ESP32 ka SMS jayega.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
            Button(onClick = { contactErr = onSaveContact(phoneText, waKeyText) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Number save karo") }
            contactErr?.let { Text(it, color = StsmnColors.Emergency, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            contactStatus?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            whatsappStatus?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            live.deviceAlertPhone?.let {
                Text(if (it.isEmpty()) "ESP32 me abhi tak SMS number save nahi hai." else "ESP32 ka SMS number: $it",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        SectionLabel("Realtime cloud (saare transformer live)")
        AppCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant)
                CloudChip(fleet.cloud, fleet.publishing)
            }
            Text(
                "Saare transformer map par dekho, aur is phone ke transformer ko sabke saath share karo. " +
                    "Sirf encrypted wss:// server chalte hain (plain ws:// sirf private LAN address par). " +
                    "Keys is app ki private storage me rehti hain aur dobara poori kabhi nahi dikhti.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it.take(200); cloudMsg = null },
                label = { Text("Server ka address") }, placeholder = { Text("wss://your-server.example.com/ws") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            OutlinedTextField(
                value = viewerKey, onValueChange = { viewerKey = it.take(128); cloudMsg = null },
                label = { Text("Viewer key (sab dekhne ke liye)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cloudId, onValueChange = { cloudId = it.uppercase().take(24); cloudMsg = null },
                    label = { Text("Is transformer ki ID") }, placeholder = { Text("TR-001") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = deviceToken, onValueChange = { deviceToken = it.take(128); cloudMsg = null },
                label = { Text("Transformer token (publish karne ke liye)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (cloudMsg != null) {
                Text(cloudMsg!!, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp), color = if (cloudErr) StsmnColors.Emergency else StsmnColors.Safe)
            }
            SwitchRow("Realtime cloud chalu", settings.cloudEnabled) { v ->
                if (v && serverUrl.isBlank()) { cloudErr = true; cloudMsg = "Pehle server ka address daalo aur save karo." }
                else onUpdate { it.copy(cloudEnabled = v) }
            }
            Button(
                onClick = {
                    val url = serverUrl.trim()
                    val id = cloudId.trim().uppercase()
                    when {
                        url.isNotEmpty() && !CloudRules.isValidServerUrl(url) -> { cloudErr = true; cloudMsg = "Address wss:// se shuru hona chahiye (ya ws:// ke saath private LAN IP)." }
                        id.isNotEmpty() && !CloudRules.isValidDeviceId(id) -> { cloudErr = true; cloudMsg = "ID me sirf letters, digits aur dash chalenge, 2 se 24 character, jaise TR-001." }
                        id.isNotEmpty() && deviceToken.isBlank() -> { cloudErr = true; cloudMsg = "$id ka token daalo (server admin banata hai)." }
                        else -> {
                            onUpdate { it.copy(serverUrl = url, viewerKey = viewerKey, cloudDeviceId = id, deviceToken = deviceToken) }
                            cloudErr = false; cloudMsg = "Save ho gaya."
                        }
                    }
                },
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Cloud settings save karo") }
        }

        SectionLabel("Site ki location (Live Map)")
        AppCard {
            Text(
                "Transformer ke fixed coordinates. Phone ka GPS use nahi hota. Jab tak asli coordinates " +
                    "nahi daalte, map par DEMO location dikhti hai.",
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
            Text("Coordinates ye hain", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sourceSel == LocationSource.DEMO, onClick = { sourceSel = LocationSource.DEMO }, label = { Text("DEMO") })
                FilterChip(selected = sourceSel == LocationSource.MEASURED, onClick = { sourceSel = LocationSource.MEASURED }, label = { Text("Asli site") })
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
                        lat == null || lng == null -> { locIsError = true; locMessage = "Number daalo, jaise 26.8467 aur 80.9462." }
                        !SiteLocationRules.isValid(lat, lng) -> { locIsError = true; locMessage = "Latitude -90 se 90 ke beech aur longitude -180 se 180 ke beech hona chahiye." }
                        sourceSel == LocationSource.MEASURED && SiteLocationRules.isDemoCoordinate(lat, lng) -> {
                            locIsError = true
                            locMessage = "Ye app ke built-in demo coordinates hain. Asli site daalo ya DEMO chuno."
                        }
                        else -> {
                            onUpdate { it.copy(siteLat = lat, siteLng = lng, siteSource = sourceSel) }
                            locIsError = false
                            locMessage = if (SiteLocationRules.isInsideUttarPradesh(lat, lng)) "Save ho gaya."
                            else "Save ho gaya. Dhyan do: ye coordinates Uttar Pradesh ke bahar hain."
                        }
                    }
                }) { Text("Location save karo") }
                OutlinedButton(onClick = {
                    latText = AppConfig.DEMO_LAT.toString(); lngText = AppConfig.DEMO_LNG.toString()
                    sourceSel = LocationSource.DEMO
                    onUpdate { it.copy(siteLat = AppConfig.DEMO_LAT, siteLng = AppConfig.DEMO_LNG, siteSource = LocationSource.DEMO) }
                    locIsError = false; locMessage = "DEMO location par wapas aa gaye."
                }) { Text("Demo use karo") }
            }
            TextButton(onClick = {
                latText = ""; lngText = ""
                onUpdate { it.copy(siteLat = null, siteLng = null) }
                locIsError = false; locMessage = "Coordinates hat gaye. Naye daalne tak map par marker nahi dikhega."
            }) { Text("Coordinates hatao") }
        }

        SectionLabel("Display")
        AppCard {
            Text("Brightness (\"System\" theme me kaam aati hai)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.values().forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode, onClick = { onUpdate { it.copy(themeMode = mode) } },
                        label = { Text(mode.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
            SwitchRow("Screen chalu rakho (exhibition ke liye)", settings.keepScreenOn) { v -> onUpdate { it.copy(keepScreenOn = v) } }
        }

        val uiStore = LocalUiPrefsStore.current
        if (uiStore != null) {
            val ui = uiStore.current
            val accent = MaterialTheme.colorScheme.primary
            SectionLabel("Text ka size")
            Text("Aaram se padhne ke liye poore app ka text size badlo:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            SectionLabel("App ki theme aur rang")
            Text("Apna pasandida rang chuno (${AppTheme.values().size} themes):", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Kone (corners)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerChoice.values().forEach { c ->
                    FilterChip(selected = ui.corners == c, onClick = { uiStore.update { it.copy(corners = c) } }, label = { Text(c.label) })
                }
            }
            AppCard { SwitchRow("Animation kam karo", ui.reduceMotion) { v -> uiStore.update { it.copy(reduceMotion = v) } } }
            OutlinedButton(
                onClick = { uiStore.update { UiPrefs() } }, modifier = Modifier.fillMaxWidth(),
            ) { Text("STSMN ke default par wapas", color = accent) }
            Text(
                "\"System\" theme, Display me chuni hui Light / Dark / System ke hisaab se chalti hai. Appearance sirf app ka look badalti hai.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionLabel("Diagnostics")
        AppCard {
            val s = live.status
            InfoRow("ESP32 ka address", live.host)
            InfoRow("Phone ka Wi-Fi network", if (live.wifiBound) "Mila" else "Nahi mila")
            InfoRow("Connection", live.connection.name)
            InfoRow("Aakhri sahi packet", fmtAge(live.packetAgeMs))
            InfoRow("Packet number", s?.seq?.toString() ?: "—")
            InfoRow("Mile hue packets", live.packetsReceived.toString())
            InfoRow("JSON padhne ki galtiyan", live.parseErrors.toString())
            InfoRow("Network ki galtiyan", live.networkErrors.toString())
            InfoRow("Dohrae hue packets", live.duplicatePackets.toString())
            InfoRow("Lagataar fail hue polls", live.consecutiveFailures.toString())
            InfoRow("Firmware", s?.fw ?: "—")
            InfoRow("ESP32 kitni der se chal raha hai", s?.let { fmtUptime(it.uptimeMs) } ?: "—")
            InfoRow("Relay ki halat", s?.let { if (it.relay) "ON (supply chalu)" else "OFF (supply band)" } ?: "—")
            InfoRow("Temperature sensor", validity(s?.temperature != null, s != null))
            InfoRow("Voltage sensor", validity(s?.voltage != null, s != null))
            InfoRow("Current sensor", validity(s?.current != null, s != null))
            InfoRow("Oil level sensor", validity(s?.oilLevel != null, s != null))
            if (live.lastError != null) {
                Text("Aakhri error: ${live.lastError}", color = StsmnColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        SectionLabel("Safety")
        AppCard {
            Text("Protection ESP32 par chalta hai", fontWeight = FontWeight.Bold)
            Text(
                "Risk ki limits aur automatic cutoff ESP32 ke firmware (config.h) me set hain, is app se badal nahi sakte. " +
                    "Phone band ho ya connect na ho, tab bhi ESP32 system ko bachata rehta hai.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionLabel("Data")
        AppCard {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Event history saaf karo") }
        }
        SectionLabel("About")
        AppCard {
            InfoRow("App", "STSMN Monitor")
            InfoRow("Version", BuildConfig.VERSION_NAME)
            InfoRow("Link", if (settings.cloudEnabled) "Local Wi-Fi + realtime cloud" else "Local Wi-Fi · internet ki zaroorat nahi")
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Event history saaf karein?") },
            text = { Text("Is phone se saare saved events delete ho jayenge.") },
            confirmButton = { TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("Saaf karo") } },
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
        if (!connected) { Text("Flame settings badalne ke liye ESP32 se Wi-Fi par connect ho jao.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return@AppCard }
        if (tuning == null) { Text(status ?: "ESP32 se flame settings padh rahe hain...", fontSize = 13.sp); return@AppCard }
        var local by androidx.compose.runtime.remember(tuning) { mutableStateOf(tuning) }
        liveFlame?.let {
            Text(
                if (!it.usable) "Sensor: " + when (it.why) { 1 -> "normal roshni seekh raha hai (aag door rakho)"; 2 -> "AO wire me noise hai: sirf DO mode, sirf flicker karta DO maana jayega"; 3 -> "AO ki direction ulti hai: sirf DO mode"; else -> "AO kaam ka nahi" } + ", DO ${if (it.doOn) "ON" else "off"}"
                else "Sensor abhi: normal roshni ${it.base} mV, badlav ${it.dev} mV, flicker ${it.reversals}${if (it.flicker) " (aag jaisa)" else ""}, DO ${if (it.doOn) "ON" else "off"}, signal ${it.pct}%" + if (it.critical) "  FLAME!" else "",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TuneSlider("Sensitivity: ${local.minDelta} mV badlav chahiye", "Zyada = roshni ko behtar ignore karta hai, badi aag chahiye", local.minDelta.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.MIN_DELTA_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.MIN_DELTA_RANGE.last.toFloat(), 0,
            { local = local.copy(minDelta = it.toInt()) }, { onPush(local, false) })
        TuneSlider("Flicker ki sakhti: ${"%.1f".format(local.flicker)}", "Zyada = steady roshni/noise ignore hota hai, aag ko zyada flicker karna padega", local.flicker.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.FLICKER_RANGE.start.toFloat()..com.stsmn.monitor.domain.FlameTuning.FLICKER_RANGE.endInclusive.toFloat(), 0,
            { local = local.copy(flicker = it.toDouble()) }, { onPush(local, false) })
        TuneSlider("Flicker ginti: ${local.reversals}", "Zyada = 0.6 second me zyada flicker chahiye", local.reversals.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.REVERSALS_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.REVERSALS_RANGE.last.toFloat(), 0,
            { local = local.copy(reversals = it.toInt()) }, { onPush(local, false) })
        TuneSlider("Confirm hone ka time: ${local.confirmMs} ms", "Zyada = alarm der se bajega, galat alarm kam", local.confirmMs.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.CONFIRM_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.CONFIRM_RANGE.last.toFloat(), 0,
            { local = local.copy(confirmMs = (it / 50f).toInt() * 50) }, { onPush(local, false) })
        TuneSlider("Steady roshni itni der baad normal maano: ${local.rebaseSec} s", "Dhoop/lamp jo flicker nahi karti, itne time baad normal maani jayegi", local.rebaseSec.toFloat(),
            com.stsmn.monitor.domain.FlameTuning.REBASE_RANGE.first.toFloat()..com.stsmn.monitor.domain.FlameTuning.REBASE_RANGE.last.toFloat(), 0,
            { local = local.copy(rebaseSec = it.toInt()) }, { onPush(local, false) })
        SwitchRow("DO pin bhi sahmat ho (galat alarm kam)", local.requireDo) { local = local.copy(requireDo = it); onPush(local, false) }
        SwitchRow("AO ki direction ulti karo (sirf tab jab andhere me AO ghate)", local.invert) { local = local.copy(invert = it); onPush(local, false) }
        OutlinedButton(onClick = { onPush(local, true) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Roshni abhi dobara seekho (aag saamne na ho!)") }
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
    valid -> "Sahi"
    else -> "Nahi mil raha"
}
