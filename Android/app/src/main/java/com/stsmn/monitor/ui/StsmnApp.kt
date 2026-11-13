package com.stsmn.monitor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import com.stsmn.monitor.data.BlePermissions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
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

private enum class Tab(val label: String, val icon: ImageVector?, val parent: Tab?) {
    DASHBOARD("Home", Icons.Filled.Home, null),
    HISTORY("History", Icons.Filled.DateRange, null),
    DIAGNOSTICS("Health", Icons.Filled.Build, null),
    MAP("Map", Icons.Filled.Place, null),
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
    val contactStatus by vm.contactStatus.collectAsStateWithLifecycle()
    val whatsappStatus by vm.whatsappStatus.collectAsStateWithLifecycle()
    val appContext = LocalContext.current

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.connect() }
    val connectWithPerm: () -> Unit = {
        if (settings.useBluetooth && !BlePermissions.granted(appContext)) permLauncher.launch(BlePermissions.required()) else vm.connect()
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val nowElapsed by rememberElapsedNow()
    var showSplash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1300); showSplash = false }

    val current = Tab.values()[tab.coerceIn(0, Tab.values().size - 1)]
    val devices = MapDevices.build(
        live, settings.siteLat, settings.siteLng, settings.siteSource == LocationSource.DEMO, fleet, nowElapsed,
    )

    val uiCtx = androidx.compose.ui.platform.LocalContext.current
    val uiStore = remember { UiPrefsStore(uiCtx) }
    CompositionLocalProvider(LocalUiPrefsStore provides uiStore) {
    StsmnTheme(settings.themeMode) {
        if (showSplash) { SplashScreen(); return@StsmnTheme }
        val view = LocalView.current
        DisposableEffect(settings.keepScreenOn) {
            view.keepScreenOn = settings.keepScreenOn
            onDispose { view.keepScreenOn = false }
        }

        val fullNow = fullscreen && current == Tab.MAP
        LaunchedEffect(current) { if (current != Tab.MAP) fullscreen = false }
        DisposableEffect(fullNow) {
            var c: android.content.Context? = view.context
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            val window = (c as? android.app.Activity)?.window
            val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
            if (fullNow) {
                ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            onDispose { ctl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
        BackHandler(enabled = current.parent != null) { tab = current.parent!!.ordinal }

        GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                if (!fullNow) AurelionNavBar(
                    tabs = Tab.values().filter { it.icon != null },
                    isSelected = { t -> current == t || current.parent == t },
                    onSelect = { t -> tab = t.ordinal },
                )
            },
        ) { padding ->
            val underNav = true
            val contentModifier = if (underNav) Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()) else Modifier.fillMaxSize().padding(padding)
            CompositionLocalProvider(LocalBottomInset provides (if (underNav) padding.calculateBottomPadding() else 0.dp)) {
            Column(contentModifier) {
              if (current.parent != null) {
                  TextButton(onClick = { tab = current.parent!!.ordinal }, modifier = Modifier.padding(start = 8.dp)) {
                      Text("‹  ${current.parent!!.label}")
                  }
              }
              Box(Modifier.fillMaxSize()) {
                when (current) {
                    Tab.DASHBOARD ->
                        if (live.connection == ConnectionState.IDLE && live.status == null) {
                            ConnectScreen(live, settings, onConnect = connectWithPerm)
                        } else DashboardScreen(
                            live, settings, fleet, onConnect = connectWithPerm,
                            onOpenSensors = { tab = Tab.SENSORS.ordinal }, onOpenDiagnostics = { tab = Tab.DIAGNOSTICS.ordinal },
                            resetStatus = vm.resetStatus.collectAsStateWithLifecycle().value,
                            onReset = vm::resetLatch,
                        )
                    Tab.MAP -> EarthScreen(
                        devices, fleet, settings.cloudEnabled, selectedId,
                        onSelect = { selectedId = it }, onOpenSettings = { tab = Tab.SETTINGS.ordinal },
                        fullscreen = fullNow, onFullscreen = { fullscreen = it },
                    )
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
                        onConnect = connectWithPerm,
                        onRequestBlePermission = { permLauncher.launch(BlePermissions.required()) },
                        onDisconnect = vm::disconnect,
                        onClearHistory = vm::clearHistory,
                        onOpenProfile = { tab = Tab.USER.ordinal },
                        contactStatus = contactStatus,
                        whatsappStatus = whatsappStatus,
                        onSaveContact = vm::saveAlertContact,
                        flameTuning = vm.flameTuning.collectAsStateWithLifecycle().value,
                        flameLive = vm.flameLive.collectAsStateWithLifecycle().value,
                        flameStatus = vm.flameStatus.collectAsStateWithLifecycle().value,
                        onRefreshFlame = vm::refreshFlame,
                        onPushFlame = vm::pushFlame,
                        resetStatus = vm.resetStatus.collectAsStateWithLifecycle().value,
                        onReset = vm::resetLatch,
                    )
                }
              }
            }
            }
        }
        }
    }
    }
}

