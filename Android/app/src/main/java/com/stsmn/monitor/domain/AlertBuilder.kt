package com.stsmn.monitor.domain

import com.stsmn.monitor.config.AppConfig

enum class AlertLevel { INFO, WARNING, CRITICAL }

data class ActiveAlert(val level: AlertLevel, val title: String, val detail: String)

/** Derives the "active alerts" list from the live state (never from stale data). */
object AlertBuilder {
    fun build(live: LiveSnapshot): List<ActiveAlert> {
        val alerts = mutableListOf<ActiveAlert>()
        val s = live.status
        when (live.connection) {
            ConnectionState.STALE -> alerts += ActiveAlert(
                AlertLevel.CRITICAL, "Data is stale", "No fresh packet for ${(live.packetAgeMs ?: 0) / 1000}s. Values are not live.")
            ConnectionState.ERROR -> alerts += ActiveAlert(
                AlertLevel.CRITICAL, "ESP32 unreachable", live.lastError ?: "Repeated network failures. Retrying…")
            ConnectionState.CONNECTING -> alerts += ActiveAlert(
                AlertLevel.INFO, "Connecting…", "Waiting for the first valid packet.")
            ConnectionState.IDLE -> alerts += ActiveAlert(
                AlertLevel.INFO, "Not connected", "Tap Connect to start monitoring.")
            ConnectionState.LIVE -> Unit
        }
        if (live.isLive && s != null) {
            when (s.status) {
                SystemStatus.EMERGENCY -> alerts += ActiveAlert(
                    AlertLevel.CRITICAL, "EMERGENCY", "Risk ${s.risk}/100. Supply is cut off by the ESP32.")
                SystemStatus.WARNING -> alerts += ActiveAlert(
                    AlertLevel.WARNING, "Warning – inspection advised", "Risk ${s.risk}/100.")
                SystemStatus.SAFE -> Unit
            }
            if (s.flame) alerts += ActiveAlert(AlertLevel.CRITICAL, "Flame detected", "Flame sensor is active.")
            if (s.smoke) alerts += ActiveAlert(AlertLevel.WARNING, "Smoke / gas detected", "MQ-2 indicator is active (demo sensor).")
            val oil = s.oilLevel
            if (oil != null && oil < AppConfig.OIL_LOW_PERCENT) {
                alerts += ActiveAlert(AlertLevel.WARNING, "Oil level low", "Demo level sensor reads $oil%.")
            }
            s.invalidSensors().forEach {
                alerts += ActiveAlert(AlertLevel.WARNING, "$it unavailable", "Sensor value is missing or out of range.")
            }
        }
        live.anomalies.forEach { alerts += ActiveAlert(AlertLevel.CRITICAL, "Relay fault", it) }
        return alerts
    }
}
