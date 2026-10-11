package com.stsmn.monitor.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.MapDevice
import org.json.JSONArray
import org.json.JSONObject

private const val GLOBE_URL = "https://appassets.androidplatform.net/assets/globe/index.html"
private val ID_RE = Regex("^[A-Z0-9][A-Z0-9-]{1,23}$")

/** Serializes transformer markers with live telemetry into JSON. org.json safely escapes all values. */
internal fun globeJson(devices: List<MapDevice>): String {
    val arr = JSONArray()
    for (d in devices) {
        if (!d.hasLocation || !ID_RE.matches(d.id)) continue
        val obj = JSONObject()
            .put("id", d.id)
            .put("name", d.name)
            .put("lat", d.lat)
            .put("lng", d.lng)
            .put("state", d.state.name)
            .put("demo", d.demoLocation)
            .put("isLive", d.isLive)
        d.risk?.let { obj.put("risk", it) }
        d.temperature?.let { obj.put("temp", it) }
        d.voltage?.let { obj.put("volt", it) }
        d.current?.let { obj.put("amp", it) }
        d.smoke?.let { obj.put("smoke", it) }
        d.flame?.let { obj.put("flame", it) }
        d.oilLevel?.let { obj.put("oil", it) }
        d.relay?.let { obj.put("relay", it) }
        d.alarm?.let { obj.put("alarm", it) }
        d.ageMs?.let { obj.put("ageMs", it) }
        arr.put(obj)
    }
    return arr.toString()
}

private class GlobeBridge(
    private val selectCb: (String?) -> Unit,
    private val readyCb: () -> Unit,
    private val errorCb: (String) -> Unit,
    private val warnCb: (String) -> Unit = {},
) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @JavascriptInterface fun onSelect(id: String?) { if (id == null || ID_RE.matches(id)) handler.post { selectCb(id) } }
    @JavascriptInterface fun onReady() { handler.post { readyCb() } }
    @JavascriptInterface fun onWarn(msg: String?) { handler.post { warnCb((msg ?: "").take(40)) } }
    @JavascriptInterface fun onError(msg: String?) { handler.post { errorCb((msg ?: "error").take(200)) } }
}

