package com.stsmn.monitor.domain

import org.json.JSONObject

/** How fresh a transformer's data is, as decided by the SERVER (live / stale / offline). */
enum class FleetLink { LIVE, STALE, OFFLINE }

/** Connection of this phone to the realtime server. */
enum class CloudState { OFF, CONNECTING, ONLINE, RETRYING, AUTH_FAILED }

/** One transformer as seen by every viewer. [ageAtReceiptMs] + local clock = live age (no phone/server clock skew). */
data class FleetDevice(
    val id: String,
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val demoLocation: Boolean,
    val link: FleetLink,
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
    val ageAtReceiptMs: Long?,
    val receivedElapsedMs: Long,
) {
    fun ageMs(nowElapsedMs: Long): Long? = ageAtReceiptMs?.let { it + (nowElapsedMs - receivedElapsedMs).coerceAtLeast(0L) }
    val hasLocation: Boolean get() = lat != null && lng != null && SiteLocationRules.isValid(lat, lng)
}

data class FleetState(
    val cloud: CloudState = CloudState.OFF,
    val devices: List<FleetDevice> = emptyList(),
    val publishing: CloudState = CloudState.OFF,
)

object FleetParser {
    private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { it.isFinite() }
    private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private fun JSONObject.bool(k: String): Boolean? = if (isNull(k) || !has(k)) null else optBoolean(k)

    /** Parses one device object from the server. Returns null when the id is missing/invalid (never throws). */
    fun device(o: JSONObject, serverNowMs: Long?, elapsedNowMs: Long): FleetDevice? = try {
        val id = o.optString("id", "")
        if (!CloudRules.isValidDeviceId(id)) null else {
            val ts = if (o.isNull("ts") || !o.has("ts")) null else o.optLong("ts")
            val age = if (ts != null && serverNowMs != null) (serverNowMs - ts).coerceAtLeast(0L) else if (ts != null) 0L else null
            FleetDevice(
                id = id,
                name = o.optString("name", id).take(40),
                lat = o.dbl("lat"), lng = o.dbl("lng"),
                demoLocation = o.optBoolean("demo", false),
                link = when (o.optString("link")) { "live" -> FleetLink.LIVE; "stale" -> FleetLink.STALE; else -> FleetLink.OFFLINE },
                status = o.optString("status", "").let { SystemStatus.parse(it) },
                risk = o.int("risk")?.coerceIn(0, 100),
                temperature = o.dbl("temperature"), voltage = o.dbl("voltage"), current = o.dbl("current"),
                oilLevel = o.int("oilLevel"),
                smoke = o.bool("smoke"), flame = o.bool("flame"), relay = o.bool("relay"), alarm = o.bool("alarm"),
                ageAtReceiptMs = age, receivedElapsedMs = elapsedNowMs,
            )
        }
    } catch (e: Exception) { null }

    fun markerState(d: FleetDevice, cloudOnline: Boolean): MarkerState = when {
        d.link == FleetLink.OFFLINE -> MarkerState.OFFLINE
        d.link == FleetLink.STALE || !cloudOnline -> MarkerState.STALE
        else -> when (d.status) {
            SystemStatus.SAFE -> MarkerState.SAFE
            SystemStatus.WARNING -> MarkerState.WARNING
            SystemStatus.EMERGENCY -> MarkerState.EMERGENCY
            null -> MarkerState.OFFLINE
        }
    }

    /** Most dangerous first, so an emergency is never buried in the list. */
    fun severity(m: MarkerState): Int = when (m) {
        MarkerState.EMERGENCY -> 0; MarkerState.WARNING -> 1; MarkerState.STALE -> 2; MarkerState.OFFLINE -> 3; MarkerState.SAFE -> 4
    }
}
