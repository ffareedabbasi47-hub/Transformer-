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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
    DASHBOARD("Home", Icons.Filled.Home, null),
    HISTORY("History", Icons.Filled.DateRange, null),
    DIAGNOSTICS("Health", Icons.Filled.Build, null),
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
    var mapMode by rememberSaveable { mutableIntStateOf(0) } // 0 = 3D globe map (default), 1 = flat map (backup), 2 = all transformers
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

        GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    tonalElevation = 0.dp,
                ) {
                    Tab.values().filter { it.icon != null }.forEach { t ->
                        NavigationBarItem(
                            selected = current == t || current.parent == t,
                            onClick = { tab = t.ordinal },
                            icon = { Icon(t.icon!!, contentDescription = t.label) },
                            // Short one-line labels: never wrapped or cut off on small phones.
                            label = { Text(t.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
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
                        MapModeSwitch(mapMode, onChange = { mapMode = it })
                        Box(Modifier.weight(1f)) {
                            when (mapMode) {
                                2 -> FleetScreen(
                                    devices, fleet, settings.cloudEnabled,
                                    onOpenOnMap = { id -> selectedId = id; mapMode = 0 },
                                    onOpenSettings = { tab = Tab.SETTINGS.ordinal },
                                )
                                1 -> MapScreen(
                                    devices, fleet, settings.cloudEnabled, selectedId,
                                    onSelect = { selectedId = it }, onOpenSettings = { tab = Tab.SETTINGS.ordinal },
                                )
                                else -> GlobeScreen(
                                    devices, fleet, settings.cloudEnabled, selectedId,
                                    onSelect = { selectedId = it }, onOpenSettings = { tab = Tab.SETTINGS.ordinal },
                                    onUseFlatMap = { mapMode = 1 },
                                )
                            }
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
        } // GlassBackground
    }
}

/** Three views of the same live data: 3D globe map (default), flat map (backup), list of every transformer. */
@Composable
private fun MapModeSwitch(mode: Int, onChange: (Int) -> Unit) {
    val names = listOf("3D Map", "Flat", "All")
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEachIndexed { i, n ->
            if (i == mode) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(n, maxLines = 1, softWrap = false) }
            else OutlinedButton(onClick = { onChange(i) }, modifier = Modifier.weight(1f)) { Text(n, maxLines = 1, softWrap = false) }
        }
    }
}
