package com.stsmn.monitor

import com.stsmn.monitor.domain.BaseLayer
import com.stsmn.monitor.domain.Clustering
import com.stsmn.monitor.domain.FleetFilter
import com.stsmn.monitor.domain.MapDevice
import com.stsmn.monitor.domain.MapLayers
import com.stsmn.monitor.domain.MarkerState
import com.stsmn.monitor.domain.StateFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFeaturesTest {
    private fun dev(id: String, state: MarkerState, name: String = "STSMN-$id") = MapDevice(
        id, name, 26.85, 80.94, false, state, null, null, null, null, null, null, null, null, null, null, 1000L, false,
    )

    private val fleet = listOf(
        dev("TR-001", MarkerState.SAFE), dev("TR-002", MarkerState.WARNING),
        dev("TR-003", MarkerState.EMERGENCY), dev("TR-004", MarkerState.OFFLINE), dev("TR-005", MarkerState.STALE),
    )

    // ---- layers ----
    @Test fun tileUrlsAreEsriZYX() {
        assertEquals(
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/3/2/1",
            MapLayers.esriTileUrl(MapLayers.IMAGERY_SERVICE, 3, 2, 1),
        )
        assertTrue(MapLayers.esriTileUrl(MapLayers.ROADS_SERVICE, 5, 10, 20).endsWith("Reference/World_Transportation/MapServer/tile/5/10/20"))
        assertTrue(MapLayers.esriBase(MapLayers.TERRAIN_SERVICE).endsWith("World_Topo_Map/MapServer/tile/"))
    }

    @Test fun everyLayerHasAttribution() {
        for (b in BaseLayer.values()) assertTrue(MapLayers.attribution(b, false).isNotBlank())
        assertTrue(MapLayers.attribution(BaseLayer.SATELLITE, true).contains("Roads"))
        assertFalse(MapLayers.attribution(BaseLayer.STREETS, true).contains("Roads"))
    }

    // ---- search ----
    @Test fun searchFindsByAnyWritingOfTheId() {
        for (q in listOf("tr-001", "TR001", "stsmn-tr-001", " 001 ")) {
            val r = FleetFilter.apply(fleet, q, emptySet())
            assertEquals(q, listOf("TR-001"), r.map { it.id })
        }
    }

    @Test fun emptyQueryAndNoFilterReturnsEveryone() = assertEquals(5, FleetFilter.apply(fleet, "", emptySet()).size)

    @Test fun unknownQueryReturnsNothing() = assertTrue(FleetFilter.apply(fleet, "xyz-999", emptySet()).isEmpty())

    // ---- filters ----
    @Test fun offlineChipIncludesStale() {
        val r = FleetFilter.apply(fleet, "", setOf(StateFilter.OFFLINE))
        assertEquals(setOf("TR-004", "TR-005"), r.map { it.id }.toSet())
    }

    @Test fun filtersCombineAndWorkWithSearch() {
        assertEquals(setOf("TR-002", "TR-003"), FleetFilter.apply(fleet, "", setOf(StateFilter.WARNING, StateFilter.EMERGENCY)).map { it.id }.toSet())
        assertTrue(FleetFilter.apply(fleet, "tr-001", setOf(StateFilter.EMERGENCY)).isEmpty())
    }

    @Test fun countsMatchTheFleet() {
        val c = FleetFilter.counts(fleet)
        assertEquals(1, c[StateFilter.SAFE]); assertEquals(1, c[StateFilter.WARNING])
        assertEquals(1, c[StateFilter.EMERGENCY]); assertEquals(2, c[StateFilter.OFFLINE])
    }

    // ---- clustering ----
    private fun item(id: String, lat: Double, lng: Double, s: MarkerState = MarkerState.SAFE) = Clustering.Item(id, lat, lng, s)

    @Test fun singleMarkerKeepsExactCoordinates() {
        val c = Clustering.cluster(listOf(item("A", 26.84670123, 80.94620456)), 10.0, 256.0 * 2.75, 60.0)
        assertEquals(1, c.size)
        assertEquals(26.84670123, c[0].lat, 0.0)
        assertEquals(80.94620456, c[0].lng, 0.0)
    }

    @Test fun nearbyMergeAtLowZoomAndSplitAtHighZoom() {
        val pts = listOf(item("A", 26.8467, 80.9462), item("B", 26.8470, 80.9466))      // about 50 m apart
        assertEquals(1, Clustering.cluster(pts, 10.0, 256.0 * 2.75, 60.0).size)
        assertEquals(2, Clustering.cluster(pts, 19.0, 256.0 * 2.75, 60.0).size)
    }

    @Test fun farApartNeverMerge() {
        val pts = listOf(item("A", 26.85, 80.95), item("B", 28.61, 77.21))               // Lucknow and Delhi
        assertEquals(2, Clustering.cluster(pts, 6.0, 256.0 * 2.75, 60.0).size)
    }

    @Test fun clusterCentreLiesBetweenMembersAndEveryMarkerIsKept() {
        val pts = listOf(item("A", 26.80, 80.90), item("B", 26.90, 81.00), item("C", 26.85, 80.95))
        val c = Clustering.cluster(pts, 5.0, 256.0 * 2.75, 400.0)
        assertEquals(1, c.size)
        assertEquals(3, c[0].size)
        assertTrue(c[0].lat in 26.80..26.90)
        assertTrue(c[0].lng in 80.90..81.00)
        assertEquals(26.85, c[0].lat, 0.01)
        assertEquals(80.95, c[0].lng, 0.01)
    }

    @Test fun clusterShowsMostSeriousState() {
        val pts = listOf(item("A", 26.8467, 80.9462, MarkerState.SAFE), item("B", 26.8468, 80.9463, MarkerState.EMERGENCY), item("C", 26.8469, 80.9464, MarkerState.WARNING))
        val c = Clustering.cluster(pts, 8.0, 256.0 * 2.75, 60.0)
        assertEquals(1, c.size)
        assertEquals(MarkerState.EMERGENCY, c[0].worst)
    }

    @Test fun resultIsDeterministicAndIgnoresInputOrder() {
        val a = listOf(item("A", 26.8467, 80.9462), item("B", 26.8468, 80.9463), item("C", 20.0, 70.0))
        val r1 = Clustering.cluster(a, 8.0, 700.0, 60.0).map { it.members.map { m -> m.id } }
        val r2 = Clustering.cluster(a.reversed(), 8.0, 700.0, 60.0).map { it.members.map { m -> m.id } }
        assertEquals(r1, r2)
    }
}
