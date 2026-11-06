package com.stsmn.monitor.domain

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tan

/** Groups markers that would overlap on screen. Distances are measured in Web-Mercator pixels, like the map itself. */
object Clustering {
    data class Item(val id: String, val lat: Double, val lng: Double, val state: MarkerState)

    class Cluster(val members: List<Item>, val lat: Double, val lng: Double) {
        val size: Int get() = members.size
        /** The most serious state inside the group, so one red transformer is never hidden by green ones. */
        val worst: MarkerState get() = members.maxByOrNull { severity(it.state) }!!.state
    }

    private fun severity(s: MarkerState) = when (s) {
        MarkerState.EMERGENCY -> 4
        MarkerState.WARNING -> 3
        MarkerState.OFFLINE -> 2
        MarkerState.STALE -> 1
        MarkerState.SAFE -> 0
    }

    private fun worldX(lng: Double, w: Double) = (lng + 180.0) / 360.0 * w
    private fun worldY(lat: Double, w: Double): Double {
        val l = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        return (0.5 - ln(tan(PI / 4 + l / 2)) / (2 * PI)) * w
    }
    private fun lngOf(x: Double, w: Double) = x / w * 360.0 - 180.0
    private fun latOf(y: Double, w: Double) = (2 * atan(exp((0.5 - y / w) * 2 * PI)) - PI / 2) * 180.0 / PI

    /**
     * @param zoom map zoom level
     * @param worldPx0 size in pixels of the whole world at zoom 0 (256 x screen density)
     * @param radiusPx markers closer than this on screen are merged
     * A single marker is returned at its exact coordinates; a group is placed at the mean position of its members.
     */
    fun cluster(items: List<Item>, zoom: Double, worldPx0: Double, radiusPx: Double): List<Cluster> {
        val w = worldPx0 * Math.pow(2.0, zoom)
        val sorted = items.sortedBy { it.id }
        val xs = sorted.map { worldX(it.lng, w) }
        val ys = sorted.map { worldY(it.lat, w) }
        val used = BooleanArray(sorted.size)
        val out = ArrayList<Cluster>()
        for (i in sorted.indices) {
            if (used[i]) continue
            used[i] = true
            val group = arrayListOf(i)
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val dx = xs[j] - xs[i]; val dy = ys[j] - ys[i]
                if (sqrt(dx * dx + dy * dy) <= radiusPx) { used[j] = true; group.add(j) }
            }
            if (group.size == 1) out.add(Cluster(listOf(sorted[i]), sorted[i].lat, sorted[i].lng))
            else {
                val cx = group.sumOf { xs[it] } / group.size
                val cy = group.sumOf { ys[it] } / group.size
                out.add(Cluster(group.map { sorted[it] }, latOf(cy, w), lngOf(cx, w)))
            }
        }
        return out
    }
}
