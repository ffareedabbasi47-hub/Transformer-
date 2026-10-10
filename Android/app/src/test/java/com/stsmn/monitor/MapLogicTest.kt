package com.stsmn.monitor

import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.ConnectionTracker
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.MarkerState
import com.stsmn.monitor.domain.MarkerStateMapper
import com.stsmn.monitor.domain.SiteLocationRules
import com.stsmn.monitor.domain.StsmnStatus
import com.stsmn.monitor.domain.SystemStatus
import com.stsmn.monitor.domain.TransformerLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLogicTest {
    private fun pkt(status: SystemStatus) =
        StsmnStatus("TR-001", "1.0.0", 1000, 30.0, 12.0, 1.0, false, false, 80, 10, status, true, false, 1)

    private fun snap(conn: ConnectionState, status: SystemStatus? = SystemStatus.SAFE) =
        LiveSnapshot(connection = conn, status = status?.let { pkt(it) })

    // ---- marker status updates ----
    @Test fun liveStatusesMapToTheirOwnMarker() {
        assertEquals(MarkerState.SAFE, MarkerStateMapper.from(snap(ConnectionState.LIVE, SystemStatus.SAFE)))
        assertEquals(MarkerState.WARNING, MarkerStateMapper.from(snap(ConnectionState.LIVE, SystemStatus.WARNING)))
        assertEquals(MarkerState.EMERGENCY, MarkerStateMapper.from(snap(ConnectionState.LIVE, SystemStatus.EMERGENCY)))
    }

    @Test fun oldReadingsAreNeverShownAsLive() {
        // last packet said EMERGENCY / SAFE, but the link is no longer LIVE
        assertEquals(MarkerState.STALE, MarkerStateMapper.from(snap(ConnectionState.STALE, SystemStatus.EMERGENCY)))
        assertEquals(MarkerState.OFFLINE, MarkerStateMapper.from(snap(ConnectionState.ERROR, SystemStatus.SAFE)))
        assertEquals(MarkerState.OFFLINE, MarkerStateMapper.from(snap(ConnectionState.IDLE, SystemStatus.SAFE)))
        assertEquals(MarkerState.OFFLINE, MarkerStateMapper.from(snap(ConnectionState.CONNECTING, null)))
    }

    @Test fun everyMarkerStateHasATextLabel() {
        MarkerState.values().forEach { assertTrue(it.label.isNotBlank()) }
    }

    // ---- API failure / Wi-Fi loss -> OFFLINE through the real state machine ----
    @Test fun wifiLossGoesStaleThenOffline() {
        val t = ConnectionTracker(errorAfterFailures = 3)
        t.start(); t.onValidPacket(0)
        val live = LiveSnapshot(connection = t.stateAt(1000, 10000), status = pkt(SystemStatus.SAFE))
        assertEquals(MarkerState.SAFE, MarkerStateMapper.from(live))
        repeat(2) { t.onFailure() }
        val stale = LiveSnapshot(connection = t.stateAt(20000, 10000), status = pkt(SystemStatus.SAFE))
        assertEquals(MarkerState.STALE, MarkerStateMapper.from(stale))
        t.onFailure()
        val offline = LiveSnapshot(connection = t.stateAt(21000, 10000), status = pkt(SystemStatus.SAFE))
        assertEquals(MarkerState.OFFLINE, MarkerStateMapper.from(offline))
    }

    // ---- coordinates ----
    @Test fun invalidCoordinatesAreRejected() {
        assertFalse(SiteLocationRules.isValid(91.0, 80.0))
        assertFalse(SiteLocationRules.isValid(26.0, 181.0))
        assertFalse(SiteLocationRules.isValid(Double.NaN, 80.0))
        assertFalse(SiteLocationRules.isValid(26.0, Double.POSITIVE_INFINITY))
        assertTrue(SiteLocationRules.isValid(26.8467, 80.9462))
    }

    @Test fun demoCoordinatesAreRecognisedAndUpBoxWorks() {
        assertTrue(SiteLocationRules.isDemoCoordinate(26.8467, 80.9462))
        assertFalse(SiteLocationRules.isDemoCoordinate(26.85, 80.95))
        assertTrue(SiteLocationRules.isInsideUttarPradesh(26.85, 80.95))
        assertFalse(SiteLocationRules.isInsideUttarPradesh(28.61, 77.9 - 1.0)) // west of UP box
    }

    // ---- label ----
    @Test fun labelIsStsmnPrefixedDeviceId() {
        assertEquals("STSMN-TR-001", TransformerLabel.from("TR-001"))
        assertEquals("STSMN-TR-001", TransformerLabel.from("STSMN-TR-001"))
        assertEquals("STSMN-TR-001", TransformerLabel.from(null))
    }
}

class DeviceHostTest {
    @Test fun onlyPrivateIpv4IsAccepted() {
        val ok = com.stsmn.monitor.data.SettingsStore.Companion
        assertTrue(ok.isValidHost("192.168.4.1"))
        assertTrue(ok.isValidHost("10.0.0.5"))
        assertTrue(ok.isValidHost("172.16.1.1"))
        assertFalse(ok.isValidHost("172.32.0.1"))
        assertFalse(ok.isValidHost("8.8.8.8"))
        assertFalse(ok.isValidHost("example.com"))
        assertFalse(ok.isValidHost("192.168.4.1/x"))
        assertFalse(ok.isValidHost("192.168.4.300"))
        assertFalse(ok.isValidHost(""))
    }
}
