package com.stsmn.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.data.AppSettings
import com.stsmn.monitor.data.BleClient
import com.stsmn.monitor.data.EspClient
import com.stsmn.monitor.data.EventStore
import com.stsmn.monitor.data.KeystoreCodec
import com.stsmn.monitor.data.SettingsStore
import com.stsmn.monitor.data.StsmnRepository
import com.stsmn.monitor.data.WifiNetworkBinder
import com.stsmn.monitor.data.FetchResult
import com.stsmn.monitor.data.WhatsAppSender
import com.stsmn.monitor.domain.AlertContact
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.stsmn.monitor.data.FleetRepository
import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.LocationSource
import com.stsmn.monitor.domain.FleetLink
import com.stsmn.monitor.domain.SystemStatus
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** UI -> ViewModel -> Repository -> Network -> ESP32 (PDF app architecture). */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app, KeystoreCodec())
    private val eventStore = EventStore(File(app.filesDir, AppConfig.HISTORY_FILE_NAME))
    private val binder = WifiNetworkBinder(app)
    private val ble = BleClient(app)
    private val repository = StsmnRepository(EspClient(binder), binder, eventStore, settingsStore.settings, ble)

    val settings = settingsStore.settings
    val live = repository.snapshot
    val events = eventStore.events
    private val fleetRepository = FleetRepository()
    val fleet = fleetRepository.state
    private var lastPublishedSeq = -1L
    private val notifier = AlertNotifier(app)
    private val whatsApp = WhatsAppSender(app)
    private val _contactStatus = MutableStateFlow<String?>(null)
    val contactStatus: StateFlow<String?> = _contactStatus.asStateFlow()     // "Saved on ESP32 +91*****48", errors...
    private val _whatsappStatus = MutableStateFlow<String?>(null)
    val whatsappStatus: StateFlow<String?> = _whatsappStatus.asStateFlow()  // result of the last automatic WhatsApp
    private var lastContactSyncMs = 0L
    private var emergencyIds = emptySet<String>()

    private var wantConnected = false
    private var foreground = false

    init {
        if (settings.value.autoConnect) { wantConnected = true; repository.start() }
        // Cloud sockets follow the settings (restarted only when cloud settings actually change).
        viewModelScope.launch { settings.collect { fleetRepository.configure(it) } }
        // Notify once when a transformer ENTERS emergency (local ESP32 or any transformer on the server).
        viewModelScope.launch {
            combine(live, fleet) { l, f ->
                buildMap<String, Pair<Int?, Boolean?>> {
                    val st = l.status
                    if (l.connection == ConnectionState.LIVE && st != null && st.status == SystemStatus.EMERGENCY) {
                        put(st.deviceId.removePrefix("STSMN-").uppercase(), st.risk to !st.relay)
                    }
                    f.devices.filter { it.link == FleetLink.LIVE && it.status == SystemStatus.EMERGENCY }
                        .forEach { putIfAbsent(it.id, it.risk to it.relay?.not()) }
                }
            }.collect { now ->
                val fresh = now.filterKeys { it !in emergencyIds }
                fresh.forEach { (id, v) ->
                    val cfg = settings.value
                    val st = live.value.status
                    val localId = st?.deviceId?.removePrefix("STSMN-")?.uppercase()
                    val phone = cfg.alertPhone
                    val text = if (st != null && id == localId) AlertContact.message(st) else "STSMN ALERT $id: EMERGENCY. Risk ${v.first ?: "?"}/100."
                    notifier.emergency(id, v.first, v.second)
                    // Fully automatic WhatsApp (only if a CallMeBot key was saved) - never blocks anything else.
                    if (phone.isNotEmpty() && cfg.whatsappKey.isNotEmpty()) launch { sendWhatsAppWithRetry(id, phone, text, cfg.whatsappKey) }
                }
                emergencyIds = now.keys
            }
        }
        // Keep the ESP32's SMS number equal to the one saved in the app (re-sent when the ESP32 reports a different/empty one).
        viewModelScope.launch {
            live.collect { l ->
                val phone = settings.value.alertPhone
                val dev = l.deviceAlertPhone
                if (phone.isEmpty() || l.connection != ConnectionState.LIVE || dev == null) return@collect
                if (dev == AlertContact.mask(phone)) return@collect
                if (SystemClock.elapsedRealtime() - lastContactSyncMs < 20_000L) return@collect
                syncContactToDevice(phone)
            }
        }
        // Every NEW valid packet from the ESP32 is relayed live to the server (this phone = gateway for its transformer).
        viewModelScope.launch {
            live.collect { l ->
                val st = l.status ?: return@collect
                if (l.connection != ConnectionState.LIVE || st.seq == lastPublishedSeq) return@collect
                lastPublishedSeq = st.seq
                val cfg = settings.value
                if (cfg.cloudEnabled && cfg.cloudDeviceId.isNotEmpty()) {
                    fleetRepository.publish(st, cfg.siteLat, cfg.siteLng, cfg.siteSource == LocationSource.DEMO)
                }
            }
        }
    }

    /** Saves the alert number (+ optional WhatsApp key). Returns an error text for the form, or null when accepted. */
    fun saveAlertContact(rawPhone: String, whatsappKey: String): String? {
        val trimmed = rawPhone.trim()
        val phone = if (trimmed.isEmpty()) "" else AlertContact.normalize(trimmed)
            ?: return "That is not a valid mobile number. Example: 75050 45948 or +91 75050 45948"
        updateSettings { it.copy(alertPhone = phone, whatsappKey = whatsappKey.trim()) }
        if (phone.isEmpty()) { _contactStatus.value = "Alert number removed from the app. Removing it on the ESP32 too..." }
        else _contactStatus.value = "Saved in the app: ${AlertContact.mask(phone)}. Sending to the ESP32..."
        syncContactToDevice(phone)
        val key = whatsappKey.trim()
        if (phone.isNotEmpty() && key.isNotEmpty()) viewModelScope.launch {
            val err = whatsApp.send(phone, "STSMN: automatic WhatsApp alerts are connected to this number.", key)
            _whatsappStatus.value = if (err == null) "WhatsApp is connected: a confirmation message was sent." else "WhatsApp key saved, but the confirmation was not sent: $err"
        }
        return null
    }

    /** Automatic WhatsApp: no buttons. Retries every 30 s (up to 6 times) while the emergency lasts and there is no internet yet. */
    private suspend fun sendWhatsAppWithRetry(id: String, phone: String, text: String, key: String) {
        repeat(6) {
            val err = whatsApp.send(phone, text, key)
            if (err == null) { _whatsappStatus.value = "WhatsApp alert sent automatically ($id)"; return }
            _whatsappStatus.value = "WhatsApp alert not sent yet: $err. Retrying..."
            if (id !in emergencyIds) return
            delay(30_000L)
        }
        _whatsappStatus.value = "WhatsApp alert could not be sent (check internet and the key)."
    }

    private fun syncContactToDevice(phone: String) {
        lastContactSyncMs = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            _contactStatus.value = when (val r = repository.sendAlertPhone(phone)) {
                is FetchResult.Body -> if (phone.isEmpty()) "SMS switched off on the ESP32." else "SMS number saved on the ESP32: ${AlertContact.mask(phone)}. SMS and WhatsApp alerts go to this number."
                is FetchResult.Failure -> "Saved in the app. ESP32 not reachable (${r.message}); it is sent automatically when the app is connected."
            }
        }
    }

    fun connect() { wantConnected = true; repository.start() }
    fun disconnect() { wantConnected = false; repository.stop() }

    /** Polling only runs while the app is visible ("while the app is active"). */
    fun onForeground(isForeground: Boolean) {
        foreground = isForeground
        if (isForeground) { if (wantConnected) repository.resume() } else repository.pause()
    }
    fun clearHistory() = eventStore.clear()
    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    override fun onCleared() {
        repository.close()
        fleetRepository.close()
    }
}
