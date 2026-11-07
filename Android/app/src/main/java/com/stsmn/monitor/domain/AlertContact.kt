package com.stsmn.monitor.domain

import java.net.URLEncoder

/** The person who is alerted (SMS from the ESP32's SIM module + WhatsApp). Pure logic, unit-tested on the JVM. */
object AlertContact {
    /** "75050 45948", "+91-75050-45948", "917505045948" -> "+917505045948". null when it is not a plausible number. */
    fun normalize(input: String): String? {
        val s = input.trim()
        var plus = false
        val d = StringBuilder()
        for ((i, c) in s.withIndex()) {
            when {
                c == '+' && i == 0 -> plus = true
                c in '0'..'9' -> { if (d.length >= 18) return null; d.append(c) }
                c == ' ' || c == '-' || c == '(' || c == ')' -> Unit
                else -> return null
            }
        }
        var digits = d.toString()
        if (!plus) {
            digits = when {
                digits.length == 10 && digits[0] in '6'..'9' -> "91$digits"
                digits.length == 12 && digits.startsWith("91") -> digits
                digits.length == 11 && digits[0] == '0' -> "91" + digits.substring(1)
                else -> return null
            }
        }
        return if (digits.length in 8..15) "+$digits" else null
    }

    /** "+917505045948" -> "+91********48" (same rule as the ESP32, so the two can be compared). */
    fun mask(phone: String): String {
        if (phone.length < 6) return ""
        return buildString { phone.forEachIndexed { i, c -> append(if (i < 3 || i >= phone.length - 2) c else '*') } }
    }

    /** Plain ASCII text for the WhatsApp message. Only values the ESP32 really reported. */
    fun message(s: StsmnStatus): String = buildString {
        append("STSMN ALERT ").append(s.deviceId).append(": ").append(s.status.name)
        append(". Risk ").append(s.risk).append("/100.")
        s.smokePct?.let { if (it > 0) append(" Smoke ").append(it).append("%.") }
        s.flamePct?.let { if (it > 0) append(" Fire ").append(it).append("%.") }
        s.temperature?.let { append(" Temp ").append(String.format(java.util.Locale.US, "%.1f", it)).append("C.") }
        append(if (s.relay) " Supply is ON." else " Supply cut off automatically.")
    }

    private fun enc(t: String): String = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    /** Opens WhatsApp with the text ready; the person only has to press send. */
    fun waMeUrl(phone: String, text: String): String = "https://wa.me/${phone.removePrefix("+")}?text=${enc(text)}"

    /** Fully automatic WhatsApp through the free CallMeBot service (needs that service's own key for this number). */
    fun callMeBotUrl(phone: String, text: String, key: String): String =
        "https://api.callmebot.com/whatsapp.php?phone=${enc(phone)}&text=${enc(text)}&apikey=${enc(key)}"
}
