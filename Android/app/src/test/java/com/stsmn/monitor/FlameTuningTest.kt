package com.stsmn.monitor

import com.stsmn.monitor.domain.FlameTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameTuningTest {
    private val json = """{"minDelta":40,"flicker":4.0,"reversals":4,"confirmMs":500,"rebaseMs":20000,"requireDo":1,
        "live":{"usable":1,"ao":2750,"base":2800,"dev":50,"noise":3.2,"rev":6,"do":1,"pct":12,"critical":0}}"""

    @Test fun parsesDeviceAnswer() {
        val (t, l) = FlameTuning.parse(json)!!
        assertEquals(FlameTuning(40, 4.0, 4, 500, 20, true), t)
        assertNotNull(l); assertEquals(2800, l!!.base); assertTrue(l.doOn)
    }

    @Test fun garbageIsNull() { assertNull(FlameTuning.parse("<html>")) }

    @Test fun formIsClampedAndInMs() {
        val f = FlameTuning(minDelta = 9999, flicker = 0.1, reversals = 99, confirmMs = 1, rebaseSec = 500, requireDo = false).toForm(relearn = true)
        assertEquals("300", f["minDelta"]); assertEquals("2.0", f["flicker"]); assertEquals("12", f["reversals"])
        assertEquals("250", f["confirmMs"]); assertEquals("60000", f["rebaseMs"]); assertEquals("0", f["requireDo"]); assertEquals("1", f["relearn"])
    }

    @Test fun invertIsParsedAndSent() {
        val (t, _) = FlameTuning.parse(json.replace("\"requireDo\":1,", "\"requireDo\":1,\"invert\":1,"))!!
        assertTrue(t.invert)
        assertEquals("1", t.toForm()["invert"])
    }
}
