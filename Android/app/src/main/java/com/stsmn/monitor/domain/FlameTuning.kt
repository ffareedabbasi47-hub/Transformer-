package com.stsmn.monitor.domain

import org.json.JSONException
import org.json.JSONObject

data class FlameLive(
    val usable: Boolean, val ao: Int, val base: Int, val dev: Int, val noise: Double,
    val reversals: Int, val doOn: Boolean, val pct: Int, val critical: Boolean,
    val why: Int = 0, val flicker: Boolean = false, val corroborated: Boolean = false,
)

data class FlameTuning(
    val minDelta: Int = 40,
    val flicker: Double = 4.0,
    val reversals: Int = 4,
    val confirmMs: Int = 500,
    val rebaseSec: Int = 20,
    val requireDo: Boolean = true,
) {
    fun clamped() = copy(
        minDelta = minDelta.coerceIn(MIN_DELTA_RANGE.first, MIN_DELTA_RANGE.last),
        flicker = flicker.coerceIn(FLICKER_RANGE.start, FLICKER_RANGE.endInclusive),
        reversals = reversals.coerceIn(REVERSALS_RANGE.first, REVERSALS_RANGE.last),
        confirmMs = confirmMs.coerceIn(CONFIRM_RANGE.first, CONFIRM_RANGE.last),
        rebaseSec = rebaseSec.coerceIn(REBASE_RANGE.first, REBASE_RANGE.last),
    )

    fun toForm(relearn: Boolean = false): Map<String, String> = clamped().let {
        buildMap {
            put("minDelta", it.minDelta.toString())
            put("flicker", String.format(java.util.Locale.US, "%.1f", it.flicker))
            put("reversals", it.reversals.toString())
            put("confirmMs", it.confirmMs.toString())
            put("rebaseMs", (it.rebaseSec * 1000).toString())
            put("requireDo", if (it.requireDo) "1" else "0")
            if (relearn) put("relearn", "1")
        }
    }

    companion object {
        val MIN_DELTA_RANGE = 10..400
        val FLICKER_RANGE = 2.0..12.0
        val REVERSALS_RANGE = 2..16
        val CONFIRM_RANGE = 250..3000
        val REBASE_RANGE = 3..60

        fun parse(json: String): Pair<FlameTuning, FlameLive?>? = try {
            val o = JSONObject(json)
            val t = FlameTuning(
                minDelta = o.getInt("minDelta"), flicker = o.getDouble("flicker"), reversals = o.getInt("reversals"),
                confirmMs = o.getInt("confirmMs"), rebaseSec = o.getInt("rebaseMs") / 1000, requireDo = o.getInt("requireDo") != 0,
            ).clamped()
            val l = o.optJSONObject("live")?.let {
                FlameLive(it.getInt("usable") != 0, it.getInt("ao"), it.getInt("base"), it.getInt("dev"), it.getDouble("noise"),
                    it.getInt("rev"), it.getInt("do") != 0, it.getInt("pct"), it.getInt("critical") != 0,
                    it.optInt("why", 0), it.optInt("flick", 0) != 0, it.optInt("corr", 0) != 0)
            }
            t to l
        } catch (e: JSONException) { null }
    }
}
