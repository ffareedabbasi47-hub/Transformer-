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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
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
 * JavaScript interface for the offline 3D Earth Globe.
 */
private class GlobeBridge(
    private val selectCb: (String?) -> Unit,
    private val readyCb: () -> Unit,
    private val fatalCb: (String, String) -> Unit,
    private val noticeCb: (String, String) -> Unit,
    private val spinCb: (Boolean) -> Unit,
) {
    @Volatile var alive = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun post(block: () -> Unit) { handler.post { if (alive) block() } }

    @JavascriptInterface fun onSelect(id: String?) { if (id == null || GlobeData.validId(id)) post { selectCb(id) } }
    @JavascriptInterface fun onReady() { post { readyCb() } }
    @JavascriptInterface fun onFatal(kind: String?, detail: String?) { post { fatalCb((kind ?: "").take(40), (detail ?: "").take(160)) } }
    @JavascriptInterface fun onNotice(kind: String?, detail: String?) { post { noticeCb((kind ?: "").take(40), (detail ?: "").take(160)) } }
    @JavascriptInterface fun onSpinState(active: Boolean) { post { spinCb(active) } }
}

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
    var isSpinning by remember { mutableStateOf(true) }
    var debug by remember { mutableStateOf(false) }
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
            spinCb = { isSpinning = it },
        )
    }

    val web = remember {
        runCatching {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()
            WebView(ctx).apply {
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                setBackgroundColor(AColor.parseColor("#04070F"))
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setSupportZoom(false)
                    setGeolocationEnabled(false)
                }
                addJavascriptInterface(bridge, "AndroidBridge")
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        Log.d(TAG, "console ${m.messageLevel()} ${m.message().take(200)}")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val r = loader.shouldInterceptRequest(request.url) ?: return null
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
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) {
                            state = GlobeLifecycle.onFatal(state, GlobeFailure.LOAD_ERROR, error.description?.toString())
                        }
                    }
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
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
            bridge.alive = false
            view?.apply {
                runCatching { (parent as? android.view.ViewGroup)?.removeView(this) }
                runCatching { removeJavascriptInterface("AndroidBridge"); stopLoading(); destroy() }
            }
        }
    }

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

    LaunchedEffect(Unit) { delay(START_TIMEOUT_MS); state = GlobeLifecycle.onTimeout(state) }

    val json = remember(devices) { GlobeData.json(devices) }
    val ready = state.isReady && view != null
    LaunchedEffect(ready, json) { if (ready) view!!.evaluateJavascript("stsmn.setDevices($json)", null) }
    LaunchedEffect(ready, selectedId) {
        if (ready) {
            if (GlobeData.validId(selectedId)) view!!.evaluateJavascript("stsmn.focus('$selectedId')", null)
            else view!!.evaluateJavascript("stsmn.select(null)", null)
        }
    }
    LaunchedEffect(ready, debug) { if (ready) view!!.evaluateJavascript("stsmn.debug($debug)", null) }

    // Fallback screen only if WebGL / WebView completely crashed
    if (view == null || state.failure != null) {
        val why = state.failure?.message ?: GlobeFailure.INIT_ERROR.message
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenTitle("3D Globe", "${devices.size} transformer(s)")
            AppCard(container = StsmnColors.Emergency.copy(alpha = 0.14f)) {
                Text("3D Map Offline Fallback", fontWeight = FontWeight.Bold, color = StsmnColors.Emergency)
                Text(why, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry 3D", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = onUseFlatMap, modifier = Modifier.weight(1f)) { Text("Open Flat Map", maxLines = 1, softWrap = false) }
                }
            }
            devices.forEach { DeviceCard(it, onClose = null) }
        }
        return
    }

    // Full-screen 3D Globe with sleek floating glass controls
    Box(Modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })

        // Top Floating Status Chip
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(StsmnColors.Safe))
                    Text(
                        "3D EARTH GLOBE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "• ${devices.size} TR",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .clickable { debug = !debug }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(
                    if (satellite) "🛰️ SATELLITE" else "🗺️ TECH",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (!state.isReady) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    "Booting 3D Earth Globe…",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }

        // Bottom Controls Container
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Selected Transformer Quick Telemetry Card (Compact Glass Card)
            if (selected != null) {
                CompactGlobeDeviceCard(
                    d = selected,
                    onClose = { onSelect(null) },
                    onFocus = {
                        if (selected.hasLocation) view.evaluateJavascript("stsmn.focus('${selected.id}')", null)
                    },
                )
            }

            // Single Row Floating Action Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = { view.evaluateJavascript("stsmn.globe()", null) },
                    enabled = state.isReady,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text("🌍 Globe", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = { view.evaluateJavascript("stsmn.fitAll()", null) },
                    enabled = state.isReady,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text("📍 UP Site", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = {
                        val next = !isSpinning
                        isSpinning = next
                        view.evaluateJavascript("stsmn.spin($next)", null)
                    },
                    enabled = state.isReady,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text(if (isSpinning) "⏹️ Pause" else "▶️ Spin", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = {
                        satellite = !satellite
                        view.evaluateJavascript("stsmn.setStyle('${if (satellite) "sat" else "street"}')", null)
                    },
                    enabled = state.isReady,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text(if (satellite) "🗺️ Streets" else "🛰️ Sat", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }

            // Unobtrusive Touch Guide Caption
            Text(
                "STSMN 3D Offline Engine • Drag to rotate • Pinch to zoom • Tap marker to view",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * Compact floating telemetry card designed specifically for the 3D globe.
 * Keeps the globe 85% visible while providing instant real-time metrics.
 */
@Composable
private fun CompactGlobeDeviceCard(
    d: MapDevice,
    onClose: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = markerColor(d.state)
    AppCard(
        modifier = modifier.fillMaxWidth(),
        container = color.copy(alpha = 0.14f),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.label, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (d.demoLocation) "Ajrara Demo Site (UP)" else d.name,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(d.state.label, color)
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp).padding(start = 4.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
            }
        }

        // Live telemetry metric tiles in one compact row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlobeMetricTile("TEMP", fmtTemp(d.temperature), color = StsmnColors.Safe, Modifier.weight(1f))
            GlobeMetricTile("VOLT", fmtVolt(d.voltage), color = MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            GlobeMetricTile("CURR", fmtAmp(d.current), color = MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            GlobeMetricTile("RISK", d.risk?.let { "$it/100" } ?: "--", color = color, Modifier.weight(1f))
        }

        // Action row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onFocus,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text("Focus On Globe", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text("Dismiss", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun GlobeMetricTile(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1, softWrap = false)
        }
    }
}