/**
 * Option A: Standalone offline-capable dark 3D Earth with transformer markers and live telemetry.
 * Remote satellite and street map imagery are treated strictly as optional enhancements.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GlobeScreen(
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onUseFlatMap: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var imageryMode by remember { mutableIntStateOf(0) } // 0 = Dark Earth (Offline Standalone), 1 = Satellite (Remote), 2 = Streets (Remote)
    var tilted by remember { mutableStateOf(false) }
    var autoSpin by remember { mutableStateOf(true) }
    var remoteOfflineWarn by remember { mutableStateOf(false) }
    val hasInternet by rememberHasInternet()
    val onSelectState = rememberUpdatedState(onSelect)
    val selected = devices.firstOrNull { it.id == selectedId }

    val web = remember {
        runCatching {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
            WebView(ctx).apply {
                setBackgroundColor(AColor.parseColor("#04070F"))
                settings.apply {
                    javaScriptEnabled = true            // needed by MapLibre; only bundled local pages/scripts are loaded
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportZoom(false)
                    setGeolocationEnabled(false)
                }
                addJavascriptInterface(
                    GlobeBridge(
                        selectCb = { onSelectState.value(it) },
                        readyCb = { ready = true },
                        errorCb = { failed = it },
                        warnCb = { msg -> if (msg == "remote_offline") remoteOfflineWarn = true }
                    ),
                    "AndroidBridge"
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val r = loader.shouldInterceptRequest(request.url) ?: return null
                        // Ensure correct MIME types on all Android WebView versions
                        return try {
                            val mime = when (request.url.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
                                "css" -> "text/css"
                                "js" -> "text/javascript"
                                "html" -> "text/html"
                                else -> null
                            }
                            if (mime == null) r else WebResourceResponse(mime, "utf-8", r.data)
                        } catch (_: Throwable) {
                            r
                        }
                    }
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        failed = "The 3D engine stopped on this phone (low memory or GPU)"
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) failed = error.description?.toString() ?: "load error"
                    }
                }
                loadUrl(GLOBE_URL)
            }
        }
    }
    val view = web.getOrNull()

    DisposableEffect(view) {
        onDispose {
            view?.apply {
                runCatching { (parent as? android.view.ViewGroup)?.removeView(this) }
                runCatching { removeJavascriptInterface("AndroidBridge"); stopLoading(); destroy() }
            }
        }
    }

    if (view == null || failed != null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("3D Earth", "${devices.size} transformer(s)")
            AppCard(container = StsmnColors.Emergency.copy(alpha = 0.14f)) {
                Text("3D Earth could not be loaded", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
                Text(
                    "${web.exceptionOrNull()?.message ?: failed ?: "Unknown error"}. Use the flat map or the list; live data and alerts are not affected.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onUseFlatMap, modifier = Modifier.padding(top = 8.dp)) { Text("Open flat map") }
            }
        }
        return
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(15_000)
        if (!ready && failed == null) failed = "Earth visualization did not start (WebGL unavailable on this phone)"
    }

    val json = remember(devices) { globeJson(devices) }
    LaunchedEffect(ready, json) {
        if (ready) view.evaluateJavascript("stsmn.setDevices($json)", null)
    }

    LaunchedEffect(ready, selectedId) {
        if (ready) {
            if (selectedId != null && ID_RE.matches(selectedId)) {
                view.evaluateJavascript("stsmn.focus('$selectedId')", null)
            } else {
                view.evaluateJavascript("stsmn.select(null)", null)
            }
        }
    }

    // Layer enhancement switching
    LaunchedEffect(ready, imageryMode) {
        if (ready) {
            val styleKind = when (imageryMode) {
                1 -> "sat"
                2 -> "street"
                else -> "dark"
            }
            view.evaluateJavascript("stsmn.setStyle('$styleKind')", null)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })

        // ---------------- Top HUD & Status ----------------
        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryBar(devices, fleet, cloudEnabled, title = "3D EARTH")
            if (cloudEnabled && fleet.cloud != CloudState.ONLINE) {
                Banner(
                    when (fleet.cloud) {
                        CloudState.AUTH_FAILED -> "Viewer key rejected by server – check Settings → Realtime cloud"
                        CloudState.OFF -> "Realtime cloud is off"
                        else -> "Reconnecting… markers show last known state (STALE)"
                    }, StsmnColors.Warning,
                )
            }
            // If remote imagery was chosen but phone is offline, display helpful hint without breaking dark earth
            if (imageryMode != 0 && (!hasInternet || remoteOfflineWarn)) {
                Banner("Remote imagery unavailable offline · using standalone Dark Earth base", StsmnColors.Info)
            }
        }

        if (!ready) {
            Text("Loading 3D Earth…", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        }

        if (devices.none { it.hasLocation }) {
            AppCard(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("No transformer positions yet", fontWeight = FontWeight.Bold)
                Text(
                    "Enter site coordinates in Settings, or register transformers with coordinates on the server.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }

        // ---------------- Bottom Controls & Telemetry Detail Card ----------------
        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Row 1: Layer mode switch (Dark Earth is standalone & default; Satellite and Streets are optional)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val modes = listOf("Dark Earth", "Satellite", "Streets")
                modes.forEachIndexed { idx, name ->
                    val isSelected = imageryMode == idx
                    if (isSelected) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                imageryMode = idx
                                remoteOfflineWarn = false
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                        ) {
                            Text(name, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // Row 2: Camera & Interaction controls
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { view.evaluateJavascript("stsmn.fitAll()", null) },
                    enabled = devices.any { it.hasLocation },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text("Fit All", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = {
                        tilted = !tilted
                        view.evaluateJavascript("stsmn.tilt($tilted)", null)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                ) {
                    Text(if (tilted) "Flat" else "Tilt 3D", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = {
                        autoSpin = !autoSpin
                        view.evaluateJavascript("stsmn.spin($autoSpin)", null)
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                ) {
                    Text(if (autoSpin) "Pause" else "Spin", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }

            // Selected transformer full telemetry card
            if (selected != null) {
                DeviceCard(selected, onClose = { onSelect(null) }, modifier = Modifier.heightIn(max = 300.dp))
            }

            // Clean attribution text
            Text(
                when (imageryMode) {
                    1 -> "Imagery © Esri, Maxar · Terrain © Mapzen/AWS · MapLibre"
                    2 -> "© OpenStreetMap contributors · MapLibre"
                    else -> "STSMN 3D Earth · Standalone Offline Base · MapLibre"
                },
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
