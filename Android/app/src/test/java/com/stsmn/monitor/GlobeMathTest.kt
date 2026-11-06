package com.stsmn.monitor

import com.stsmn.monitor.domain.GlobeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeMathTest {
    @Test fun centreProjectsToMiddleAndFaces() {
        val s = GlobeMath.project(22.5, 78.9, 22.5, 78.9)
        assertEquals(0.0, s.x, 1e-9); assertEquals(0.0, s.y, 1e-9); assertTrue(s.visible)
    }
    @Test fun antipodeIsHidden() {
        assertFalse(GlobeMath.project(-22.5, 78.9 - 180.0, 22.5, 78.9).visible)
    }
    @Test fun projectUnprojectRoundTrip() {
        for ((lat, lon) in listOf(26.85 to 80.9, 10.0 to 70.0, 35.0 to 95.0, 0.0 to 78.9)) {
            val s = GlobeMath.project(lat, lon, 22.5, 78.9)
            assertTrue(s.visible)
            val back = GlobeMath.unproject(s.x, s.y, 22.5, 78.9)
            assertNotNull(back)
            assertEquals(lat, back!!.first, 1e-6); assertEquals(lon, back.second, 1e-6)
        }
    }
    @Test fun outsideDiskHasNoSurface() = assertNull(GlobeMath.unproject(0.9, 0.9, 0.0, 0.0))
    @Test fun mercatorKnownPoints() {
        assertEquals(1024.0, GlobeMath.mercatorRow(0.0, 2048), 1e-6)
        assertEquals(1024.0, GlobeMath.mercatorCol(0.0, 2048), 1e-6)
        assertEquals(0.0, GlobeMath.mercatorCol(-180.0, 2048), 1e-6)
        assertTrue(GlobeMath.mercatorRow(89.9, 2048) in 0.0..2.0)            // clamped, never negative or NaN
        assertTrue(GlobeMath.mercatorRow(-89.9, 2048) in 2046.0..2048.0)
    }
    @Test fun dragMovesSurfaceWithFinger() {
        val (lat, lon) = GlobeMath.dragCenter(0.0, 0.0, dx = 100.0, dy = 0.0, radiusPx = 300.0)
        assertTrue(lon < 0.0); assertEquals(0.0, lat, 1e-9)               // drag right -> see more of the west
        val (lat2, _) = GlobeMath.dragCenter(79.5, 0.0, 0.0, 500.0, 300.0)
        assertTrue(lat2 <= 80.0)                                           // latitude is clamped
    }
    @Test fun lonWrapsAroundDateline() {
        assertEquals(-170.0, GlobeMath.normLon(190.0), 1e-9)
        assertEquals(170.0, GlobeMath.normLon(-190.0), 1e-9)
    }
}
