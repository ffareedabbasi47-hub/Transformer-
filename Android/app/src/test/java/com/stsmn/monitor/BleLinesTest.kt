package com.stsmn.monitor

import com.stsmn.monitor.domain.BleLineAssembler
import com.stsmn.monitor.domain.BleMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleLinesTest {
    @Test fun reassemblesChunksIntoLines() {
        val a = BleLineAssembler()
        val line = "ST {\"risk\":12,\"status\":\"SAFE\"}"
        val got = ArrayList<String>()
        (line + "\n").chunked(20).forEach { got += a.feed(it) }
        assertEquals(listOf(line), got)
    }
    @Test fun twoLinesInOneChunkAndSplitAcrossChunks() {
        val a = BleLineAssembler()
        assertEquals(listOf("OK a", "ER b"), a.feed("OK a\nER b\n"))
        assertEquals(emptyList<String>(), a.feed("ST par"))
        assertEquals(listOf("ST partial"), a.feed("tial\r\n"))
    }
    @Test fun runawayLineIsDroppedAndResyncs() {
        val a = BleLineAssembler(maxLine = 10)
        a.feed("x".repeat(30))
        assertEquals(listOf("OK y"), a.feed("\nOK y\n"))
    }
    @Test fun parsesKinds() {
        assertEquals(BleMessage.Kind.STATUS, BleMessage.parse("ST {}")!!.kind)
        assertEquals("{\"a\":1}", BleMessage.parse("HE {\"a\":1}")!!.payload)
        assertEquals(BleMessage.Kind.OK, BleMessage.parse("OK saved +91********48")!!.kind)
        assertEquals(BleMessage.Kind.ERROR, BleMessage.parse("ER wrong password")!!.kind)
        assertNull(BleMessage.parse("XX hello")); assertNull(BleMessage.parse("S")); assertNull(BleMessage.parse("STX {}"))
    }
    @Test fun phoneCommand() { assertEquals("PHONE pw +919876543210\n", BleMessage.phoneCommand("pw", "+919876543210")) }
}
