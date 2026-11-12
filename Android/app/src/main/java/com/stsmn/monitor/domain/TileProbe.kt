package com.stsmn.monitor.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.URL

object TileProbe {
    enum class Choice { SATELLITE, STREETS, NONE }

    data class Result(val esri: Boolean, val osm: Boolean) {
        val any: Boolean get() = esri || osm
        fun choice(): Choice = when {
            esri -> Choice.SATELLITE
            osm -> Choice.STREETS
            else -> Choice.NONE
        }
    }

    private const val ESRI_URL = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/2/1/2"
    private const val OSM_URL = "https://tile.openstreetmap.org/2/2/1.png"
    private const val TIMEOUT_MS = 3000

    suspend fun run(userAgent: String): Result = coroutineScope {
        val esri = async(Dispatchers.IO) { reachable(ESRI_URL, userAgent) }
        val osm = async(Dispatchers.IO) { reachable(OSM_URL, userAgent) }
        Result(esri.await(), osm.await())
    }

    private fun reachable(url: String, userAgent: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
            }
            c.responseCode == 200 && c.inputStream.use { it.readBytes().size > 100 }
        } catch (_: Exception) {
            false
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }
}
