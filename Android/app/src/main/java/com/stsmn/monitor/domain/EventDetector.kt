package com.stsmn.monitor.domain

import com.stsmn.monitor.config.AppConfig

/**
 * Turns the stream of valid packets into meaningful events only (state changes),
 * so a persisting fault does not create hundreds of duplicate alerts.
 */
class EventDetector(private val invalidDebouncePackets: Int = AppConfig.SENSOR_INVALID_DEBOUNCE_PACKETS) {
    private var prev: StsmnStatus? = null
    private val invalidStreak = mutableMapOf<String, Int>()
    private val reportedInvalid = mutableSetOf<String>()

    fun onPacket(nowMs: Long, s: StsmnStatus): List<EventRecord> {
        val events = mutableListOf<EventRecord>()
        fun add(type: EventType, message: String) {
            events += EventRecord(nowMs, s.deviceId, type, s.risk, s.status.name, s.relay, message)
        }
        val p = prev
        if (p != null) {
            if (s.uptimeMs < p.uptimeMs) add(EventType.DEVICE_RESTART, "ESP32 restarted (uptime reset)")
            if (p.status != s.status) {
                val type = if (s.status == SystemStatus.EMERGENCY) EventType.EMERGENCY else EventType.STATUS_CHANGED
                add(type, "${p.status} → ${s.status}")
            }
            if (p.relay != s.relay) {
                add(EventType.RELAY_CHANGED, "Relay ${relayText(p.relay)} → ${relayText(s.relay)}")
            }
        } else if (s.status != SystemStatus.SAFE) {
            val type = if (s.status == SystemStatus.EMERGENCY) EventType.EMERGENCY else EventType.STATUS_CHANGED
            add(type, "Status is ${s.status} on first packet")
        }

        val invalidNow = s.invalidSensors().toSet()
        for (name in listOf("Temperature", "Voltage", "Current", "Oil level")) {
            if (name in invalidNow) {
                val streak = (invalidStreak[name] ?: 0) + 1
                invalidStreak[name] = streak
                if (streak >= invalidDebouncePackets && reportedInvalid.add(name)) {
                    add(EventType.SENSOR_INVALID, "$name unavailable")
                }
            } else {
                invalidStreak[name] = 0
                reportedInvalid.remove(name)
            }
        }
        prev = s
        return events
    }

    private fun relayText(on: Boolean) = if (on) "ON" else "OFF (cutoff)"
}
