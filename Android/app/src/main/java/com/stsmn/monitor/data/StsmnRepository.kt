package com.stsmn.monitor.data

import android.os.SystemClock
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.domain.BleMessage
import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.ConnectionTracker
import com.stsmn.monitor.domain.EventDetector
import com.stsmn.monitor.domain.EventRecord
import com.stsmn.monitor.domain.EventType
import com.stsmn.monitor.domain.HealthParser
import com.stsmn.monitor.domain.LiveGps
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.ParseResult
import com.stsmn.monitor.domain.StatusParser
import com.stsmn.monitor.domain.StsmnStatus
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class StsmnRepository(
    private val client: EspClient,
    private val binder: WifiNetworkBinder,
    private val eventStore: EventStore,
    private val settings: StateFlow<AppSettings>,
    private val ble: BleClient? = null,
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
    private var lastGps: LiveGps? = null
    private var lastGpsElapsedMs: Long? = null
    private var lastGpsPollMs = 0L
    private var lastDevicePhone: String? = null
    @Volatile private var pendingReply: CompletableDeferred<BleMessage>? = null

    private val _snapshot = MutableStateFlow(LiveSnapshot())
    val snapshot: StateFlow<LiveSnapshot> = _snapshot.asStateFlow()

    fun start() {
        tracker.start()
        launchJobs()
        publish()
    }

    fun stop() {
        cancelJobs()
        tracker.stop()
        publish()
    }

    fun pause() { cancelJobs() }

    fun resume() {
        if (tracker.isStarted) { launchJobs(); publish() }
    }

    private fun launchJobs() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { modeLoop() }
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

    private suspend fun modeLoop() {
        while (true) {
            if (settings.value.useBluetooth && ble != null) bleLoop() else pollLoop()
        }
    }

    private suspend fun pollLoop() {
        binder.start()
        try { pollLoopInner() } finally { binder.stop() }
    }

    private suspend fun pollLoopInner() {
        resetForNewHost(); activeHost = null
        while (!(settings.value.useBluetooth && ble != null)) {
            val s = settings.value
            if (activeHost != null && activeHost != s.deviceHost) resetForNewHost()
            activeHost = s.deviceHost

            when (val result = client.fetchStatus(s.deviceHost)) {
                is FetchResult.Failure -> {
                    tracker.onFailure(); networkErrors++; lastError = result.message
                }
                is FetchResult.Body -> {
                    handleBody(result.text)
                    pollGps(s.deviceHost)
                }
            }
            publish()

            val failures = tracker.consecutiveFailures
            val wait = if (failures == 0) s.pollIntervalMs
            else (s.pollIntervalMs * (failures + 1)).coerceAtMost(AppConfig.MAX_BACKOFF_MS)
            delay(wait)
        }
    }

    private suspend fun bleLoop() {
        val b = ble ?: return
        resetForNewHost(); activeHost = null
        b.start()
        val collector = scope.launch { b.lines.collect { onBleLine(it) } }
        try {
            while (settings.value.useBluetooth) {
                if (b.state.value != BleState.CONNECTED) {
                    tracker.onFailure(); lastError = bleStateText(b.state.value)
                }
                publish()
                delay(2000)
            }
        } finally { collector.cancel(); b.stop() }
    }

    private fun bleStateText(s: BleState): String = when (s) {
        BleState.OFF -> "Bluetooth is off - turn it on"
        BleState.NO_PERMISSION -> "Bluetooth permission needed (tap Connect)"
        BleState.UNSUPPORTED -> "This phone has no Bluetooth LE"
        BleState.SCANNING -> "Searching for the ESP32 (STSMN) over Bluetooth..."
        BleState.CONNECTING -> "Connecting over Bluetooth..."
        BleState.CONNECTED -> "Bluetooth connected"
        BleState.IDLE -> "Bluetooth: waiting to search again"
    }

    private fun onBleLine(line: String) {
        val m = BleMessage.parse(line) ?: return
        when (m.kind) {
            BleMessage.Kind.STATUS -> { handleBody(m.payload); publish() }
            BleMessage.Kind.HEALTH -> {
                HealthParser.parseGps(m.payload)?.let { lastGps = it; lastGpsElapsedMs = SystemClock.elapsedRealtime() }
                lastDevicePhone = HealthParser.parseAlertPhone(m.payload)
            }
            BleMessage.Kind.OK, BleMessage.Kind.ERROR -> pendingReply?.complete(m)
        }
    }

    private suspend fun pollGps(host: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGpsPollMs < AppConfig.GPS_POLL_EVERY_MS) return
        lastGpsPollMs = now
        val r = client.fetchHealth(host)
        if (r is FetchResult.Body) {
            HealthParser.parseGps(r.text)?.let { lastGps = it; lastGpsElapsedMs = SystemClock.elapsedRealtime() }
            lastDevicePhone = HealthParser.parseAlertPhone(r.text)
        }
    }

    suspend fun flameGet(): FetchResult = client.fetchFlame(settings.value.deviceHost)
    suspend fun flamePost(form: Map<String, String>): FetchResult = client.postFlame(settings.value.deviceHost, form + ("token" to settings.value.espToken))
    suspend fun resetLatch(): FetchResult = client.postReset(settings.value.deviceHost, settings.value.espToken)

    suspend fun sendAlertPhone(phone: String): FetchResult {
        val s = settings.value
        val b = ble
        if (!s.useBluetooth || b == null) return client.postContact(s.deviceHost, phone, s.espToken)
        if (s.blePassword.isEmpty()) return FetchResult.Failure("Enter the device password (Settings > Bluetooth) to save the number over Bluetooth")
        if (b.state.value != BleState.CONNECTED) return FetchResult.Failure("Bluetooth is not connected")
        val reply = CompletableDeferred<BleMessage>()
        pendingReply = reply
        try {
            val sent = withContext(Dispatchers.IO) { b.write(BleMessage.phoneCommand(s.blePassword, phone)) }
            if (!sent) return FetchResult.Failure("Could not send over Bluetooth")
            val m = withTimeoutOrNull(6000) { reply.await() } ?: return FetchResult.Failure("No answer from the ESP32")
            return if (m.kind == BleMessage.Kind.OK) FetchResult.Body(m.payload) else FetchResult.Failure(m.payload)
        } finally { pendingReply = null }
    }

    private fun handleBody(text: String) {
        when (val r = StatusParser.parse(text)) {
            is ParseResult.Invalid -> {
                tracker.onFailure(); parseErrors++; lastError = r.reason
            }
            is ParseResult.Valid -> {
                val st = r.status
                val prev = lastStatus

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
        lastGps = null; lastGpsElapsedMs = null; lastGpsPollMs = 0L; lastDevicePhone = null
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
            viaBluetooth = settings.value.useBluetooth && ble != null,
            linkNote = ble?.let { if (settings.value.useBluetooth) bleStateText(it.state.value) else "" } ?: "",
            recent = recent.toList(),
            gps = lastGps,
            deviceAlertPhone = lastDevicePhone,
            gpsElapsedMs = lastGpsElapsedMs,
        )
    }

    private fun addConnectionEvent(type: EventType, message: String) {
        val s = lastStatus
        eventStore.add(EventRecord(System.currentTimeMillis(), s?.deviceId ?: "unknown", type,
            s?.risk, s?.status?.name, s?.relay, message))
    }
}
