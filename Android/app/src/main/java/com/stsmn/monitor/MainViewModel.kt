package com.stsmn.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.data.AppSettings
import com.stsmn.monitor.data.EspClient
import com.stsmn.monitor.data.EventStore
import com.stsmn.monitor.data.KeystoreCodec
import com.stsmn.monitor.data.SettingsStore
import com.stsmn.monitor.data.StsmnRepository
import com.stsmn.monitor.data.WifiNetworkBinder
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
    private val repository = StsmnRepository(EspClient(binder), binder, eventStore, settingsStore.settings)

    val settings = settingsStore.settings
    val live = repository.snapshot
    val events = eventStore.events
    private val fleetRepository = FleetRepository()
    val fleet = fleetRepository.state
    private var lastPublishedSeq = -1L
    private val notifier = AlertNotifier(app)
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
                now.filterKeys { it !in emergencyIds }.forEach { (id, v) -> notifier.emergency(id, v.first, v.second) }
                emergencyIds = now.keys
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
