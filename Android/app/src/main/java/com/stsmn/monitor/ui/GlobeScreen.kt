package com.stsmn.monitor.ui

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.key
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.GlobeData
import com.stsmn.monitor.domain.GlobeFailure
import com.stsmn.monitor.domain.GlobeLifecycle
import com.stsmn.monitor.domain.GlobeNotice
import com.stsmn.monitor.domain.MapDevice
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val GLOBE_URL = "https://appassets.androidplatform.net/assets/globe/index.html"
private const val TAG = "StsmnGlobe"
private const val START_TIMEOUT_MS = 20_000L

/**
 * The only two things the bundled page may call. Every input is validated here; nothing is executed.
 * [alive] is switched off when the screen is disposed so a late callback can never touch a dead screen.
 */
private class GlobeBridge(
    private val selectCb: (String?) -> Unit,
    private val readyCb: () -> Unit,
    private val fatalCb: (String, String) -> Unit,
    private val noticeCb: (String, String) -> Unit,
) {
    @Volatile var alive = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun post(block: () -> Unit) { handler.post { if (alive) block() } }

    @JavascriptInterface fun onSelect(id: String?) { if (id == null || GlobeData.validId(id)) post { selectCb(id) } }
    @JavascriptInterface fun onReady() { post { readyCb() } }
    @JavascriptInterface fun onFatal(kind: String?, detail: String?) { post { fatalCb((kind ?: "").take(40), (detail ?: "").take(160)) } }
    @JavascriptInterface fun onNotice(kind: String?, detail: String?) { post { noticeCb((kind ?: "").take(40), (detail ?: "").take(160)) } }
}

