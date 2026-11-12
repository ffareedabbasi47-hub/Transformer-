package com.stsmn.monitor.domain

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

    fun esriTileUrl(service: String, z: Int, y: Int, x: Int): String = "$ESRI$service/MapServer/tile/$z/$y/$x"

    fun esriBase(service: String): String = "$ESRI$service/MapServer/tile/"

    fun attribution(base: BaseLayer, roads: Boolean): String =
        if (roads && base != BaseLayer.STREETS) base.attribution + " · Roads © Esri, HERE, Garmin" else base.attribution
}
