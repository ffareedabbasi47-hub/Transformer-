package com.stsmn.monitor

import com.stsmn.monitor.domain.EventDetector
import com.stsmn.monitor.domain.EventType
import com.stsmn.monitor.domain.StsmnStatus
import com.stsmn.monitor.domain.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {
    private fun pkt(
        seq: Long, uptime: Long = seq * 1000, status: SystemStatus = SystemStatus.SAFE,
        relay: Boolean = true, temp: Double? = 30.0,
    ) = StsmnStatus("TR-001", "1.0.0", uptime, temp, 12.0, 1.0, false, false, 80, 10, status, relay, false, seq)

    @Test fun noEventsWhileNothingChanges() {
        val d = EventDetector()
        d.onPacket(0, pkt(1))
        assertTrue(d.onPacket(1, pkt(2)).isEmpty())
    }

    @Test fun emergencyAndRelayChangeAreLoggedOnce() {
        val d = EventDetector()
        d.onPacket(0, pkt(1))
        val ev = d.onPacket(1, pkt(2, status = SystemStatus.EMERGENCY, relay = false))
        assertTrue(ev.any { it.type == EventType.EMERGENCY })
        assertTrue(ev.any { it.type == EventType.RELAY_CHANGED })
        assertTrue(d.onPacket(2, pkt(3, status = SystemStatus.EMERGENCY, relay = false)).isEmpty())
    }

    @Test fun restartIsDetectedFromUptimeReset() {
        val d = EventDetector()
        d.onPacket(0, pkt(50))
        val ev = d.onPacket(1, pkt(1, uptime = 500))
        assertTrue(ev.any { it.type == EventType.DEVICE_RESTART })
    }

    @Test fun invalidSensorIsDebouncedAndReportedOnce() {
        val d = EventDetector(invalidDebouncePackets = 3)
        d.onPacket(0, pkt(1))
        assertTrue(d.onPacket(1, pkt(2, temp = null)).isEmpty())
        assertTrue(d.onPacket(2, pkt(3, temp = null)).isEmpty())
        assertEquals(1, d.onPacket(3, pkt(4, temp = null)).count { it.type == EventType.SENSOR_INVALID })
        assertTrue(d.onPacket(4, pkt(5, temp = null)).isEmpty())
    }
}
