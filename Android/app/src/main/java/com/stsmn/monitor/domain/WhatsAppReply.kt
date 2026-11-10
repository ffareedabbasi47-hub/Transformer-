package com.stsmn.monitor.domain

/**
 * Reads the answer of the CallMeBot service. The service often answers HTTP 200 even when it did NOT send anything
 * (wrong key, number not registered, rate limit), so the HTTP code alone must never count as success.
 * Pure logic, unit-tested on the JVM.
 */
object WhatsAppReply {
    /** null = the service accepted the message; otherwise a short reason for the person. */
    fun classify(httpCode: Int, body: String): String? {
        val text = body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        val low = text.lowercase()
        val accepted = ("queued" in low || "message sent" in low) &&
            "error" !in low && "invalid" !in low && "not registered" !in low
        if (httpCode in 200..299 && accepted) return null
        return when {
            "apikey" in low && ("invalid" in low || "wrong" in low) -> "the WhatsApp key is wrong (get it again from CallMeBot)"
            "not registered" in low || "not activated" in low || "authorized" in low -> "this number is not activated at CallMeBot (send the activation message first)"
            "wait" in low || "too many" in low || "limit" in low || httpCode == 429 -> "CallMeBot rate limit - wait a minute"
            httpCode in 500..599 -> "CallMeBot service is down (HTTP $httpCode)"
            text.isNotEmpty() -> text.take(120)
            else -> "service answered HTTP $httpCode with no message"
        }
    }
}
