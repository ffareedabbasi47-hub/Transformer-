package com.stsmn.monitor.domain

import com.stsmn.monitor.config.AppConfig
import kotlin.math.abs

/** Where the coordinates come from. The map always tells the user which one it is. */
enum class LocationSource(val label: String) {
    DEMO("DEMO LOCATION (not the real site)"),
    MEASURED("Site coordinates entered manually"),
}

object SiteLocationRules {
    /** Valid WGS84 range, finite numbers only. */
    fun isValid(lat: Double, lng: Double): Boolean =
        lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0

    /** Approximate rectangle around Uttar Pradesh (only used for a friendly warning). */
    fun isInsideUttarPradesh(lat: Double, lng: Double): Boolean =
        lat in AppConfig.UP_MIN_LAT..AppConfig.UP_MAX_LAT && lng in AppConfig.UP_MIN_LNG..AppConfig.UP_MAX_LNG

    fun isDemoCoordinate(lat: Double, lng: Double): Boolean =
        abs(lat - AppConfig.DEMO_LAT) < 1e-6 && abs(lng - AppConfig.DEMO_LNG) < 1e-6
}

/** Map label: "STSMN-" + device id from the ESP32 (TR-001 -> STSMN-TR-001). */
object TransformerLabel {
    fun from(deviceId: String?): String = when {
        deviceId.isNullOrBlank() -> AppConfig.DEFAULT_TRANSFORMER_LABEL
        deviceId.startsWith(AppConfig.TRANSFORMER_LABEL_PREFIX) -> deviceId
        else -> AppConfig.TRANSFORMER_LABEL_PREFIX + deviceId
    }
}
