package com.stsmn.monitor

import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.HealthParser
import com.stsmn.monitor.domain.LiveGps
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.MapDevices
import com.stsmn.monitor.domain.StsmnStatus
import com.stsmn.monitor.domain.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGpsTest {
    private fun pkt() = StsmnStatus("TR-001", "1.0.0", 1000, 30.0, 12.0, 1.0, false, false, 80, 10, SystemStatus.SAFE, true, false, 1)
    private fun live(gps: LiveGps?, at: Long?) =
        LiveSnapshot(connection = ConnectionState.LIVE, status = pkt(), lastValidElapsedMs = 900L, gps = gps, gpsElapsedMs = at)

    @Test fun parsesARealFix() {
        val g = HealthParser.parseGps("""{"gpsFix":true,"gpsSats":7,"lat":28.984462,"lng":77.705956}""")
        assertNotNull(g); assertEquals(28.984462, g!!.lat, 1e-9); assertEquals(77.705956, g.lng, 1e-9); assertEquals(7, g.sats)
    }

    @Test fun noFixIsNeverALocation() {
        assertNull(HealthParser.parseGps("""{"gpsFix":false,"lat":28.9,"lng":77.7}"""))
        assertNull(HealthParser.parseGps("""{"gpsFix":true}"""))
        assertNull(HealthParser.parseGps("""{"gpsFix":true,"lat":0,"lng":0}"""))
        assertNull(HealthParser.parseGps("""{"gpsFix":true,"lat":120,"lng":77}"""))
        assertNull(HealthParser.parseGps("not json"))
    }

    @Test fun freshFixReplacesTheDemoLocation() {
        val d = MapDevices.build(live(LiveGps(28.98, 77.70, 6), 5_000L), 26.8467, 80.9462, true, FleetState(), 10_000L).first()
        assertEquals(28.98, d.lat!!, 1e-9); assertTrue(d.locationLive); assertFalse(d.demoLocation)
    }

    @Test fun oldFixFallsBackToTheConfiguredLocation() {
        val d = MapDevices.build(live(LiveGps(28.98, 77.70, 6), 1_000L), 26.8467, 80.9462, true, FleetState(), 200_000L).first()
        assertEquals(26.8467, d.lat!!, 1e-9); assertFalse(d.locationLive); assertTrue(d.demoLocation)
    }

    @Test fun noGpsKeepsTodaysBehaviour() {
        val d = MapDevices.build(live(null, null), 26.8467, 80.9462, true, FleetState(), 10_000L).first()
        assertFalse(d.locationLive); assertTrue(d.demoLocation)
    }
}
