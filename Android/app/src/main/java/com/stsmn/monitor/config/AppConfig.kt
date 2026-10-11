package com.stsmn.monitor.config

/**
 * The ONE place for app configuration. Safety thresholds are NOT here on purpose:
 * the ESP32 decides SAFE/WARNING/EMERGENCY; the app only displays what it receives.
 */
object AppConfig {
    // --- Device / API (PDF: ESP32 SoftAP, GET /api/status) ---
    const val DEFAULT_DEVICE_HOST = "192.168.4.1" // ESP32 SoftAP default address
    const val API_PORT = 80
    const val STATUS_PATH = "/api/status"

    // --- Polling and timeouts ---
    const val DEFAULT_POLL_INTERVAL_MS = 3000L
    val POLL_INTERVAL_CHOICES_MS = listOf(1000L, 2000L, 3000L, 5000L)
    const val DEFAULT_STALE_TIMEOUT_MS = 10000L
    val STALE_TIMEOUT_CHOICES_MS = listOf(5000L, 10000L, 15000L)
    /** Stale timeout is never shorter than this many poll intervals (avoids false STALE). */
    const val MIN_STALE_POLL_MULTIPLE = 3
    const val CONNECT_TIMEOUT_MS = 2000
    const val READ_TIMEOUT_MS = 2000
    const val MAX_RESPONSE_BYTES = 4096
    /** Consecutive failed polls before the state becomes ERROR. */
    const val ERROR_AFTER_FAILURES = 6
    const val MAX_BACKOFF_MS = 5000L
    /** How often the connection state / packet age is re-evaluated. */
    const val STATE_TICK_MS = 500L

    // --- History (PDF: keep bounded, a few hundred events) ---
    const val HISTORY_MAX_EVENTS = 300
    const val HISTORY_FILE_NAME = "events.json"
    /** A sensor must be invalid in this many consecutive packets before an event is logged. */
    const val SENSOR_INVALID_DEBOUNCE_PACKETS = 3

    /** Number of recent packets kept in memory for the sensor sparklines. */
    const val TREND_POINTS = 60

    // --- Validation ranges (physical limits of the sensors) ---
    const val TEMP_MIN_C = -55.0   // DS18B20 range
    const val TEMP_MAX_C = 125.0
    const val VOLTAGE_MIN_V = 0.0  // 0-25 V module
    const val VOLTAGE_MAX_V = 25.0
    const val CURRENT_MIN_A = -5.0 // ACS712 5A
    const val CURRENT_MAX_A = 5.0

    // --- Display only (must match OIL_LOW_PERCENT in firmware config.h) ---
    const val OIL_LOW_PERCENT = 30

    // --- Live Map (ONE transformer only in this version) ---
    const val DEFAULT_TRANSFORMER_LABEL = "STSMN-TR-001"
    const val TRANSFORMER_LABEL_PREFIX = "STSMN-"
    /**
     * DEMO location (Lucknow, Uttar Pradesh). This is NOT the real transformer position.
     * It is only used until real site coordinates are entered in Settings -> Site location.
     */
    const val DEMO_LAT = 26.8467
    const val DEMO_LNG = 80.9462
    // Approximate bounding box of Uttar Pradesh (used only to focus the map and warn on far-away coordinates).
    const val UP_MIN_LAT = 23.8
    const val UP_MAX_LAT = 30.5
    const val UP_MIN_LNG = 77.0
    const val UP_MAX_LNG = 84.7
    const val UP_CENTER_LAT = 26.85
    const val UP_CENTER_LNG = 80.90
    const val MAP_INITIAL_ZOOM = 6.5
    const val MAP_CENTER_ZOOM = 11.0
    const val MAP_MIN_ZOOM = 3.0
    const val MAP_MAX_ZOOM = 20.5
    const val MAP_TILE_CACHE_BYTES = 200L * 1024 * 1024
    /** How often the "is there internet for map tiles" hint is refreshed. */
    const val MAP_INTERNET_CHECK_MS = 5000L

    // --- Settings validation ---
    const val MAX_TEXT_LENGTH = 40
}
