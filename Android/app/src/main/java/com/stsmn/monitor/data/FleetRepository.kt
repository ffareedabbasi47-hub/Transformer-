package com.stsmn.monitor.data

import android.os.SystemClock
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.FleetDevice
import com.stsmn.monitor.domain.FleetParser
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.StsmnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import org.json.JSONObject

/**
 * Realtime fleet: ONE socket to WATCH every transformer (viewer key) and, optionally, ONE socket to PUBLISH this
 * phone's own transformer (its private device token). A phone can only ever publish the ID its token belongs to.
 */
class FleetRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewer = RealtimeLink(scope)
    private val publisher = RealtimeLink(scope)

    private val devices = MutableStateFlow<Map<String, FleetDevice>>(emptyMap())

    val state: StateFlow<FleetState> = combine(viewer.state, publisher.state, devices) { v, p, d ->
        FleetState(cloud = v, publishing = p, devices = d.values.sortedBy { it.id })
    }.stateIn(scope, SharingStarted.Eagerly, FleetState())

    private var applied: CloudKey? = null
    private data class CloudKey(val url: String, val viewerKey: String, val id: String, val token: String)

    init {
        viewer.onMessage = { m ->
            val now = SystemClock.elapsedRealtime()
            when (m.optString("t")) {
                "snapshot" -> {
                    val serverNow = if (m.has("now")) m.optLong("now") else null
                    val arr = m.optJSONArray("devices")
                    val map = LinkedHashMap<String, FleetDevice>()
                    if (arr != null) for (i in 0 until minOf(arr.length(), 500)) {
                        arr.optJSONObject(i)?.let { FleetParser.device(it, serverNow, now) }?.let { map[it.id] = it }
                    }
                    devices.value = map
                }
                "update" -> m.optJSONObject("device")?.let { FleetParser.device(it, 0L, now) }?.let { d ->
                    devices.value = devices.value + (d.id to d)
                }
            }
        }
    }

    /** Called whenever settings change. Restarts the sockets only when the cloud settings really changed. */
    fun configure(s: AppSettings) {
        val key = if (s.cloudEnabled && s.serverUrl.isNotEmpty()) CloudKey(s.serverUrl, s.viewerKey, s.cloudDeviceId, s.deviceToken) else null
        if (key == applied) return
        applied = key
        viewer.stop(); publisher.stop()
        devices.value = emptyMap()
        if (key == null) return
        if (key.viewerKey.isNotEmpty()) {
            viewer.start(key.url, JSONObject().put("t", "auth").put("role", "viewer").put("key", key.viewerKey).toString())
        }
        if (key.id.isNotEmpty() && key.token.isNotEmpty()) {
            publisher.start(key.url, JSONObject().put("t", "auth").put("role", "device").put("id", key.id).put("key", key.token).toString())
        }
    }

    /** Sends one live sample of this phone's transformer. lat/lng are the configured SITE coordinates, not phone GPS. */
    fun publish(st: StsmnStatus, lat: Double?, lng: Double?, demoLocation: Boolean) {
        val o = JSONObject().put("t", "telemetry").put("seq", st.seq).put("status", st.status.name).put("risk", st.risk)
            .put("smoke", st.smoke).put("flame", st.flame).put("relay", st.relay).put("alarm", st.alarm)
            .put("temperature", st.temperature ?: JSONObject.NULL)
            .put("voltage", st.voltage ?: JSONObject.NULL)
            .put("current", st.current ?: JSONObject.NULL)
            .put("oilLevel", st.oilLevel ?: JSONObject.NULL)
            .put("fw", st.fw.take(24))
        if (lat != null && lng != null) o.put("lat", lat).put("lng", lng).put("demo", demoLocation)
        publisher.send(o.toString())
    }

    fun close() { viewer.stop(); publisher.stop(); scope.cancel() }
}
