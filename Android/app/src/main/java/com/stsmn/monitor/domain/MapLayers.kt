package com.stsmn.monitor.domain

/** Base pictures the flat map can show. Every layer is a real Esri / OpenStreetMap tile service: nothing is faked. */
enum class BaseLayer(val label: String, val attribution: String) {
    SATELLITE("Satellite", "Imagery © Esri, Maxar, Earthstar Geographics"),
    TERRAIN("Terrain", "Topographic map © Esri, HERE, Garmin, USGS, NGA, OpenStreetMap contributors"),
    STREETS("Streets", "© OpenStreetMap contributors"),
}

object MapLayers {
    private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/"
    const val IMAGERY_SERVICE = "World_Imagery"
    const val TERRAIN_SERVICE = "World_Topo_Map"
    const val ROADS_SERVICE = "Reference/World_Transportation"

    /** Esri tile URL: service/MapServer/tile/{z}/{y}/{x}. */
    fun esriTileUrl(service: String, z: Int, y: Int, x: Int): String = "$ESRI$service/MapServer/tile/$z/$y/$x"

    fun esriBase(service: String): String = "$ESRI$service/MapServer/tile/"

    /** Credit line for the current combination (required by the providers' terms). */
    fun attribution(base: BaseLayer, roads: Boolean): String =
        if (roads && base != BaseLayer.STREETS) base.attribution + " · Roads © Esri, HERE, Garmin" else base.attribution
}
