package com.stsmn.monitor.domain

enum class MarkerState(val label: String) {
    SAFE("SAFE"),
    WARNING("WARNING"),
    EMERGENCY("EMERGENCY"),
    STALE("STALE"),
    OFFLINE("OFFLINE"),
}

object MarkerStateMapper {

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
