package com.stsmn.monitor.domain

import org.json.JSONArray
import org.json.JSONObject

/** Data the 3D globe page receives. Pure Kotlin so it is unit-tested without a phone. */
object GlobeData {
    val ID_RE = Regex("^[A-Z0-9][A-Z0-9-]{1,23}$")

    fun validId(id: String?): Boolean = id != null && ID_RE.matches(id)

    /**
     * Only transformers with a valid ID AND a valid position are sent. org.json escapes everything,
     * so a device name can never become script. Devices without a position stay in the list screens.
     */
    fun json(devices: List<MapDevice>): String {
        val arr = JSONArray()
        for (d in devices) {
            if (!d.hasLocation || !validId(d.id)) continue

            val obj = JSONObject()
                .put("id", d.id)
                .put("name", d.name)
                .put("lat", d.lat)
                .put("lng", d.lng)
                .put("state", d.state.name)
                .put("demo", d.demoLocation)
                .put("isLive", d.isLive)

            d.status?.let { obj.put("status", it.name) }
            d.risk?.let { obj.put("risk", it) }
            d.temperature?.let { obj.put("temp", it) }
            d.voltage?.let { obj.put("volt", it) }
            d.current?.let { obj.put("amp", it) }
            d.oilLevel?.let { obj.put("oil", it) }
            d.smoke?.let { obj.put("smoke", it) }
            d.flame?.let { obj.put("flame", it) }
            d.relay?.let { obj.put("relay", it) }
            d.alarm?.let { obj.put("alarm", it) }
            d.ageMs?.let { obj.put("ageMs", it) }

            arr.put(obj)
        }
        return arr.toString()
    }
}
