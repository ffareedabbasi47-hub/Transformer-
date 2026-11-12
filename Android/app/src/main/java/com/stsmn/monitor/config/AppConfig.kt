package com.stsmn.monitor.config

object AppConfig {

    const val DEFAULT_DEVICE_HOST = "192.168.4.1"
    const val API_PORT = 80
    const val STATUS_PATH = "/api/status"
    const val CONTACT_PATH = "/api/contact"
    const val RESET_PATH = "/api/reset"
    const val FLAME_PATH = "/api/flame"
    const val HEALTH_PATH = "/api/health"
    const val GPS_POLL_EVERY_MS = 5_000L
    const val GPS_MAX_AGE_MS = 60_000L

    const val DEFAULT_POLL_INTERVAL_MS = 3000L
    val POLL_INTERVAL_CHOICES_MS = listOf(1000L, 2000L, 3000L, 5000L)
    const val DEFAULT_STALE_TIMEOUT_MS = 10000L
    val STALE_TIMEOUT_CHOICES_MS = listOf(5000L, 10000L, 15000L)

    const val MIN_STALE_POLL_MULTIPLE = 3
    const val CONNECT_TIMEOUT_MS = 2000
    const val READ_TIMEOUT_MS = 2000
    const val MAX_RESPONSE_BYTES = 4096

    const val ERROR_AFTER_FAILURES = 6
    const val MAX_BACKOFF_MS = 5000L

    const val STATE_TICK_MS = 500L

    const val HISTORY_MAX_EVENTS = 300
    const val HISTORY_FILE_NAME = "events.json"

    const val SENSOR_INVALID_DEBOUNCE_PACKETS = 3

    const val TREND_POINTS = 60

    const val TEMP_MIN_C = -55.0
    const val TEMP_MAX_C = 125.0
    const val VOLTAGE_MIN_V = 0.0
    const val VOLTAGE_MAX_V = 25.0
    const val CURRENT_MIN_A = -5.0
    const val CURRENT_MAX_A = 5.0

    const val OIL_LOW_PERCENT = 30

    const val DEFAULT_TRANSFORMER_LABEL = "STSMN-TR-001"
    const val TRANSFORMER_LABEL_PREFIX = "STSMN-"

    const val DEMO_LAT = 26.8467
    const val DEMO_LNG = 80.9462

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

    const val MAP_INTERNET_CHECK_MS = 5000L

    const val MAX_TEXT_LENGTH = 40
}
