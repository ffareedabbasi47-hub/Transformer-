package com.stsmn.monitor.domain

import com.stsmn.monitor.config.AppConfig

data class MapDevice(
    val id: String,
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val demoLocation: Boolean,
    val state: MarkerState,
    val status: SystemStatus?,
    val risk: Int?,
    val temperature: Double?,
    val voltage: Double?,
    val current: Double?,
    val oilLevel: Int?,
    val smoke: Boolean?,
    val flame: Boolean?,
    val relay: Boolean?,
    val alarm: Boolean?,
    val ageMs: Long?,
    val isLocal: Boolean,
    val locationLive: Boolean = false,
) {
    val label: String get() = TransformerLabel.from(id)
    val hasLocation: Boolean get() = lat != null && lng != null && SiteLocationRules.isValid(lat, lng)

    val isLive: Boolean get() = state == MarkerState.SAFE || state == MarkerState.WARNING || state == MarkerState.EMERGENCY
}

object MapDevices {
    fun idOf(deviceId: String?): String =
        (deviceId ?: AppConfig.DEFAULT_TRANSFORMER_LABEL).removePrefix(AppConfig.TRANSFORMER_LABEL_PREFIX).uppercase()

    fun build(
        live: LiveSnapshot,
        siteLat: Double?, siteLng: Double?, siteIsDemo: Boolean,
        fleet: FleetState,
        nowElapsedMs: Long,
    ): List<MapDevice> {
        val cloudOnline = fleet.cloud == CloudState.ONLINE
        val out = LinkedHashMap<String, MapDevice>()
        for (d in fleet.devices) {
            out[d.id] = MapDevice(
                d.id, d.name, d.lat, d.lng, d.demoLocation, FleetParser.markerState(d, cloudOnline), d.status, d.risk,
                d.temperature, d.voltage, d.current, d.oilLevel, d.smoke, d.flame, d.relay, d.alarm,
                d.ageMs(nowElapsedMs), isLocal = false,
            )
        }
        val st = live.status
        val showLocal = st != null || live.connection != ConnectionState.IDLE || out.isEmpty()
        if (showLocal) {
            val id = idOf(st?.deviceId)
            val prev = out[id]
            val keepServerView = prev != null && live.connection != ConnectionState.LIVE
            val age = live.lastValidElapsedMs?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
            val gps = live.gps?.takeIf {
                val t = live.gpsElapsedMs
                t != null && nowElapsedMs - t in 0..AppConfig.GPS_MAX_AGE_MS
            }
            if (!keepServerView) out[id] = MapDevice(
                id, prev?.name ?: id,
                gps?.lat ?: siteLat ?: prev?.lat, gps?.lng ?: siteLng ?: prev?.lng,
                if (gps != null) false else if (siteLat != null) siteIsDemo else prev?.demoLocation ?: false,
                MarkerStateMapper.from(live), st?.status, st?.risk,
                st?.temperature, st?.voltage, st?.current, st?.oilLevel, st?.smoke, st?.flame, st?.relay, st?.alarm,
                age, isLocal = true, locationLive = gps != null,
            )
        }
        return out.values.sortedWith(compareBy({ FleetParser.severity(it.state) }, { it.id }))
    }
}
