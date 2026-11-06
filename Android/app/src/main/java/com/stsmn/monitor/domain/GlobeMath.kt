package com.stsmn.monitor.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure math for the native 3D Earth (orthographic view of a sphere textured with a Web-Mercator world image).
 * No Android classes: unit-tested on the JVM.
 */
object GlobeMath {
    const val MAX_LAT = 85.0511287798          // Web-Mercator limit
    private const val DEG = PI / 180.0

    /** Screen position on the unit disk (x right, y up) and whether the point faces the viewer. */
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

    /** Inverse of [project] for a point on the visible disk (x*x + y*y <= 1). Returns lat/lon in degrees. */
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

    /** Pixel row in a square Web-Mercator world image of `size` pixels for a latitude (clamped). */
    fun mercatorRow(lat: Double, size: Int): Double {
        val s = sin(max(-MAX_LAT, min(MAX_LAT, lat)) * DEG)
        val y = 0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * PI)
        return y * size
    }

    fun mercatorCol(lon: Double, size: Int): Double = (normLon(lon) + 180.0) / 360.0 * size

    /** Dragging by (dx, dy) screen pixels on a globe of the given radius in pixels: the surface follows the finger. */
    fun dragCenter(centerLat: Double, centerLon: Double, dx: Double, dy: Double, radiusPx: Double): Pair<Double, Double> {
        val perPx = (180.0 / PI) / max(1.0, radiusPx)
        val lonScale = max(0.35, cos(centerLat * DEG))
        val lat = (centerLat + dy * perPx).coerceIn(-80.0, 80.0)
        val lon = normLon(centerLon - dx * perPx / lonScale)
        return lat to lon
    }
}
