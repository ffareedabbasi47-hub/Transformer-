package com.stsmn.monitor.domain

/**
 * Validation for the realtime-cloud settings. Pure Kotlin so it can be unit-tested.
 * Rule of thumb: internet traffic must be encrypted (wss://). Plain ws:// is accepted ONLY for private
 * LAN addresses (testing the server on your own Wi-Fi), never for a public host.
 */
object CloudRules {
    private val WSS = Regex("""^wss://[A-Za-z0-9]([A-Za-z0-9.-]{0,120}[A-Za-z0-9])?(:\d{1,5})?(/[A-Za-z0-9._~/-]{0,100})?$""")
    private val WS_PRIVATE = Regex("""^ws://(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(:\d{1,5})?(/[A-Za-z0-9._~/-]{0,100})?$""")
    private val DEVICE_ID = Regex("""^[A-Z0-9][A-Z0-9-]{1,23}$""")

    fun isValidServerUrl(url: String): Boolean {
        if (url.length > 200) return false
        if (WSS.matches(url)) return true
        val m = WS_PRIVATE.matchEntire(url) ?: return false
        val o = m.groupValues.subList(1, 5).map { it.toInt() }
        if (o.any { it > 255 }) return false
        return o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31)
    }

    fun isValidDeviceId(id: String): Boolean = DEVICE_ID.matches(id)

    /** Keys/tokens: printable ASCII, no spaces, max 128 chars. */
    fun cleanSecret(s: String): String = s.filter { it.code in 33..126 }.take(128)
}
