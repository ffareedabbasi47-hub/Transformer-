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
import com.stsmn.monitor.domain.FlameLive
import com.stsmn.monitor.domain.FlameTuning
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
    val contactStatus: StateFlow<String?> = _contactStatus.asStateFlow()
    private val _whatsappStatus = MutableStateFlow<String?>(null)
    val whatsappStatus: StateFlow<String?> = _whatsappStatus.asStateFlow()
    private val _flameTuning = MutableStateFlow<FlameTuning?>(null)
    val flameTuning: StateFlow<FlameTuning?> = _flameTuning.asStateFlow()
    private val _flameLive = MutableStateFlow<FlameLive?>(null)
    val flameLive: StateFlow<FlameLive?> = _flameLive.asStateFlow()
    private val _flameStatus = MutableStateFlow<String?>(null)
    val flameStatus: StateFlow<String?> = _flameStatus.asStateFlow()

    fun refreshFlame() {
        viewModelScope.launch {
            when (val r = repository.flameGet()) {
                is FetchResult.Body -> FlameTuning.parse(r.text)?.let { (t, l) ->
                    if (_flameTuning.value == null) _flameTuning.value = t
                    _flameLive.value = l
                    _flameStatus.value = null
                } ?: run { _flameStatus.value = "ESP32 ka flame jawab padha nahi ja saka" }
                is FetchResult.Failure -> _flameStatus.value = if (r.message.contains("404") || r.message.contains("405")) "ESP32 ka sketch purana hai: sliders chalane ke liye naya .ino upload karo" else "Flame settings: ${r.message}"
            }
        }
    }

    fun pushFlame(t: FlameTuning, relearn: Boolean = false) {
        _flameTuning.value = t.clamped()
        viewModelScope.launch {
            when (val r = repository.flamePost(t.toForm(relearn))) {
                is FetchResult.Body -> { FlameTuning.parse(r.text)?.let { (nt, l) -> _flameTuning.value = nt; _flameLive.value = l }; _flameStatus.value = "ESP32 par lag gaya (save hai, power band hone par bhi rahega)" }
                is FetchResult.Failure -> _flameStatus.value = "Nahi laga: ${r.message}"
            }
        }
    }

    private val _resetStatus = MutableStateFlow<String?>(null)
    val resetStatus: StateFlow<String?> = _resetStatus.asStateFlow()
    fun resetLatch() {
        viewModelScope.launch {
            _resetStatus.value = when (val r = repository.resetLatch()) {
                is FetchResult.Body -> "Reset bhej diya. Supply tabhi wapas aayegi jab saari readings safe hongi."
                is FetchResult.Failure -> "Reset nahi hua: ${r.message}"
            }
        }
    }

    private var lastContactSyncMs = 0L
    private val alertLevels = mutableMapOf<String, SystemStatus>()

    private var wantConnected = false
    private var foreground = false

    init {
        if (settings.value.autoConnect) { wantConnected = true; repository.start() }

        viewModelScope.launch { settings.collect { fleetRepository.configure(it) } }

        viewModelScope.launch {
            combine(live, fleet) { l, f ->
                buildMap<String, Triple<SystemStatus, Int?, Boolean?>> {
                    val st = l.status
                    if (l.connection == ConnectionState.LIVE && st != null) put(st.deviceId.removePrefix("STSMN-").uppercase(), Triple(st.status, st.risk, !st.relay))
                    f.devices.filter { it.link == FleetLink.LIVE && it.status != null }.forEach { putIfAbsent(it.id, Triple(it.status!!, it.risk, it.relay?.not())) }
                }
            }.collect { now ->
                now.forEach { (id, v) ->
                    val prev = alertLevels[id]
                    val level = v.first
                    if (level == prev) return@forEach
                    alertLevels[id] = level
                    when (level) {
                        SystemStatus.EMERGENCY -> {
                            val cfg = settings.value
                            val st = live.value.status
                            val localId = st?.deviceId?.removePrefix("STSMN-")?.uppercase()
                            val phone = cfg.alertPhone
                            val text = if (st != null && id == localId) AlertContact.message(st) else "STSMN ALERT $id: EMERGENCY. Risk ${v.second ?: "?"}/100."
                            notifier.clearWarning(id)
                            notifier.emergency(id, v.second, v.third)
                            if (phone.isNotEmpty() && cfg.whatsappKey.isNotEmpty()) launch { sendWhatsAppWithRetry(id, phone, text, cfg.whatsappKey) }
                        }
                        SystemStatus.WARNING -> { notifier.clearEmergency(id); notifier.warning(id, v.second) }
                        else -> notifier.clearAll(id)
                    }
                }
            }
        }

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

    fun saveAlertContact(rawPhone: String, whatsappKey: String): String? {
        val trimmed = rawPhone.trim()
        val phone = if (trimmed.isEmpty()) "" else AlertContact.normalize(trimmed)
            ?: return "Ye sahi mobile number nahi hai. Jaise: 75050 45948 ya +91 75050 45948"
        updateSettings { it.copy(alertPhone = phone, whatsappKey = whatsappKey.trim()) }
        if (phone.isEmpty()) { _contactStatus.value = "Alert number app se hata diya. ESP32 se bhi hata rahe hain..." }
        else _contactStatus.value = "App me save ho gaya: ${AlertContact.mask(phone)}. ESP32 ko bhej rahe hain..."
        syncContactToDevice(phone)
        val key = whatsappKey.trim()
        if (phone.isNotEmpty() && key.isNotEmpty()) viewModelScope.launch {
            val err = whatsApp.send(phone, "STSMN: automatic WhatsApp alerts are connected to this number.", key)
            _whatsappStatus.value = if (err == null) "WhatsApp jud gaya: ek confirmation message bhej diya." else "WhatsApp key save ho gayi, par confirmation nahi gaya: $err"
        }
        return null
    }

    private suspend fun sendWhatsAppWithRetry(id: String, phone: String, text: String, key: String) {
        repeat(6) {
            val err = whatsApp.send(phone, text, key)
            if (err == null) { _whatsappStatus.value = "WhatsApp alert apne aap bhej diya ($id)"; return }
            _whatsappStatus.value = "WhatsApp alert abhi nahi gaya: $err. Dobara koshish kar rahe hain..."
            if (alertLevels[id] != SystemStatus.EMERGENCY) return
            delay(30_000L)
        }
        _whatsappStatus.value = "WhatsApp alert nahi bhej paye (internet aur key check karo)."
    }

    private fun syncContactToDevice(phone: String) {
        lastContactSyncMs = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            _contactStatus.value = when (val r = repository.sendAlertPhone(phone)) {
                is FetchResult.Body -> if (phone.isEmpty()) "ESP32 par SMS band kar diya." else "ESP32 par SMS number save ho gaya: ${AlertContact.mask(phone)}. SMS aur WhatsApp alert isi number par jayenge."
                is FetchResult.Failure -> "App me save ho gaya. ESP32 tak pahunch nahi paye (${r.message}); app connect hote hi apne aap bhej diya jayega."
            }
        }
    }

    fun connect() { wantConnected = true; repository.start() }
    fun disconnect() { wantConnected = false; repository.stop() }

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
