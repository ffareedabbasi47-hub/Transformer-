package com.stsmn.monitor.data

import android.os.SystemClock
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.ConnectionTracker
import com.stsmn.monitor.domain.EventDetector
import com.stsmn.monitor.domain.EventRecord
import com.stsmn.monitor.domain.EventType
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.ParseResult
import com.stsmn.monitor.domain.StatusParser
import com.stsmn.monitor.domain.StsmnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single source of truth for the ESP32 link. Everything here runs on the main
 * thread (only the HTTP call hops to IO), so there are no data races.
 * The phone is a viewer only: nothing in this class can influence the ESP32.
 */
class StsmnRepository(
    private val client: EspClient,
    private val binder: WifiNetworkBinder,
    private val eventStore: EventStore,
    private val settings: StateFlow<AppSettings>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = ConnectionTracker()
    private var detector = EventDetector()
    private var pollJob: Job? = null
    private var tickJob: Job? = null

    private var lastStatus: StsmnStatus? = null
    private var lastValidWallMs: Long? = null
    private var lastValidElapsedMs: Long? = null
    private var anomalies: List<String> = emptyList()
    private val recent = ArrayDeque<StsmnStatus>()
    private var packets = 0L
    private var parseErrors = 0L
    private var networkErrors = 0L
    private var duplicates = 0L
    private var lastError: String? = null
    private var wasLive = false
    private var everLive = false
    private var activeHost: String? = null

    private val _snapshot = MutableStateFlow(LiveSnapshot())
    val snapshot: StateFlow<LiveSnapshot> = _snapshot.asStateFlow()

    /** User wants monitoring: start polling. */
    fun start() {
        tracker.start()
        launchJobs()
        publish()
    }

    /** User disconnected: stop polling, state becomes IDLE. */
    fun stop() {
        cancelJobs()
        tracker.stop()
        publish()
    }

    /** App went to background: stop polling but remember that monitoring is wanted. */
    fun pause() { cancelJobs() }

    /** App is active again: resume polling if the user had monitoring on. */
    fun resume() {
        if (tracker.isStarted) { launchJobs(); publish() }
    }

    private fun launchJobs() {
        if (pollJob?.isActive == true) return
        binder.start()
        pollJob = scope.launch { pollLoop() }
        tickJob = scope.launch {
            while (true) { delay(AppConfig.STATE_TICK_MS); publish() }
        }
    }

    private fun cancelJobs() {
        pollJob?.cancel(); tickJob?.cancel()
        pollJob = null; tickJob = null
        binder.stop()
    }

    fun close() { stop(); scope.cancel() }

    private fun staleTimeoutMs(): Long {
        val s = settings.value
        return maxOf(s.staleTimeoutMs, s.pollIntervalMs * AppConfig.MIN_STALE_POLL_MULTIPLE)
    }

    private suspend fun pollLoop() {
        while (true) {
            val s = settings.value
            if (activeHost != null && activeHost != s.deviceHost) resetForNewHost()
            activeHost = s.deviceHost

            when (val result = client.fetchStatus(s.deviceHost)) {
                is FetchResult.Failure -> {
                    tracker.onFailure(); networkErrors++; lastError = result.message
                }
                is FetchResult.Body -> handleBody(result.text)
            }
            publish()

            val failures = tracker.consecutiveFailures
            val wait = if (failures == 0) s.pollIntervalMs
            else (s.pollIntervalMs * (failures + 1)).coerceAtMost(AppConfig.MAX_BACKOFF_MS) // reconnect backoff
            delay(wait)
        }
    }

    private fun handleBody(text: String) {
        when (val r = StatusParser.parse(text)) {
            is ParseResult.Invalid -> {
                tracker.onFailure(); parseErrors++; lastError = r.reason
            }
            is ParseResult.Valid -> {
                val st = r.status
                val prev = lastStatus
                // Same/older seq without an uptime reset = repeated or stuck packet: NOT fresh data.
                val duplicate = prev != null && st.uptimeMs >= prev.uptimeMs && st.seq <= prev.seq
                if (duplicate) {
                    duplicates++; lastError = "Duplicate packet (seq ${st.seq})"
                } else {
                    tracker.onValidPacket(SystemClock.elapsedRealtime())
                    lastStatus = st
                    lastValidWallMs = System.currentTimeMillis()
                    lastValidElapsedMs = SystemClock.elapsedRealtime()
                    anomalies = r.anomalies
                    packets++
                    recent.addLast(st); while (recent.size > AppConfig.TREND_POINTS) recent.removeFirst()
                    lastError = null
                    detector.onPacket(System.currentTimeMillis(), st).forEach { eventStore.add(it) }
                }
            }
        }
    }

    private fun resetForNewHost() {
        tracker.reset()
        detector = EventDetector()
        lastStatus = null
        lastValidWallMs = null
        lastValidElapsedMs = null
        anomalies = emptyList()
        recent.clear()
        wasLive = false
        lastError = null
    }

    private fun publish() {
        val now = SystemClock.elapsedRealtime()
        val state = tracker.stateAt(now, staleTimeoutMs())
        val live = state == ConnectionState.LIVE
        val deviceId = lastStatus?.deviceId ?: "device"
        if (live && !wasLive) {
            addConnectionEvent(EventType.CONNECTION_RESTORED,
                if (everLive) "Valid data received again" else "Connected to $deviceId")
            wasLive = true; everLive = true
        } else if (!live && wasLive) {
            if (state == ConnectionState.STALE || state == ConnectionState.ERROR) {
                addConnectionEvent(EventType.CONNECTION_LOST, "No valid packet from $deviceId")
            }
            wasLive = false
        }
        _snapshot.value = LiveSnapshot(
            connection = state,
            status = lastStatus,
            packetAgeMs = tracker.ageMs(now),
            lastValidWallMs = lastValidWallMs,
            lastValidElapsedMs = lastValidElapsedMs,
            packetsReceived = packets,
            parseErrors = parseErrors,
            networkErrors = networkErrors,
            duplicatePackets = duplicates,
            consecutiveFailures = tracker.consecutiveFailures,
            lastError = lastError,
            anomalies = anomalies,
            wifiBound = binder.isBound,
            host = settings.value.deviceHost,
            recent = recent.toList(),
        )
    }

    private fun addConnectionEvent(type: EventType, message: String) {
        val s = lastStatus
        eventStore.add(EventRecord(System.currentTimeMillis(), s?.deviceId ?: "unknown", type,
            s?.risk, s?.status?.name, s?.relay, message))
    }
}
