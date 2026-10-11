package com.stsmn.monitor

import com.stsmn.monitor.domain.GlobeData
import com.stsmn.monitor.domain.GlobeFailure
import com.stsmn.monitor.domain.GlobeLifecycle
import com.stsmn.monitor.domain.GlobeNotice
import com.stsmn.monitor.domain.GlobePhase
import com.stsmn.monitor.domain.MapDevice
import com.stsmn.monitor.domain.MarkerState
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeTest {
    private fun dev(id: String, lat: Double?, lng: Double?, state: MarkerState = MarkerState.SAFE, demo: Boolean = false) = MapDevice(
        id, id, lat, lng, demo, state, null, null, null, null, null, null, null, null, null, null, null, false,
    )

    // ---- data sent to the page ----
    @Test fun emptyFleetGivesEmptyArray() = assertEquals("[]", GlobeData.json(emptyList()))

    @Test fun onlyValidDevicesAreSent() {
        val arr = JSONArray(GlobeData.json(listOf(
            dev("T01", 26.85, 80.9),
            dev("T02", null, null),                    // no position: stays in list screens only
            dev("T03", 91.0, 10.0),                    // latitude out of range
            dev("T04", 10.0, 181.0),                   // longitude out of range
            dev("<img src=x onerror=alert(1)>", 1.0, 1.0), // hostile ID
            dev("t05", 1.0, 1.0),                      // lowercase is not a valid ID
        )))
        assertEquals(1, arr.length())
        assertEquals("T01", arr.getJSONObject(0).getString("id"))
    }

    @Test fun stateAndDemoFlagArePassedThrough() {
        val o = JSONArray(GlobeData.json(listOf(dev("T9", 1.0, 2.0, MarkerState.EMERGENCY, demo = true)))).getJSONObject(0)
        assertEquals("EMERGENCY", o.getString("state"))
        assertTrue(o.getBoolean("demo"))
    }

    @Test fun idRule() {
        assertTrue(GlobeData.validId("T01")); assertTrue(GlobeData.validId("A-1"))
        assertFalse(GlobeData.validId(null)); assertFalse(GlobeData.validId("")); assertFalse(GlobeData.validId("a"))
        assertFalse(GlobeData.validId("T01'); alert(1);//"))
    }

    // ---- lifecycle ----
    @Test fun successfulStart() {
        val s = GlobeLifecycle.onReady(GlobeLifecycle.initial())
        assertEquals(GlobePhase.READY, s.phase)
    }

    @Test fun timeoutNeverOverridesSuccess() {
        val ready = GlobeLifecycle.onReady(GlobeLifecycle.initial())
        assertEquals(GlobePhase.READY, GlobeLifecycle.onTimeout(ready).phase)
    }

    @Test fun timeoutBeforeStartIsFatal() {
        val s = GlobeLifecycle.onTimeout(GlobeLifecycle.initial())
        assertEquals(GlobePhase.FATAL, s.phase); assertEquals(GlobeFailure.LOAD_TIMEOUT, s.failure)
    }

    @Test fun deadPageCannotBecomeReady() {
        val s = GlobeLifecycle.onReady(GlobeLifecycle.onFatal(GlobeLifecycle.initial(), GlobeFailure.WEBGL_UNSUPPORTED))
        assertEquals(GlobePhase.FATAL, s.phase)
    }

    @Test fun firstFailureWinsAndIsNotDuplicated() {
        val a = GlobeLifecycle.onFatal(GlobeLifecycle.initial(), GlobeFailure.LOAD_ERROR, "style")
        val b = GlobeLifecycle.onFatal(a, GlobeFailure.INIT_ERROR, "other")
        assertEquals(a, b)
    }

    @Test fun recoverableErrorKeepsUsableGlobeAlive() {
        val ready = GlobeLifecycle.onReady(GlobeLifecycle.initial())
        val s = GlobeLifecycle.onFatal(ready, GlobeFailure.INIT_ERROR, "late script error")
        assertEquals(GlobePhase.READY, s.phase)
        assertTrue(GlobeNotice.SCRIPT_ERROR in s.notices)
    }

    @Test fun noticesNeverChangePhase() {
        var s = GlobeLifecycle.initial()
        s = GlobeLifecycle.onNotice(s, GlobeNotice.TILES_FAILED)
        assertEquals(GlobePhase.INITIALIZING, s.phase)
        s = GlobeLifecycle.onNotice(GlobeLifecycle.onReady(s), GlobeNotice.TERRAIN_FAILED)
        assertEquals(GlobePhase.READY, s.phase); assertEquals(2, s.notices.size)
    }

    @Test fun rendererCrashAfterStartFallsBack() {
        val s = GlobeLifecycle.onRendererGone(GlobeLifecycle.onReady(GlobeLifecycle.initial()))
        assertEquals(GlobePhase.FATAL, s.phase); assertEquals(GlobeFailure.RENDERER_CRASHED, s.failure)
    }

    @Test fun retryStartsFreshOnlyOnUserAction() {
        val failed = GlobeLifecycle.onTimeout(GlobeLifecycle.initial())
        val again = GlobeLifecycle.retry(failed)
        assertEquals(GlobePhase.INITIALIZING, again.phase)
        assertEquals(1, again.attempt)
        assertTrue(again.notices.isEmpty() && again.failure == null)
    }

    @Test fun unknownKindsFromThePageAreSafe() {
        assertEquals(GlobeFailure.INIT_ERROR, GlobeLifecycle.parseFailure("whatever"))
        assertEquals(GlobeFailure.WEBGL_UNSUPPORTED, GlobeLifecycle.parseFailure("WEBGL_UNSUPPORTED"))
        assertEquals(GlobeNotice.SCRIPT_ERROR, GlobeLifecycle.parseNotice(null))
    }
}
