package com.stsmn.monitor

import com.stsmn.monitor.domain.MapDevice
import com.stsmn.monitor.domain.MarkerState
import com.stsmn.monitor.domain.SystemStatus
import com.stsmn.monitor.ui.globeJson
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobeJsonTest {
    @Test
    fun serializesTelemetryWhenAvailable() {
        val device = MapDevice(
            id = "TR-001",
            name = "Test Transformer",
            lat = 26.85,
            lng = 80.94,
            demoLocation = true,
            state = MarkerState.SAFE,
            status = SystemStatus.SAFE,
            risk = 18,
            temperature = 32.4,
            voltage = 12.1,
            current = 1.4,
            oilLevel = 85,
            smoke = false,
            flame = false,
            relay = true,
            alarm = false,
            ageMs = 1200L,
            isLocal = true,
        )

        val jsonStr = globeJson(listOf(device))
        val arr = JSONArray(JSONTokener(jsonStr))
        assertEquals(1, arr.length())

        val obj = arr.getJSONObject(0)
        assertEquals("TR-001", obj.getString("id"))
        assertEquals("Test Transformer", obj.getString("name"))
        assertEquals(26.85, obj.getDouble("lat"), 0.001)
        assertEquals(80.94, obj.getDouble("lng"), 0.001)
        assertEquals("SAFE", obj.getString("state"))
        assertTrue(obj.getBoolean("demo"))
        assertTrue(obj.getBoolean("isLive"))
        assertEquals(18, obj.getInt("risk"))
        assertEquals(32.4, obj.getDouble("temp"), 0.001)
        assertEquals(12.1, obj.getDouble("volt"), 0.001)
        assertEquals(1.4, obj.getDouble("amp"), 0.001)
        assertFalse(obj.getBoolean("smoke"))
        assertFalse(obj.getBoolean("flame"))
        assertEquals(85, obj.getInt("oil"))
        assertTrue(obj.getBoolean("relay"))
        assertFalse(obj.getBoolean("alarm"))
        assertEquals(1200L, obj.getLong("ageMs"))
    }

    @Test
    fun skipsDevicesWithoutCoordinates() {
        val noLocation = MapDevice(
            id = "TR-002",
            name = "No Coords",
            lat = null,
            lng = null,
            demoLocation = false,
            state = MarkerState.OFFLINE,
            status = null,
            risk = null,
            temperature = null,
            voltage = null,
            current = null,
            oilLevel = null,
            smoke = null,
            flame = null,
            relay = null,
            alarm = null,
            ageMs = null,
            isLocal = false,
        )
        val jsonStr = globeJson(listOf(noLocation))
        val arr = JSONArray(JSONTokener(jsonStr))
        assertEquals(0, arr.length())
    }
}
