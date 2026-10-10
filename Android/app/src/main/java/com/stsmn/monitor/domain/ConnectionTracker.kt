package com.stsmn.monitor.domain

import com.stsmn.monitor.config.AppConfig

/**
 * Pure connection state machine. LIVE means "a valid, fresh STSMN packet arrived" -
 * being connected to Wi-Fi is NOT enough.
 *
 * IDLE -> CONNECTING (start) -> LIVE (valid packet) -> STALE (no fresh packet)
 * any -> ERROR after repeated failures; a valid packet always returns to LIVE.
 */
class ConnectionTracker(private val errorAfterFailures: Int = AppConfig.ERROR_AFTER_FAILURES) {
    private var started = false
    private var lastValidAtMs: Long? = null
    var consecutiveFailures: Int = 0
        private set

    val lastValidAt: Long? get() = lastValidAtMs
    val isStarted: Boolean get() = started

    fun start() { started = true }
    fun stop() { started = false; consecutiveFailures = 0 }
    fun reset() { lastValidAtMs = null; consecutiveFailures = 0 }

    fun onValidPacket(nowMs: Long) {
        lastValidAtMs = nowMs
        consecutiveFailures = 0
    }

    fun onFailure() { if (consecutiveFailures < Int.MAX_VALUE) consecutiveFailures++ }

    fun ageMs(nowMs: Long): Long? = lastValidAtMs?.let { (nowMs - it).coerceAtLeast(0L) }

    fun stateAt(nowMs: Long, staleTimeoutMs: Long): ConnectionState {
        if (!started) return ConnectionState.IDLE
        val age = ageMs(nowMs)
        if (consecutiveFailures >= errorAfterFailures) return ConnectionState.ERROR
        if (age == null) return ConnectionState.CONNECTING
        return if (age > staleTimeoutMs) ConnectionState.STALE else ConnectionState.LIVE
    }
}
