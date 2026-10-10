package com.stsmn.monitor

import com.stsmn.monitor.domain.ParseResult
import com.stsmn.monitor.domain.StatusParser
import com.stsmn.monitor.domain.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusParserTest {
    private val good = """{"deviceId":"TR-001","fw":"1.0.0","uptime":123456,"temperature":32.4,"voltage":12.1,
        "current":1.4,"smoke":false,"flame":false,"oilLevel":85,"risk":18,"status":"SAFE","relay":true,
        "alarm":false,"seq":1042}"""

    @Test fun parsesContractExample() {
        val r = StatusParser.parse(good) as ParseResult.Valid
        assertEquals("TR-001", r.status.deviceId)
        assertEquals(32.4, r.status.temperature!!, 0.001)
        assertEquals(SystemStatus.SAFE, r.status.status)
        assertEquals(1042L, r.status.seq)
        assertTrue(r.anomalies.isEmpty())
    }

    @Test fun malformedJsonIsRejected() {
        assertTrue(StatusParser.parse("{not json") is ParseResult.Invalid)
        assertTrue(StatusParser.parse("") is ParseResult.Invalid)
    }

    @Test fun missingFieldIsRejected() {
        assertTrue(StatusParser.parse(good.replace("\"seq\":1042", "\"x\":1")) is ParseResult.Invalid)
    }

    @Test fun unknownStatusIsRejected() {
        assertTrue(StatusParser.parse(good.replace("\"SAFE\"", "\"FINE\"")) is ParseResult.Invalid)
    }

    @Test fun nullSensorMeansUnavailable() {
        val r = StatusParser.parse(good.replace("32.4", "null")) as ParseResult.Valid
        assertNull(r.status.temperature)
        assertTrue(r.status.invalidSensors().contains("Temperature"))
    }

    @Test fun impossibleSensorValueMeansUnavailable() {
        val r = StatusParser.parse(good.replace("32.4", "999")) as ParseResult.Valid
        assertNull(r.status.temperature)
    }

    @Test fun riskOutOfRangeIsRejected() {
        assertTrue(StatusParser.parse(good.replace("\"risk\":18", "\"risk\":150")) is ParseResult.Invalid)
    }

    @Test fun unknownFieldsAreIgnored() {
        assertTrue(StatusParser.parse(good.replace("\"seq\":1042", "\"seq\":1042,\"extra\":\"x\"")) is ParseResult.Valid)
    }

    @Test fun emergencyWithRelayOnIsFlagged() {
        val r = StatusParser.parse(good.replace("\"SAFE\"", "\"EMERGENCY\"")) as ParseResult.Valid
        assertEquals(1, r.anomalies.size)
    }
}
