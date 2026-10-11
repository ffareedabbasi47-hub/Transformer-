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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/** Only validated positions are sent; org.json escapes everything, so device names can never become script. */
internal fun globeJson(devices: List<MapDevice>): String {
    val arr = JSONArray()
    for (d in devices) {
        if (!d.hasLocation || !ID_RE.matches(d.id)) continue
        arr.put(JSONObject().put("id", d.id).put("lat", d.lat).put("lng", d.lng).put("state", d.state.name).put("demo", d.demoLocation))
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

/** 3D globe: satellite imagery on a spinning Earth, terrain relief when zoomed in, live transformer markers. */
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
    var satellite by remember { mutableStateOf(true) }
    var tilted by remember { mutableStateOf(false) }
    var tilesFailed by remember { mutableStateOf(false) }
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
                    javaScriptEnabled = true            // needed by the bundled MapLibre; only our own page is ever loaded
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportZoom(false)
                    setGeolocationEnabled(false)
                }
                addJavascriptInterface(GlobeBridge({ onSelectState.value(it) }, { ready = true }, { failed = it }, { tilesFailed = true }), "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val r = loader.shouldInterceptRequest(request.url) ?: return null
                        // Some Android versions label .css/.js as text/plain, and the browser then ignores the stylesheet.
                        // Runs on a WebView background thread: nothing here may throw, or the whole app would crash.
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
                    // If the WebView's renderer dies (low memory / GPU), show the fallback card instead of killing the app.
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        failed = "The 3D engine stopped on this phone (low memory or GPU)"
                        return true
                    }
                    // Never navigate away from the bundled page (links, redirects, injected frames).
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
                runCatching { (parent as? android.view.ViewGroup)?.removeView(this) } // never destroy while still attached
                runCatching { removeJavascriptInterface("AndroidBridge"); stopLoading(); destroy() }
            }
        }
    }

    if (view == null || failed != null) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("3D Map", "${devices.size} transformer(s)")
            AppCard(container = StsmnColors.Emergency.copy(alpha = 0.14f)) {
                Text("3D globe could not be loaded", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
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
        if (!ready && failed == null) failed = "Globe did not start (WebView or WebGL not available on this phone)"
    }
    val json = remember(devices) { globeJson(devices) }
    LaunchedEffect(ready, json) { if (ready) view.evaluateJavascript("stsmn.setDevices($json)", null) }
    LaunchedEffect(ready, selectedId) {
        if (ready) {
            if (selectedId != null && ID_RE.matches(selectedId)) view.evaluateJavascript("stsmn.focus('$selectedId')", null)
            else view.evaluateJavascript("stsmn.select(null)", null)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })

        Column(Modifier.align(Alignment.TopStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryBar(devices, fleet, cloudEnabled, title = "3D MAP")
            if (cloudEnabled && fleet.cloud != CloudState.ONLINE) {
                Banner(
                    when (fleet.cloud) {
                        CloudState.AUTH_FAILED -> "Viewer key rejected by the server – check Settings → Realtime cloud"
                        CloudState.OFF -> "Realtime cloud is off"
                        else -> "Reconnecting… markers show last known state (STALE)"
                    }, StsmnColors.Warning,
                )
            }
        }
        if (!hasInternet || tilesFailed) {
            Box(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 76.dp, end = 12.dp)) {
                Banner("No internet: the 3D map needs internet for satellite pictures. Live data still works; use Flat for cached tiles.", StsmnColors.Info)
            }
        }
        if (!ready) Text("Loading globe…", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)

        if (devices.none { it.hasLocation }) {
            AppCard(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Text("No transformer positions yet", fontWeight = FontWeight.Bold)
                Text(
                    "Enter the site coordinates in Settings, or register transformers with coordinates on the server.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { view.evaluateJavascript("stsmn.fitAll()", null) }, enabled = devices.any { it.hasLocation }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Show all", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = { satellite = !satellite; view.evaluateJavascript("stsmn.setStyle('${if (satellite) "sat" else "street"}')", null) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (satellite) "Streets" else "Satellite", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { tilted = !tilted; view.evaluateJavascript("stsmn.tilt($tilted)", null) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (tilted) "Flat" else "Tilt 3D", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
            if (selected != null) DeviceCard(selected, onClose = { onSelect(null) }, modifier = Modifier.heightIn(max = 300.dp))
            Text(
                if (satellite) "Imagery © Esri, Maxar, Earthstar · Terrain © Mapzen/AWS · MapLibre" else "© OpenStreetMap contributors · MapLibre",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
