package com.stsmn.monitor.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object GlobeMath {
    const val MAX_LAT = 85.0511287798
    private const val DEG = PI / 180.0

    data class Screen(val x: Double, val y: Double, val visible: Boolean)

    fun project(lat: Double, lon: Double, centerLat: Double, centerLon: Double): Screen {
        val phi = lat * DEG
        val phi0 = centerLat * DEG
        val dl = (lon - centerLon) * DEG
        val x = cos(phi) * sin(dl)
        val y = cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dl)
        val facing = sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dl)
        return Screen(x, y, facing > 0.0)
    }

    fun unproject(x: Double, y: Double, centerLat: Double, centerLon: Double): Pair<Double, Double>? {
        val r2 = x * x + y * y
        if (r2 > 1.0) return null
        val z = sqrt(1.0 - r2)
        val phi0 = centerLat * DEG
        val s = (z * sin(phi0) + y * cos(phi0)).coerceIn(-1.0, 1.0)
        val lat = Math.asin(s) / DEG
        val lon = centerLon + atan2(x, z * cos(phi0) - y * sin(phi0)) / DEG
        return lat to normLon(lon)
    }

    fun normLon(lon: Double): Double {
        var l = lon % 360.0
        if (l > 180.0) l -= 360.0
        if (l < -180.0) l += 360.0
        return l
    }

    fun mercatorRow(lat: Double, size: Int): Double {
        val s = sin(max(-MAX_LAT, min(MAX_LAT, lat)) * DEG)
        val y = 0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)
        return y * size
    }

    fun mercatorCol(lon: Double, size: Int): Double = (normLon(lon) + 180.0) / 360.0 * size

    fun dragCenter(centerLat: Double, centerLon: Double, dx: Double, dy: Double, radiusPx: Double): Pair<Double, Double> {
        val perPx = (180.0 / PI) / max(1.0, radiusPx)
        val lonScale = max(0.35, cos(centerLat * DEG))
        val lat = (centerLat + dy * perPx).coerceIn(-80.0, 80.0)
        val lon = normLon(centerLon - dx * perPx / lonScale)
        return lat to lon
    }
}
