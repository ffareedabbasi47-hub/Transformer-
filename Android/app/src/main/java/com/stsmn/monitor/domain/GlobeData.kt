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
            arr.put(
                JSONObject().put("id", d.id).put("lat", d.lat).put("lng", d.lng)
                    .put("state", d.state.name).put("demo", d.demoLocation),
            )
        }
        return arr.toString()
    }
}
