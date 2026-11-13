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

class EspClient(private val binder: WifiNetworkBinder) {

    suspend fun fetchStatus(host: String): FetchResult = fetch(host, AppConfig.STATUS_PATH)

    suspend fun fetchHealth(host: String): FetchResult = fetch(host, AppConfig.HEALTH_PATH)

    suspend fun fetchFlame(host: String): FetchResult = fetch(host, AppConfig.FLAME_PATH)

    suspend fun postFlame(host: String, form: Map<String, String>): FetchResult = postForm(host, AppConfig.FLAME_PATH, form)

    suspend fun postReset(host: String, token: String): FetchResult = postForm(host, AppConfig.RESET_PATH, mapOf("token" to token))

    private suspend fun postForm(host: String, path: String, form: Map<String, String>): FetchResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http", host, AppConfig.API_PORT, path)
            conn = binder.openConnection(url) as? HttpURLConnection
                ?: return@withContext FetchResult.Failure("Ye connection type chalta nahi")
            conn.connectTimeout = AppConfig.CONNECT_TIMEOUT_MS
            conn.readTimeout = AppConfig.READ_TIMEOUT_MS
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Connection", "close")
            val body = form.entries.joinToString("&") { it.key + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code == 401) return@withContext FetchResult.Failure("ESP32 ka password galat hai (Settings > ESP32 password)")
            if (code == 429) return@withContext FetchResult.Failure("Bahut baar galat password daala: 30 second ruko")
            if (code == 409) return@withContext FetchResult.Failure("Abhi allowed nahi hai (thoda ruko / ye sirf SAFE me hota hai)")
            if (code != HttpURLConnection.HTTP_OK) return@withContext FetchResult.Failure("ESP32 ne HTTP $code diya (ESP32 ka sketch update karo)")
            val text = conn.inputStream.use { readLimited(it) } ?: return@withContext FetchResult.Failure("Jawab bahut bada hai")
            FetchResult.Body(text)
        } catch (e: SocketTimeoutException) {
            FetchResult.Failure("Timeout")
        } catch (e: IOException) {
            FetchResult.Failure("Network ki dikkat: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            FetchResult.Failure("Network ki permission nahi mili")
        } catch (e: IllegalArgumentException) {
            FetchResult.Failure("Device ka address galat hai")
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun fetch(host: String, path: String): FetchResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http", host, AppConfig.API_PORT, path)
            conn = binder.openConnection(url) as? HttpURLConnection
                ?: return@withContext FetchResult.Failure("Ye connection type chalta nahi")
            conn.connectTimeout = AppConfig.CONNECT_TIMEOUT_MS
            conn.readTimeout = AppConfig.READ_TIMEOUT_MS
            conn.useCaches = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return@withContext FetchResult.Failure("HTTP $code")
            val text = conn.inputStream.use { readLimited(it) }
                ?: return@withContext FetchResult.Failure("Jawab bahut bada hai")
            FetchResult.Body(text)
        } catch (e: SocketTimeoutException) {
            FetchResult.Failure("Timeout")
        } catch (e: IOException) {
            FetchResult.Failure("Network ki dikkat: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            FetchResult.Failure("Network ki permission nahi mili")
        } catch (e: IllegalArgumentException) {
            FetchResult.Failure("Device ka address galat hai")
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun postContact(host: String, phone: String, token: String): FetchResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("http", host, AppConfig.API_PORT, AppConfig.CONTACT_PATH)
            conn = binder.openConnection(url) as? HttpURLConnection
                ?: return@withContext FetchResult.Failure("Ye connection type chalta nahi")
            conn.connectTimeout = AppConfig.CONNECT_TIMEOUT_MS
            conn.readTimeout = AppConfig.READ_TIMEOUT_MS
            conn.useCaches = false
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Connection", "close")
            val body = ("phone=" + java.net.URLEncoder.encode(phone, "UTF-8") + "&token=" + java.net.URLEncoder.encode(token, "UTF-8")).toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return@withContext FetchResult.Failure("ESP32 ne HTTP $code diya")
            val text = conn.inputStream.use { readLimited(it) } ?: return@withContext FetchResult.Failure("Jawab bahut bada hai")
            FetchResult.Body(text)
        } catch (e: SocketTimeoutException) {
            FetchResult.Failure("Timeout")
        } catch (e: IOException) {
            FetchResult.Failure("Network ki dikkat: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            FetchResult.Failure("Network ki permission nahi mili")
        } catch (e: IllegalArgumentException) {
            FetchResult.Failure("Device ka address galat hai")
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