@Composable
private fun AurelionNavBar(tabs: List<Tab>, isSelected: (Tab) -> Boolean, onSelect: (Tab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current
    val n = tabs.size
    val selIndex = tabs.indexOfFirst { isSelected(it) }.coerceAtLeast(0)
    val posState = androidx.compose.animation.core.animateFloatAsState(
        targetValue = selIndex.toFloat(),
        animationSpec = if (reduce) androidx.compose.animation.core.tween(0)
        else androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 150f),
        label = "drop",
    )
    val dark = cs.background.luminance() < 0.5f
    val accent = cs.primary
    Box(Modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().panel(28.dp, 0.97f)) {
        Box(
            Modifier.matchParentSize().padding(5.dp).drawBehind {
                val p = posState.value
                val w = size.width / n
                val h = size.height
                val d = kotlin.math.abs(selIndex - p).coerceIn(0f, 1f)
                val dw = w * 0.94f * (1f + 0.55f * d)
                val dh = h * (1f - 0.14f * d)
                val cx = (p + 0.5f) * w
                val left = cx - dw / 2f
                val top = (h - dh) / 2f
                val rr = androidx.compose.ui.geometry.CornerRadius(dh * 0.5f)
                val white = Color.White
                drawRoundRect(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.32f), Color.Transparent), center = Offset(cx, h / 2f), radius = dw),
                    topLeft = Offset(left - dw * 0.25f, top - dh * 0.25f),
                    size = androidx.compose.ui.geometry.Size(dw * 1.5f, dh * 1.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(dh),
                )
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(white.copy(alpha = if (dark) 0.26f else 0.7f), accent.copy(alpha = if (dark) 0.20f else 0.26f), white.copy(alpha = if (dark) 0.10f else 0.35f)),
                        startY = top, endY = top + dh,
                    ),
                    topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(dw, dh), cornerRadius = rr,
                )
                drawOval(
                    Brush.radialGradient(listOf(white.copy(alpha = 0.75f), Color.Transparent), center = Offset(left + dw * 0.32f, top + dh * 0.2f), radius = dw * 0.32f),
                    topLeft = Offset(left + dw * 0.1f, top + dh * 0.05f), size = androidx.compose.ui.geometry.Size(dw * 0.5f, dh * 0.26f),
                )
                drawOval(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx, top + dh * 0.92f), radius = dw * 0.4f),
                    topLeft = Offset(cx - dw * 0.32f, top + dh * 0.78f), size = androidx.compose.ui.geometry.Size(dw * 0.64f, dh * 0.18f),
                )
                drawRoundRect(
                    Brush.linearGradient(listOf(white.copy(alpha = 0.95f), white.copy(alpha = 0.08f), white.copy(alpha = 0.55f)), start = Offset(left, top), end = Offset(left + dw, top + dh)),
                    topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(dw, dh), cornerRadius = rr,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.3.dp.toPx()),
                )
            },
        )
        Row(Modifier.padding(5.dp).fillMaxWidth()) {
            tabs.forEach { t ->
                val sel = isSelected(t)
                val tint by androidx.compose.animation.animateColorAsState(
                    if (sel) cs.primary else cs.onSurfaceVariant,
                    androidx.compose.animation.core.tween(if (reduce) 0 else 220), label = "navTint",
                )
                val scale by androidx.compose.animation.core.animateFloatAsState(
                    if (sel) 1.14f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 300f), label = "navScale",
                )
                Column(
                    Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(22.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) { onSelect(t) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon!!, contentDescription = t.label, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                    Text(t.label, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, color = tint,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, letterSpacing = 0.3.sp)
                }
            }
        }
    }
}
