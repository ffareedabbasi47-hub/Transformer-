package com.stsmn.monitor.data

import android.content.Context
import com.stsmn.monitor.config.AppConfig
import com.stsmn.monitor.domain.CloudRules
import com.stsmn.monitor.domain.LocationSource
import com.stsmn.monitor.domain.SiteLocationRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val deviceHost: String = AppConfig.DEFAULT_DEVICE_HOST,
    val deviceName: String = "Demo Transformer",
    val pollIntervalMs: Long = AppConfig.DEFAULT_POLL_INTERVAL_MS,
    val staleTimeoutMs: Long = AppConfig.DEFAULT_STALE_TIMEOUT_MS,
    val autoConnect: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val keepScreenOn: Boolean = true,
    // Site location of the ONE transformer (fixed coordinates, not the phone's GPS). null = not configured.
    val siteLat: Double? = AppConfig.DEMO_LAT,
    val siteLng: Double? = AppConfig.DEMO_LNG,
    val siteSource: LocationSource = LocationSource.DEMO,
    // Realtime cloud link (optional). Empty/disabled = the app works exactly as before (local ESP32 only).
    val cloudEnabled: Boolean = false,
    val serverUrl: String = "",
    val viewerKey: String = "",       // lets this phone WATCH every transformer
    val cloudDeviceId: String = "",   // this phone's transformer ID on the server, e.g. TR-001
    val deviceToken: String = "",     // lets this phone PUBLISH only that one transformer
    // Local profile shown in the User tab (no login, nothing leaves the phone).
    val profileName: String = "Fardeen",
    val profileRole: String = "Class 9 · Project owner",
    val profileSchool: String = "Government High School Ajrara",
)

/** Settings in SharedPreferences, validated on every change. */
class SettingsStore(context: Context, private val codec: SecretCodec) {
    private val prefs = context.applicationContext.getSharedPreferences("stsmn_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val sanitized = sanitize(transform(_settings.value), _settings.value)
        _settings.value = sanitized
        save(sanitized)
    }

    private fun sanitize(n: AppSettings, old: AppSettings): AppSettings = n.copy(
        deviceHost = if (isValidHost(n.deviceHost)) n.deviceHost.trim() else old.deviceHost,
        deviceName = n.deviceName.trim().take(AppConfig.MAX_TEXT_LENGTH),
        pollIntervalMs = if (n.pollIntervalMs in AppConfig.POLL_INTERVAL_CHOICES_MS) n.pollIntervalMs else AppConfig.DEFAULT_POLL_INTERVAL_MS,
        staleTimeoutMs = if (n.staleTimeoutMs in AppConfig.STALE_TIMEOUT_CHOICES_MS) n.staleTimeoutMs else AppConfig.DEFAULT_STALE_TIMEOUT_MS,
        siteLat = sanitizeSite(n, old).first,
        siteLng = sanitizeSite(n, old).second,
        serverUrl = n.serverUrl.trim().let { if (it.isEmpty() || CloudRules.isValidServerUrl(it)) it else old.serverUrl },
        viewerKey = CloudRules.cleanSecret(n.viewerKey),
        cloudDeviceId = n.cloudDeviceId.trim().uppercase().let { if (it.isEmpty() || CloudRules.isValidDeviceId(it)) it else old.cloudDeviceId },
        deviceToken = CloudRules.cleanSecret(n.deviceToken),
        profileName = n.profileName.trim().take(AppConfig.MAX_TEXT_LENGTH),
        profileRole = n.profileRole.trim().take(AppConfig.MAX_TEXT_LENGTH),
        profileSchool = n.profileSchool.trim().take(AppConfig.MAX_TEXT_LENGTH),
    )

