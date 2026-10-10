package com.stsmn.monitor.domain

/** What the single map marker shows. Colour is never the only signal: every state has a text label. */
enum class MarkerState(val label: String) {
    SAFE("SAFE"),
    WARNING("WARNING"),
    EMERGENCY("EMERGENCY"),
    STALE("STALE"),
    OFFLINE("OFFLINE"),
}

object MarkerStateMapper {
    /**
     * SAFE / WARNING / EMERGENCY only while the link is LIVE (fresh, validated data).
     * Old readings are never mapped to a live-looking colour: stale -> STALE, everything else -> OFFLINE.
     */
    fun from(live: LiveSnapshot): MarkerState {
        val s = live.status
        return when (live.connection) {
            ConnectionState.LIVE -> when (s?.status) {
                SystemStatus.SAFE -> MarkerState.SAFE
                SystemStatus.WARNING -> MarkerState.WARNING
                SystemStatus.EMERGENCY -> MarkerState.EMERGENCY
                null -> MarkerState.OFFLINE
            }
            ConnectionState.STALE -> MarkerState.STALE
            ConnectionState.IDLE, ConnectionState.CONNECTING, ConnectionState.ERROR -> MarkerState.OFFLINE
        }
    }
}
