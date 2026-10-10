package com.stsmn.monitor.domain

import com.stsmn.monitor.config.AppConfig

/** Everything the map / fleet list needs about one transformer, whether it came from the server or the local ESP32. */
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
) {
    val label: String get() = TransformerLabel.from(id)
    val hasLocation: Boolean get() = lat != null && lng != null && SiteLocationRules.isValid(lat, lng)
    /** Values are only "live" when the state says so; otherwise they are last-known and shown dimmed. */
    val isLive: Boolean get() = state == MarkerState.SAFE || state == MarkerState.WARNING || state == MarkerState.EMERGENCY
}

object MapDevices {
    fun idOf(deviceId: String?): String =
        (deviceId ?: AppConfig.DEFAULT_TRANSFORMER_LABEL).removePrefix(AppConfig.TRANSFORMER_LABEL_PREFIX).uppercase()

    /**
     * Server devices + this phone's directly connected ESP32. The local reading wins for the same ID because it
     * is first-hand and keeps working when the internet is down. Sorted most dangerous first, then by ID.
     */
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
            if (!keepServerView) out[id] = MapDevice(
                id, prev?.name ?: id, siteLat ?: prev?.lat, siteLng ?: prev?.lng,
                if (siteLat != null) siteIsDemo else prev?.demoLocation ?: false,
                MarkerStateMapper.from(live), st?.status, st?.risk,
                st?.temperature, st?.voltage, st?.current, st?.oilLevel, st?.smoke, st?.flame, st?.relay, st?.alarm,
                age, isLocal = true,
            )
        }
        return out.values.sortedWith(compareBy({ FleetParser.severity(it.state) }, { it.id }))
    }
}
