package com.stsmn.monitor

import com.stsmn.monitor.domain.TileProbe
import com.stsmn.monitor.domain.TileProbe.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileProbeTest {
    @Test fun satelliteWinsWhenReachable() {
        assertEquals(Choice.SATELLITE, TileProbe.Result(esri = true, osm = true).choice())
        assertEquals(Choice.SATELLITE, TileProbe.Result(esri = true, osm = false).choice())
    }
    @Test fun streetsWhenOnlyOsmReachable() = assertEquals(Choice.STREETS, TileProbe.Result(esri = false, osm = true).choice())
    @Test fun noneWhenNothingReachable() {
        val r = TileProbe.Result(esri = false, osm = false)
        assertEquals(Choice.NONE, r.choice()); assertFalse(r.any)
    }
    @Test fun anyWhenOneReachable() = assertTrue(TileProbe.Result(esri = false, osm = true).any)
}
