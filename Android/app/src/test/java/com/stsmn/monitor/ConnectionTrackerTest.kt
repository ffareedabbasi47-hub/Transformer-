package com.stsmn.monitor

import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.ConnectionTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTrackerTest {
    private val stale = 5000L

    @Test fun fullLifecycle() {
        val t = ConnectionTracker(errorAfterFailures = 3)
        assertEquals(ConnectionState.IDLE, t.stateAt(0, stale))
        t.start()
        assertEquals(ConnectionState.CONNECTING, t.stateAt(0, stale))
        t.onValidPacket(1000)
        assertEquals(ConnectionState.LIVE, t.stateAt(2000, stale))
        assertEquals(ConnectionState.STALE, t.stateAt(7000, stale))
        t.onValidPacket(7500)
        assertEquals(ConnectionState.LIVE, t.stateAt(7600, stale))
    }

    @Test fun repeatedFailuresMeanError() {
        val t = ConnectionTracker(errorAfterFailures = 3)
        t.start()
        repeat(3) { t.onFailure() }
        assertEquals(ConnectionState.ERROR, t.stateAt(100, stale))
        t.onValidPacket(200)
        assertEquals(ConnectionState.LIVE, t.stateAt(300, stale))
    }

    @Test fun stopReturnsToIdle() {
        val t = ConnectionTracker()
        t.start(); t.onValidPacket(0); t.stop()
        assertEquals(ConnectionState.IDLE, t.stateAt(10, stale))
    }
}
