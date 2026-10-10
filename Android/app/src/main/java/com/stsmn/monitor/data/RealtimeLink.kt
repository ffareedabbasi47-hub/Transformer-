package com.stsmn.monitor.data

import com.stsmn.monitor.domain.CloudState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One authenticated WebSocket with automatic reconnect (1 s -> 15 s backoff).
 * The first message is always the auth message; the link is ONLINE only after the server accepts it.
 * Wrong key (server closes with 1008) -> AUTH_FAILED and a slow 30 s retry, so a typo never hammers the server.
 */
class RealtimeLink(private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)      // detects dead connections (phone changed network, server died)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)        // streaming socket; pings handle liveness
        .build()

    private val _state = MutableStateFlow(CloudState.OFF)
    val state: StateFlow<CloudState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null

    /** Called on an OkHttp thread for every valid JSON message after the server accepted us. */
    @Volatile var onMessage: (JSONObject) -> Unit = {}

    fun start(url: String, authMessage: String) {
        stop()
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                _state.value = if (attempt == 0) CloudState.CONNECTING else CloudState.RETRYING
                val done = CompletableDeferred<Int>()
                var accepted = false
                val request = try { Request.Builder().url(url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")).build() }
                catch (e: IllegalArgumentException) { _state.value = CloudState.AUTH_FAILED; return@launch }
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(authMessage) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val m = try { JSONObject(text) } catch (e: Exception) { return }
                        val t = m.optString("t")
                        if (!accepted && (t == "snapshot" || t == "ok")) { accepted = true; _state.value = CloudState.ONLINE }
                        if (accepted) this@RealtimeLink.onMessage(m)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); done.complete(code) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(code) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(-1) }
                })
                socket = ws
                val code = try { done.await() } finally { ws.cancel(); socket = null }
                if (!isActive) break
                if (accepted) attempt = 0 else attempt++
                if (code == 1008 && !accepted) { _state.value = CloudState.AUTH_FAILED; delay(30_000); continue }
                _state.value = CloudState.RETRYING
                delay(minOf(15_000L, 1_000L shl minOf(attempt, 4)))
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        socket?.cancel(); socket = null
        _state.value = CloudState.OFF
    }

    /** Returns false when not connected (caller simply drops the sample; the next one is 3 s away). */
    fun send(text: String): Boolean = _state.value == CloudState.ONLINE && (socket?.send(text) ?: false)
}
