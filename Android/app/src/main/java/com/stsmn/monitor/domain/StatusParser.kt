package com.stsmn.monitor.domain

import com.stsmn.monitor.config.AppConfig
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

sealed interface ParseResult {
    data class Valid(val status: StsmnStatus, val anomalies: List<String>) : ParseResult
    data class Invalid(val reason: String) : ParseResult
}

/**
 * Validates every field of the ESP32 JSON contract.
 *  - Missing / wrong-type required field  -> whole packet rejected.
 *  - Sensor value null or outside its physical range -> that sensor becomes "unavailable".
 *  - Unknown extra fields are ignored (forward compatibility).
 */
object StatusParser {
    private const val MAX_PACKET_CHARS = 4096

    private class FieldError(message: String) : Exception(message)

    fun parse(raw: String): ParseResult {
        if (raw.isBlank() || raw.length > MAX_PACKET_CHARS) return ParseResult.Invalid("Empty or oversized packet")
        val obj = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return ParseResult.Invalid("Malformed JSON")
        }
        return try {
            val temperature = obj.sensor("temperature", AppConfig.TEMP_MIN_C, AppConfig.TEMP_MAX_C)
            val voltage = obj.sensor("voltage", AppConfig.VOLTAGE_MIN_V, AppConfig.VOLTAGE_MAX_V)
            val current = obj.sensor("current", AppConfig.CURRENT_MIN_A, AppConfig.CURRENT_MAX_A)
            val oil = obj.sensor("oilLevel", 0.0, 100.0)
            val statusText = obj.str("status", 16)
            val status = SystemStatus.parse(statusText) ?: throw FieldError("Unknown status '$statusText'")
            val relay = obj.bool("relay")
            val packet = StsmnStatus(
                deviceId = obj.str("deviceId", 32),
                fw = obj.str("fw", 16),
                uptimeMs = obj.long("uptime", 0L, 9_000_000_000_000_000L),
                temperature = temperature,
                voltage = voltage,
                current = current,
                smoke = obj.bool("smoke"),
                flame = obj.bool("flame"),
                oilLevel = oil?.roundToInt(),
                risk = obj.long("risk", 0L, 100L).toInt(),
                status = status,
                relay = relay,
                alarm = obj.bool("alarm"),
                seq = obj.long("seq", 0L, 9_000_000_000_000_000L),
            )
            val anomalies = buildList {
                if (status == SystemStatus.EMERGENCY && relay) add("Relay fault: EMERGENCY but supply is still ON")
            }
            ParseResult.Valid(packet, anomalies)
        } catch (e: FieldError) {
            ParseResult.Invalid(e.message ?: "Invalid field")
        }
    }

    private fun JSONObject.str(key: String, maxLen: Int): String {
        val v = opt(key)
        if (v !is String || v.isBlank() || v.length > maxLen) throw FieldError("Field '$key' missing or invalid")
        return v
    }

    private fun JSONObject.bool(key: String): Boolean {
        val v = opt(key)
        if (v !is Boolean) throw FieldError("Field '$key' must be true/false")
        return v
    }

    private fun JSONObject.long(key: String, min: Long, max: Long): Long {
        val v = opt(key)
        if (v !is Number) throw FieldError("Field '$key' missing or not a number")
        val d = v.toDouble()
        if (d.isNaN() || d != Math.floor(d) || d < min.toDouble() || d > max.toDouble()) {
            throw FieldError("Field '$key' out of range")
        }
        return v.toLong()
    }

    /** Required key; value may be null (= sensor unavailable). Out-of-range -> unavailable. */
    private fun JSONObject.sensor(key: String, min: Double, max: Double): Double? {
        if (!has(key)) throw FieldError("Field '$key' missing")
        val v = opt(key)
        if (v == null || v == JSONObject.NULL) return null
        if (v !is Number) throw FieldError("Field '$key' must be a number or null")
        val d = v.toDouble()
        if (d.isNaN() || d.isInfinite() || d < min || d > max) return null
        return d
    }
}