    /** Both null = cleared on purpose; otherwise both must be valid, or the previous pair is kept. */
    private fun sanitizeSite(n: AppSettings, old: AppSettings): Pair<Double?, Double?> {
        val lat = n.siteLat
        val lng = n.siteLng
        if (lat == null && lng == null) return Pair(null, null)
        if (lat != null && lng != null && SiteLocationRules.isValid(lat, lng)) return Pair(lat, lng)
        return Pair(old.siteLat, old.siteLng)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return sanitize(
            AppSettings(
                deviceHost = prefs.getString("host", d.deviceHost) ?: d.deviceHost,
                deviceName = prefs.getString("deviceName", d.deviceName) ?: d.deviceName,
                pollIntervalMs = prefs.getLong("pollMs", d.pollIntervalMs),
                staleTimeoutMs = prefs.getLong("staleMs", d.staleTimeoutMs),
                autoConnect = prefs.getBoolean("autoConnect", d.autoConnect),
                themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.themeMode.name) ?: "") }.getOrDefault(d.themeMode),
                keepScreenOn = prefs.getBoolean("keepOn", d.keepScreenOn),
                siteLat = if (prefs.getBoolean("siteSet", true)) prefs.getString("siteLat", null)?.toDoubleOrNull() ?: d.siteLat else null,
                siteLng = if (prefs.getBoolean("siteSet", true)) prefs.getString("siteLng", null)?.toDoubleOrNull() ?: d.siteLng else null,
                siteSource = runCatching { LocationSource.valueOf(prefs.getString("siteSource", d.siteSource.name) ?: "") }.getOrDefault(d.siteSource),
                cloudEnabled = prefs.getBoolean("cloudOn", d.cloudEnabled),
                serverUrl = prefs.getString("srvUrl", "") ?: "",
                viewerKey = readSecret("viewerKey"),
                cloudDeviceId = prefs.getString("cloudId", "") ?: "",
                deviceToken = readSecret("devToken"),
                profileName = prefs.getString("pName", d.profileName) ?: d.profileName,
                profileRole = prefs.getString("pRole", d.profileRole) ?: d.profileRole,
                profileSchool = prefs.getString("pSchool", d.profileSchool) ?: d.profileSchool,
            ),
            d,
        )
    }

    /** Secrets are stored encrypted under "<name>_enc". An old plaintext value is migrated once and then erased. */
    private fun readSecret(name: String): String {
        prefs.getString(name + "_enc", null)?.let { enc -> return codec.decrypt(enc) ?: "" }
        val legacy = prefs.getString(name, "") ?: ""
        if (legacy.isNotEmpty()) {
            val enc = codec.encrypt(legacy)
            val editor = prefs.edit()
            editor.remove(name)
            if (enc != null) editor.putString(name + "_enc", enc)
            editor.apply()
        }
        return legacy
    }

    private fun writeSecret(e: android.content.SharedPreferences.Editor, name: String, value: String) {
        e.remove(name) // never keep a plaintext copy
        val enc = if (value.isEmpty()) "" else codec.encrypt(value)
        if (enc == null) e.remove(name + "_enc") else e.putString(name + "_enc", enc)
    }

    private fun save(s: AppSettings) {
        val editor = prefs.edit()
        editor
            .putString("host", s.deviceHost).putString("deviceName", s.deviceName)
            .putLong("pollMs", s.pollIntervalMs).putLong("staleMs", s.staleTimeoutMs)
            .putBoolean("autoConnect", s.autoConnect).putString("theme", s.themeMode.name)
            .putBoolean("keepOn", s.keepScreenOn)
            .putBoolean("siteSet", s.siteLat != null && s.siteLng != null)
            .putString("siteLat", s.siteLat?.toString() ?: "").putString("siteLng", s.siteLng?.toString() ?: "")
            .putString("siteSource", s.siteSource.name)
            .putBoolean("cloudOn", s.cloudEnabled).putString("srvUrl", s.serverUrl)
            .putString("cloudId", s.cloudDeviceId)
            .putString("pName", s.profileName).putString("pRole", s.profileRole).putString("pSchool", s.profileSchool)
        writeSecret(editor, "viewerKey", s.viewerKey)
        writeSecret(editor, "devToken", s.deviceToken)
        editor.apply()
    }

    companion object {
        private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

        /**
         * Only PRIVATE IPv4 addresses (192.168.x.x, 10.x.x.x, 172.16-31.x.x) are accepted: the ESP32 lives on a
         * local network, so the app can never be pointed at a public/internet host by mistake.
         */
        fun isValidHost(host: String): Boolean {
            val m = IPV4_REGEX.matchEntire(host.trim()) ?: return false
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31)
        }
    }
}
