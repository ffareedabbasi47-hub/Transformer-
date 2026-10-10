package com.stsmn.monitor.data

import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.domain.EventRecord
import com.stsmn.monitor.domain.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded event history stored as one small JSON file on the phone (newest first). */
class EventStore(private val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val _events = MutableStateFlow<List<EventRecord>>(emptyList())
    val events: StateFlow<List<EventRecord>> = _events.asStateFlow()

    init {
        scope.launch {
            val loaded = load()
            _events.update { current ->
                (current + loaded).sortedByDescending { it.timestampMs }.take(AppConfig.HISTORY_MAX_EVENTS)
            }
        }
    }

    fun add(event: EventRecord) {
        _events.update { (listOf(event) + it).take(AppConfig.HISTORY_MAX_EVENTS) }
        persist()
    }

    fun clear() {
        _events.value = emptyList()
        persist()
    }

    private fun persist() {
        scope.launch {
            writeMutex.withLock {
                try {
                    val arr = JSONArray()
                    _events.value.forEach { arr.put(toJson(it)) }
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(arr.toString())
                    if (!tmp.renameTo(file)) { file.writeText(arr.toString()); tmp.delete() }
                } catch (e: Exception) {
                    // History is best-effort; never crash the app because of storage.
                }
            }
        }
    }

    private fun load(): List<EventRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    try { add(fromJson(arr.getJSONObject(i))) } catch (e: Exception) { /* skip bad row */ }
                }
            }
        } catch (e: Exception) {
            emptyList() // corrupted file -> start clean
        }
    }

    private fun toJson(e: EventRecord) = JSONObject().apply {
        put("timestamp", e.timestampMs)
        put("deviceId", e.deviceId)
        put("event", e.type.name)
        e.risk?.let { put("risk", it) }
        e.status?.let { put("status", it) }
        e.relay?.let { put("relay", it) }
        put("message", e.message)
    }

    private fun fromJson(o: JSONObject) = EventRecord(
        timestampMs = o.getLong("timestamp"),
        deviceId = o.getString("deviceId"),
        type = EventType.valueOf(o.getString("event")),
        risk = if (o.has("risk")) o.getInt("risk") else null,
        status = if (o.has("status")) o.getString("status") else null,
        relay = if (o.has("relay")) o.getBoolean("relay") else null,
        message = o.getString("message"),
    )
}
