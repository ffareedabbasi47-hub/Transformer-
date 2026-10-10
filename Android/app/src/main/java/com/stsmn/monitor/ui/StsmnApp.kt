package com.stsmn.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import com.stsmn.monitor.domain.ConnectionState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stsmn.monitor.MainViewModel
import com.stsmn.monitor.domain.LocationSource
import com.stsmn.monitor.domain.MapDevices

/** Bottom bar shows 5 tabs (Material guidance). SENSORS / DIAGNOSTICS / USER are sub-screens opened from Home / Settings. */
private enum class Tab(val label: String, val icon: ImageVector?, val parent: Tab?) {
    DASHBOARD("Dashboard", Icons.Filled.Home, null),
    HISTORY("History", Icons.Filled.DateRange, null),
    DIAGNOSTICS("Diagnostics", Icons.Filled.Build, null),
    MAP("Map", Icons.Filled.Place, null),   // contains a Map | Fleet list switch
    SETTINGS("Settings", Icons.Filled.Settings, null),
    SENSORS("Sensor Data", null, DASHBOARD),
    USER("Profile", null, SETTINGS),
}

@Composable
fun StsmnApp(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val fleet by vm.fleet.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFleetList by rememberSaveable { mutableStateOf(false) }
    val nowElapsed by rememberElapsedNow()
    var showSplash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1300); showSplash = false }

    val current = Tab.values()[tab.coerceIn(0, Tab.values().size - 1)]
    val devices = MapDevices.build(
        live, settings.siteLat, settings.siteLng, settings.siteSource == LocationSource.DEMO, fleet, nowElapsed,
    )

    StsmnTheme(settings.themeMode) {
        if (showSplash) { SplashScreen(); return@StsmnTheme }
        val view = LocalView.current
        DisposableEffect(settings.keepScreenOn) {
            view.keepScreenOn = settings.keepScreenOn
            onDispose { view.keepScreenOn = false }
        }
        // Back from a sub-screen returns to its parent tab instead of closing the app.
        BackHandler(enabled = current.parent != null) { tab = current.parent!!.ordinal }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.values().filter { it.icon != null }.forEach { t ->
                        NavigationBarItem(
                            selected = current == t || current.parent == t,
                            onClick = { tab = t.ordinal },
                            icon = { Icon(t.icon!!, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
              if (current.parent != null) {
                  TextButton(onClick = { tab = current.parent!!.ordinal }, modifier = Modifier.padding(start = 8.dp)) {
                      Text("‹  ${current.parent!!.label}")
                  }
              }
              Box(Modifier.fillMaxSize()) {
                when (current) {
                    Tab.DASHBOARD ->
                        if (live.connection == ConnectionState.IDLE && live.status == null) {
                            ConnectScreen(live, settings, onConnect = vm::connect)
                        } else DashboardScreen(
                            live, settings, fleet, onConnect = vm::connect,
                            onOpenSensors = { tab = Tab.SENSORS.ordinal }, onOpenDiagnostics = { tab = Tab.DIAGNOSTICS.ordinal },
                        )
                    Tab.MAP -> Column(Modifier.fillMaxSize()) {
                        MapListSwitch(showFleetList, onChange = { showFleetList = it })
                        Box(Modifier.weight(1f)) {
                            if (showFleetList) FleetScreen(
                                devices, fleet, settings.cloudEnabled,
                                onOpenOnMap = { id -> selectedId = id; showFleetList = false },
                                onOpenSettings = { tab = Tab.SETTINGS.ordinal },
                            ) else MapScreen(
                                devices, fleet, settings.cloudEnabled, selectedId,
                                onSelect = { selectedId = it }, onOpenSettings = { tab = Tab.SETTINGS.ordinal },
                            )
                        }
                    }
                    Tab.HISTORY -> HistoryScreen(live, events, onClear = vm::clearHistory)
                    Tab.SENSORS -> SensorsScreen(live)
                    Tab.DIAGNOSTICS -> DiagnosticsScreen(live, fleet, settings.cloudEnabled)
                    Tab.USER -> UserScreen(settings) { name, role, school ->
                        vm.updateSettings { it.copy(profileName = name, profileRole = role, profileSchool = school) }
                    }
                    Tab.SETTINGS -> SettingsScreen(
                        live = live,
                        settings = settings,
                        fleet = fleet,
                        onUpdate = vm::updateSettings,
                        onConnect = vm::connect,
                        onDisconnect = vm::disconnect,
                        onClearHistory = vm::clearHistory,
                        onOpenProfile = { tab = Tab.USER.ordinal },
                    )
                }
              }
            }
        }
    }
}

/** Two big buttons: live map or the list of every transformer (same data, two views). */
@Composable
private fun MapListSwitch(showList: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!showList) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Map") }
        else OutlinedButton(onClick = { onChange(false) }, modifier = Modifier.weight(1f)) { Text("Map") }
        if (showList) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("All transformers") }
        else OutlinedButton(onClick = { onChange(true) }, modifier = Modifier.weight(1f)) { Text("All transformers") }
    }
}
