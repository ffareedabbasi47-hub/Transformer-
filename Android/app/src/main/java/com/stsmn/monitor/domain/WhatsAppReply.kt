package com.stsmn.monitor.domain

object WhatsAppReply {

    fun classify(httpCode: Int, body: String): String? {
        val text = body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        val low = text.lowercase()
        val accepted = ("queued" in low || "message sent" in low) &&
            "error" !in low && "invalid" !in low && "not registered" !in low
        if (httpCode in 200..299 && accepted) return null
        return when {
            "apikey" in low && ("invalid" in low || "wrong" in low) -> "WhatsApp key galat hai (CallMeBot se dobara lo)"
            "not registered" in low || "not activated" in low || "authorized" in low -> "ye number CallMeBot par activate nahi hai (pehle activation message bhejo)"
            "wait" in low || "too many" in low || "limit" in low || httpCode == 429 -> "CallMeBot ki limit lag gayi - ek minute ruko"
            httpCode in 500..599 -> "CallMeBot service band hai (HTTP $httpCode)"
            text.isNotEmpty() -> text.take(120)
            else -> "service ne HTTP $httpCode diya, par koi message nahi"
        }
    }
}
