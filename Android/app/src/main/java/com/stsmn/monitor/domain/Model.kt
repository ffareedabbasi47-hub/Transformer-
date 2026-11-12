package com.stsmn.monitor.domain

enum class SystemStatus {
    SAFE, WARNING, EMERGENCY;

    companion object {
        fun parse(value: String): SystemStatus? = values().firstOrNull { it.name == value }
    }
}

enum class ConnectionState { IDLE, CONNECTING, LIVE, STALE, ERROR }

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
    val relay: Boolean,
    val alarm: Boolean,
    val seq: Long,

    val smokePct: Int? = null,
    val flamePct: Int? = null,
    val tempPct: Int? = null,
    val currentPct: Int? = null,
    val voltagePct: Int? = null,
    val latched: Boolean = false,
    val resetWaitS: Int = 0,
    val oilLow: Boolean = false,
    val uncalibrated: Boolean = false,
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

data class EventRecord(
    val timestampMs: Long,
    val deviceId: String,
    val type: EventType,
    val risk: Int?,
    val status: String?,
    val relay: Boolean?,
    val message: String,
)

data class LiveSnapshot(
    val connection: ConnectionState = ConnectionState.IDLE,
    val status: StsmnStatus? = null,
    val packetAgeMs: Long? = null,
    val lastValidElapsedMs: Long? = null,
    val lastValidWallMs: Long? = null,
    val packetsReceived: Long = 0,
    val deviceAlertPhone: String? = null,
    val parseErrors: Long = 0,
    val networkErrors: Long = 0,
    val duplicatePackets: Long = 0,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val anomalies: List<String> = emptyList(),
    val wifiBound: Boolean = false,
    val host: String = "",
    val viaBluetooth: Boolean = false,
    val linkNote: String = "",
    val recent: List<StsmnStatus> = emptyList(),
    val gps: LiveGps? = null,
    val gpsElapsedMs: Long? = null,
) {
    val isLive: Boolean get() = connection == ConnectionState.LIVE
}
