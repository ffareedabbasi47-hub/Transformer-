package com.stsmn.monitor.domain

enum class SystemStatus {
    SAFE, WARNING, EMERGENCY;

    companion object {
        fun parse(value: String): SystemStatus? = values().firstOrNull { it.name == value }
    }
}

/** PDF connection state machine: IDLE, CONNECTING, LIVE, STALE, ERROR. */
enum class ConnectionState { IDLE, CONNECTING, LIVE, STALE, ERROR }

/**
 * One validated packet. A null sensor value means "unavailable/invalid" (the ESP32
 * sends null, or the value was outside the sensor's physical range).
 */
data class StsmnStatus(
    val deviceId: String,
    val fw: String,
    val uptimeMs: Long,
    val temperature: Double?,
    val voltage: Double?,
    val current: Double?,
    val smoke: Boolean,
    val flame: Boolean,
    val oilLevel: Int?,
    val risk: Int,
    val status: SystemStatus,
    val relay: Boolean, // true = supply allowed, false = cutoff
    val alarm: Boolean,
    val seq: Long,
    // Optional signal strength 0..100 per sensor (newer firmware). null = not reported / sensor not fitted.
    val smokePct: Int? = null,
    val flamePct: Int? = null,
    val tempPct: Int? = null,
    val currentPct: Int? = null,
    val voltagePct: Int? = null,
) {
    fun invalidSensors(): List<String> = buildList {
        if (temperature == null) add("Temperature")
        if (voltage == null) add("Voltage")
        if (current == null) add("Current")
        if (oilLevel == null) add("Oil level")
    }
}

enum class EventType(val label: String) {
    STATUS_CHANGED("Status changed"),
    EMERGENCY("Emergency"),
    RELAY_CHANGED("Relay changed"),
    SENSOR_INVALID("Sensor unavailable"),
    DEVICE_RESTART("Device restarted"),
    CONNECTION_LOST("Connection lost"),
    CONNECTION_RESTORED("Connection restored"),
}

/** PDF fault history record: timestamp, deviceId, event, risk, status, relay, message. */
data class EventRecord(
    val timestampMs: Long,
    val deviceId: String,
    val type: EventType,
    val risk: Int?,
    val status: String?,
    val relay: Boolean?,
    val message: String,
)

/** Everything the UI needs about the live link, in one immutable object. */
data class LiveSnapshot(
    val connection: ConnectionState = ConnectionState.IDLE,
    val status: StsmnStatus? = null,          // last VALID packet (may be old)
    val packetAgeMs: Long? = null,            // null = no valid packet yet
    val lastValidElapsedMs: Long? = null,     // SystemClock.elapsedRealtime() of the last valid packet (UI ticks age locally)
    val lastValidWallMs: Long? = null,        // wall-clock time of the last valid packet ("last seen")
    val packetsReceived: Long = 0,
    val deviceAlertPhone: String? = null,     // masked SMS number the ESP32 reports ("" = none, null = unknown)
    val parseErrors: Long = 0,
    val networkErrors: Long = 0,
    val duplicatePackets: Long = 0,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val anomalies: List<String> = emptyList(),
    val wifiBound: Boolean = false,
    val host: String = "",
    val viaBluetooth: Boolean = false,        // link is Bluetooth (BLE) instead of the ESP32 Wi-Fi
    val linkNote: String = "",               // short text about the Bluetooth link state
    val recent: List<StsmnStatus> = emptyList(), // last valid packets (oldest first) for sparklines
    val gps: LiveGps? = null,                    // last valid GPS fix from /api/health (null = none yet)
    val gpsElapsedMs: Long? = null,              // SystemClock.elapsedRealtime() when that fix was received
) {
    val isLive: Boolean get() = connection == ConnectionState.LIVE
}
