package com.stsmn.monitor

import com.stsmn.monitor.domain.CloudRules
import com.stsmn.monitor.domain.CloudState
import com.stsmn.monitor.domain.ConnectionState
import com.stsmn.monitor.domain.FleetDevice
import com.stsmn.monitor.domain.FleetLink
import com.stsmn.monitor.domain.FleetParser
import com.stsmn.monitor.domain.FleetState
import com.stsmn.monitor.domain.LiveSnapshot
import com.stsmn.monitor.domain.MapDevices
import com.stsmn.monitor.domain.MarkerState
import com.stsmn.monitor.domain.SystemStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAndFleetTest {
    private fun dev(id: String, link: FleetLink, status: SystemStatus?, lat: Double? = 26.8, lng: Double? = 80.9) = FleetDevice(
        id, id, lat, lng, false, link, status, 10, 30.0, 3.7, 0.4, 80, false, false, true, false, 1000L, 0L,
    )

    // ---- server address rules (security: never send keys over plain internet) ----
    @Test fun wssIsAccepted() = assertTrue(CloudRules.isValidServerUrl("wss://stsmn.example.com/ws"))
    @Test fun plainWsToPublicHostIsRejected() = assertFalse(CloudRules.isValidServerUrl("ws://stsmn.example.com/ws"))
    @Test fun plainWsToPrivateLanIsAccepted() = assertTrue(CloudRules.isValidServerUrl("ws://192.168.1.20:8080/ws"))
    @Test fun plainWsToPublicIpIsRejected() = assertFalse(CloudRules.isValidServerUrl("ws://8.8.8.8:8080/ws"))
    @Test fun httpAndGarbageAreRejected() {
        assertFalse(CloudRules.isValidServerUrl("https://x.com"))
        assertFalse(CloudRules.isValidServerUrl("wss://x.com/ws?token=abc"))
        assertFalse(CloudRules.isValidServerUrl("wss://exa mple.com"))
        assertFalse(CloudRules.isValidServerUrl("wss://" + "a".repeat(250)))
    }
    @Test fun deviceIdRules() {
        assertTrue(CloudRules.isValidDeviceId("TR-001"))
        assertFalse(CloudRules.isValidDeviceId("tr-001"))
        assertFalse(CloudRules.isValidDeviceId("TR 001"))
        assertFalse(CloudRules.isValidDeviceId("<script>"))
    }
    @Test fun secretsAreCleaned() = assertEquals("abc123", CloudRules.cleanSecret(" abc 12\n3 "))

    // ---- server message parsing ----
    @Test fun parsesDeviceAndComputesAge() {
        val o = JSONObject("""{"id":"TR-002","name":"Second","lat":26.9,"lng":80.9,"link":"live","ts":9000,"status":"WARNING","risk":55,
            "temperature":48.7,"voltage":11.8,"current":3.2,"oilLevel":72,"smoke":false,"flame":false,"relay":true,"alarm":false}""")
        val d = FleetParser.device(o, serverNowMs = 10_000, elapsedNowMs = 500)!!
        assertEquals("TR-002", d.id); assertEquals(SystemStatus.WARNING, d.status); assertEquals(1000L, d.ageAtReceiptMs)
        assertEquals(1500L, d.ageMs(1000)) // 1000 at receipt + 500 elapsed since
    }
    @Test fun nullSensorsStayNull() {
        val d = FleetParser.device(JSONObject("""{"id":"TR-003","link":"offline","temperature":null,"ts":null}"""), null, 0)!!
        assertNull(d.temperature); assertNull(d.ageAtReceiptMs); assertEquals(FleetLink.OFFLINE, d.link)
    }
    @Test fun badIdIsDropped() {
        assertNull(FleetParser.device(JSONObject("""{"id":"<b>x</b>"}"""), null, 0))
        assertNull(FleetParser.device(JSONObject("""{"name":"no id"}"""), null, 0))
    }
    @Test fun unknownStatusBecomesNull() {
        assertNull(FleetParser.device(JSONObject("""{"id":"TR-004","status":"HACKED"}"""), null, 0)!!.status)
    }

    // ---- marker colour rules ----
    @Test fun liveStatusMapsToColour() {
        assertEquals(MarkerState.EMERGENCY, FleetParser.markerState(dev("TR-1A", FleetLink.LIVE, SystemStatus.EMERGENCY), true))
        assertEquals(MarkerState.SAFE, FleetParser.markerState(dev("TR-1A", FleetLink.LIVE, SystemStatus.SAFE), true))
    }
    @Test fun serverDownMakesEverythingStale() =
        assertEquals(MarkerState.STALE, FleetParser.markerState(dev("TR-1A", FleetLink.LIVE, SystemStatus.SAFE), cloudOnline = false))
    @Test fun staleAndOfflineNeverLookLive() {
        assertEquals(MarkerState.STALE, FleetParser.markerState(dev("TR-1A", FleetLink.STALE, SystemStatus.EMERGENCY), true))
        assertEquals(MarkerState.OFFLINE, FleetParser.markerState(dev("TR-1A", FleetLink.OFFLINE, SystemStatus.SAFE), true))
    }

    // ---- merged list ----
    @Test fun dangerousFirst() {
        val fleet = FleetState(CloudState.ONLINE, listOf(
            dev("TR-001", FleetLink.LIVE, SystemStatus.SAFE), dev("TR-002", FleetLink.LIVE, SystemStatus.EMERGENCY),
            dev("TR-003", FleetLink.OFFLINE, null), dev("TR-004", FleetLink.LIVE, SystemStatus.WARNING)))
        val list = MapDevices.build(LiveSnapshot(), null, null, false, fleet, 0L)
        assertEquals(listOf("TR-002", "TR-004", "TR-003", "TR-001"), list.map { it.id })
    }
    @Test fun idleLocalDoesNotAddAGhostWhenFleetHasDevices() {
        val fleet = FleetState(CloudState.ONLINE, listOf(dev("TR-001", FleetLink.LIVE, SystemStatus.SAFE)))
        assertEquals(1, MapDevices.build(LiveSnapshot(connection = ConnectionState.IDLE), 26.8, 80.9, true, fleet, 0L).size)
    }
    @Test fun localOnlyShowsOneOfflineMarker() {
        val list = MapDevices.build(LiveSnapshot(), 26.8, 80.9, true, FleetState(), 0L)
        assertEquals(1, list.size); assertEquals(MarkerState.OFFLINE, list[0].state); assertTrue(list[0].demoLocation); assertNotNull(list[0].lat)
    }
}
