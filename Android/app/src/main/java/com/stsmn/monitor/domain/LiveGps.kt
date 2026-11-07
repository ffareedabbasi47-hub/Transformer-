package com.stsmn.monitor.domain

import org.json.JSONException
import org.json.JSONObject

/** A real GPS position reported by the ESP32's NEO-6M (never invented: only built from a valid fix). */
data class LiveGps(val lat: Double, val lng: Double, val sats: Int)

object HealthParser {
    /** Returns the position only when the ESP32 says it has a fix AND the numbers are a real, non-zero coordinate. */
    fun parseGps(raw: String): LiveGps? {
        val o = try { JSONObject(raw) } catch (e: JSONException) { return null }
        if (!o.optBoolean("gpsFix", false)) return null
        if (!o.has("lat") || !o.has("lng")) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lng = o.optDouble("lng", Double.NaN)
        if (!SiteLocationRules.isValid(lat, lng)) return null
        if (lat == 0.0 && lng == 0.0) return null
        return LiveGps(lat, lng, o.optInt("gpsSats", 0))
    }
}
