package com.stsmn.monitor.domain

import java.net.URLEncoder

object AlertContact {

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

    fun mask(phone: String): String {
        if (phone.length < 6) return ""
        return buildString { phone.forEachIndexed { i, c -> append(if (i < 3 || i >= phone.length - 2) c else '*') } }
    }

    fun message(s: StsmnStatus): String = buildString {
        append("STSMN ALERT ").append(s.deviceId).append(": ").append(s.status.name)
        append(". Risk ").append(s.risk).append("/100.")
        s.smokePct?.let { if (it > 0) append(" Smoke ").append(it).append("%.") }
        s.flamePct?.let { if (it > 0) append(" Fire ").append(it).append("%.") }
        s.temperature?.let { append(" Temp ").append(String.format(java.util.Locale.US, "%.1f", it)).append("C.") }
        append(if (s.relay) " Supply is ON." else " Supply cut off automatically.")
    }

    private fun enc(t: String): String = URLEncoder.encode(t, "UTF-8").replace("+", "%20")

    fun waMeUrl(phone: String, text: String): String = "https://wa.me/${phone.removePrefix("+")}?text=${enc(text)}"

    fun callMeBotUrl(phone: String, text: String, key: String): String =
        "https://api.callmebot.com/whatsapp.php?phone=${enc(phone)}&text=${enc(text)}&apikey=${enc(key)}"
}