/**
 * 3D globe. Retry creates a completely new WebView (key = attempt); callbacks of older pages are ignored.
 * Fallbacks: Retry, flat map. The sensor monitoring itself never depends on this screen.
 */
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
    var attempt by remember { mutableIntStateOf(0) }
    key(attempt) {
        GlobeSession(attempt, devices, fleet, cloudEnabled, selectedId, onSelect, onOpenSettings, onUseFlatMap, onRetry = { attempt++ })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GlobeSession(
    attempt: Int,
    devices: List<MapDevice>,
    fleet: FleetState,
    cloudEnabled: Boolean,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onUseFlatMap: () -> Unit,
    onRetry: () -> Unit,
) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(GlobeLifecycle.initial(attempt)) }
    var satellite by remember { mutableStateOf(true) }
    var tilted by remember { mutableStateOf(false) }
    var debug by remember { mutableStateOf(false) }
    val hasInternet by rememberHasInternet()
    val onSelectState = rememberUpdatedState(onSelect)
    val selected = devices.firstOrNull { it.id == selectedId }

    val bridge = remember {
        GlobeBridge(
            selectCb = { onSelectState.value(it) },
            readyCb = { state = GlobeLifecycle.onReady(state) },
            fatalCb = { kind, detail ->
                Log.w(TAG, "fatal kind=$kind detail=$detail")
                state = GlobeLifecycle.onFatal(state, GlobeLifecycle.parseFailure(kind), detail)
            },
            noticeCb = { kind, detail ->
                Log.w(TAG, "notice kind=$kind detail=$detail")
                state = GlobeLifecycle.onNotice(state, GlobeLifecycle.parseNotice(kind))
            },
        )
    }

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
                addJavascriptInterface(bridge, "AndroidBridge")
                webChromeClient = object : WebChromeClient() {
                    // Structured diagnostics: level + short text, no secrets exist in this page.
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        Log.d(TAG, "console ${m.messageLevel()} ${m.message().take(200)} (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})")
                        return true
                    }
                }
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
                    // Never navigate away from the bundled page (links, redirects, injected frames).
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) {
                            Log.w(TAG, "main frame error ${error.errorCode}")
                            state = GlobeLifecycle.onFatal(state, GlobeFailure.LOAD_ERROR, error.description?.toString())
                        }
                    }
                    // Renderer died (low memory / GPU): fall back instead of letting Android kill the whole app.
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        Log.w(TAG, "renderer gone, crashed=${detail.didCrash()}")
                        state = GlobeLifecycle.onRendererGone(state)
                        return true
                    }
                }
                loadUrl(GLOBE_URL)
            }
        }
    }
    val view = web.getOrNull()

    DisposableEffect(view) {
        onDispose {
            bridge.alive = false   // late callbacks from this page are ignored from now on
            view?.apply {
                runCatching { (parent as? android.view.ViewGroup)?.removeView(this) } // never destroy while still attached
                runCatching { removeJavascriptInterface("AndroidBridge"); stopLoading(); destroy() }
            }
        }
    }

    // Pause rendering when the app is in the background (saves GPU/battery), resume on return.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, view) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // The timeout only matters while still initializing; GlobeLifecycle.onTimeout ignores a started globe.
    LaunchedEffect(Unit) { delay(START_TIMEOUT_MS); state = GlobeLifecycle.onTimeout(state) }

    // Commands are sent only after the page reported READY.
    val json = remember(devices) { GlobeData.json(devices) }
    val ready = state.isReady && view != null
    LaunchedEffect(ready, json) { if (ready) view!!.evaluateJavascript("stsmn.setDevices($json)", null) }
    LaunchedEffect(ready, selectedId) {
        if (ready) {
            if (GlobeData.validId(selectedId)) view!!.evaluateJavascript("stsmn.focus('$selectedId')", null)
            else view!!.evaluateJavascript("stsmn.select(null)", null)
        }
    }
    LaunchedEffect(ready, hasInternet) { if (ready) view!!.evaluateJavascript("stsmn.setNet(${JSONObject.quote("internet=$hasInternet")})", null) }
    LaunchedEffect(ready, debug) { if (ready) view!!.evaluateJavascript("stsmn.debug($debug)", null) }

    // ---------------- fatal: useful fallback with working Retry and Flat-map buttons ----------------
    if (view == null || state.failure != null) {
        val why = state.failure?.message ?: GlobeFailure.INIT_ERROR.message
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("3D Map", "${devices.size} transformer(s)")
            AppCard(container = StsmnColors.Emergency.copy(alpha = 0.14f)) {
                Text("3D map unavailable", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
                Text(why, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Live data, history and alerts are not affected.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                val tech = state.detail ?: web.exceptionOrNull()?.message
                if (!tech.isNullOrBlank()) {
                    Text("Technical: $tech", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = onUseFlatMap, modifier = Modifier.weight(1f)) { Text("Flat map", maxLines = 1, softWrap = false) }
                }
            }
            devices.forEach { DeviceCard(it, onClose = null) }
        }
        return
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
            if (!hasInternet) Banner(GlobeNotice.NO_INTERNET.message, StsmnColors.Info)
            else if (GlobeNotice.TILES_FAILED in state.notices) Banner(GlobeNotice.TILES_FAILED.message, StsmnColors.Info)
            if (GlobeNotice.TERRAIN_FAILED in state.notices) Banner(GlobeNotice.TERRAIN_FAILED.message, StsmnColors.Info)
        }
        if (!state.isReady) Text("Loading 3D map…", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)

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
                Button(onClick = { view.evaluateJavascript("stsmn.fitAll()", null) }, enabled = state.isReady && devices.any { it.hasLocation }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Show all", fontSize = 13.sp, maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = { satellite = !satellite; view.evaluateJavascript("stsmn.setStyle('${if (satellite) "sat" else "street"}')", null) }, enabled = state.isReady, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (satellite) "Streets" else "Satellite", fontSize = 13.sp, maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { tilted = !tilted; view.evaluateJavascript("stsmn.tilt($tilted)", null) }, enabled = state.isReady, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (tilted) "Flat" else "Tilt 3D", fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
            if (selected != null) DeviceCard(selected, onClose = { onSelect(null) }, modifier = Modifier.heightIn(max = 300.dp))
            // Tap the credit line to show/hide the developer diagnostics line (tile counters, JS errors, network).
            Text(
                if (satellite) "Imagery © Esri, Maxar, Earthstar · Terrain © Mapzen/AWS · MapLibre" else "© OpenStreetMap contributors · MapLibre",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).clickable { debug = !debug },
            )
        }
    }
}
