package com.stsmn.monitor.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.stsmn.monitor.domain.AlertContact
import com.stsmn.monitor.domain.WhatsAppReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class WhatsAppSender(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private fun candidates(): List<Network> {
        val all = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        val validated = all.filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }.map { it.first }
        val cellular = all.filter { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }.map { it.first }
        val active = listOfNotNull(cm.activeNetwork)
        return (validated + cellular + active).distinct()
    }

    suspend fun send(phone: String, text: String, key: String): String? = withContext(Dispatchers.IO) {
        val nets = candidates()
        if (nets.isEmpty()) return@withContext "no internet (turn on mobile data)"
        var last = "no internet"
        for (net in nets) {
            try {
                val conn = net.openConnection(URL(AlertContact.callMeBotUrl(phone, text, key))) as HttpURLConnection
                try {
                    conn.connectTimeout = 10000; conn.readTimeout = 20000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText().take(2000) } ?: ""
                    return@withContext WhatsAppReply.classify(code, body)
                } finally { conn.disconnect() }
            } catch (e: Exception) {
                last = "failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        last
    }
}
