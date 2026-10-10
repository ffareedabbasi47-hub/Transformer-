package com.stsmn.monitor.data

import com.stsmn.monitor.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

sealed interface FetchResult {
    data class Body(val text: String) : FetchResult
    data class Failure(val message: String) : FetchResult
}

/** Minimal HTTP client (built-in HttpURLConnection, no extra dependency). Every call has timeouts. */
class EspClient(private val binder: WifiNetworkBinder) {

    suspend fun fetchStatus(host: String): FetchResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http", host, AppConfig.API_PORT, AppConfig.STATUS_PATH)
            conn = binder.openConnection(url) as? HttpURLConnection
                ?: return@withContext FetchResult.Failure("Unsupported connection type")
            conn.connectTimeout = AppConfig.CONNECT_TIMEOUT_MS
            conn.readTimeout = AppConfig.READ_TIMEOUT_MS
            conn.useCaches = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return@withContext FetchResult.Failure("HTTP $code")
            val text = conn.inputStream.use { readLimited(it) }
                ?: return@withContext FetchResult.Failure("Response too large")
            FetchResult.Body(text)
        } catch (e: SocketTimeoutException) {
            FetchResult.Failure("Timeout")
        } catch (e: IOException) {
            FetchResult.Failure("Network error: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            FetchResult.Failure("Network permission denied")
        } catch (e: IllegalArgumentException) {
            FetchResult.Failure("Invalid device address")
        } finally {
            conn?.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(512)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > AppConfig.MAX_RESPONSE_BYTES) return null
            out.write(chunk, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
