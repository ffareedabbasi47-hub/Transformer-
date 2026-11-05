package com.stsmn.monitor.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pre-flight check: before a map is shown, find out which picture servers this phone can really reach
 * (internet present, server not blocked). The choice rules are pure and unit-tested; only [run] touches the network.
 */
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

    /** Both servers are asked at the same time; the whole check takes at most about 3 seconds. Never throws. */
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
