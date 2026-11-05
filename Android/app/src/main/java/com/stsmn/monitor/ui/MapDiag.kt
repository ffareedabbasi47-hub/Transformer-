package com.stsmn.monitor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One-line map status shown at the bottom of the Map tab so a screenshot tells exactly what this phone is doing. */
object MapDiag {
    var engine by mutableStateOf("3D")
    var probe by mutableStateOf("servers: checking…")
    var web by mutableStateOf("3D page: starting")
    var env by mutableStateOf("")

    fun probeText(r: com.stsmn.monitor.domain.TileProbe.Result) =
        "servers: satellite ${if (r.esri) "OK" else "NO"}, streets ${if (r.osm) "OK" else "NO"}"
}
