package com.stsmn.monitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.stsmn.monitor.domain.AlertContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional, fully automatic WhatsApp through the free CallMeBot service (the person pastes THEIR key in Settings).
 * It needs an internet connection: the ESP32's Wi-Fi has none, so the request is sent over a network that has
 * internet (mobile data). If there is none, or the service says no, the caller shows the reason and the one-tap
 * WhatsApp button stays available.
 */
class WhatsAppSender(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Returns null when the service accepted the message, otherwise a short reason. */
    suspend fun send(phone: String, text: String, key: String): String? = withContext(Dispatchers.IO) {
        try {
            val net = cm.allNetworks.firstOrNull {
                val c = cm.getNetworkCapabilities(it)
                c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } ?: return@withContext "no internet (turn on mobile data)"
            val conn = net.openConnection(URL(AlertContact.callMeBotUrl(phone, text, key))) as HttpURLConnection
            try {
                conn.connectTimeout = 8000; conn.readTimeout = 12000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code in 200..299) null else "service answered HTTP $code"
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            "failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
