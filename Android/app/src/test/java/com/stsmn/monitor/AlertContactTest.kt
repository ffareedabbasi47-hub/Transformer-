package com.stsmn.monitor

import com.stsmn.monitor.domain.AlertContact
import com.stsmn.monitor.domain.HealthParser
import com.stsmn.monitor.domain.ParseResult
import com.stsmn.monitor.domain.StatusParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertContactTest {
    @Test fun normalizesTheNumberFormatsPeopleType() {
        assertEquals("+917505045948", AlertContact.normalize("7505045948"))
        assertEquals("+917505045948", AlertContact.normalize("75050 45948"))
        assertEquals("+917505045948", AlertContact.normalize("+91-75050-45948"))
        assertEquals("+917505045948", AlertContact.normalize("917505045948"))
        assertEquals("+917505045948", AlertContact.normalize("07505045948"))
        assertEquals("+14155550123", AlertContact.normalize("+14155550123"))
    }

    @Test fun rejectsBadNumbers() {
        assertNull(AlertContact.normalize(""))
        assertNull(AlertContact.normalize("12345"))
        assertNull(AlertContact.normalize("75050abc948"))
        assertNull(AlertContact.normalize("5505045948"))
        assertNull(AlertContact.normalize("+91750504594812345678"))
    }

    @Test fun maskMatchesTheEspRule() {
        assertEquals("+91********48", AlertContact.mask("+917505045948"))
        assertEquals("", AlertContact.mask("+91"))
    }

    @Test fun whatsappLinksAreEncoded() {
        val u = AlertContact.waMeUrl("+917505045948", "STSMN ALERT & more")
        assertEquals("https://wa.me/917505045948?text=STSMN%20ALERT%20%26%20more", u)
        val c = AlertContact.callMeBotUrl("+917505045948", "hi there", "k e y")
        assertTrue(c.startsWith("https://api.callmebot.com/whatsapp.php?phone=%2B917505045948&text=hi%20there&apikey=k%20e%20y"))
    }

    @Test fun statusParserReadsTheOptionalStrengthBlock() {
        val base = """{"deviceId":"TR-001","fw":"1.0.0","uptime":1000,"temperature":30.0,"voltage":null,"current":0.1,
            "smoke":true,"flame":false,"oilLevel":80,"risk":60,"status":"WARNING","relay":true,"alarm":false,"seq":5"""
        val withPct = StatusParser.parse(base + ""","pct":{"temp":10,"current":null,"voltage":null,"smoke":35,"flame":0}}""")
        val s = (withPct as ParseResult.Valid).status
        assertEquals(35, s.smokePct); assertEquals(0, s.flamePct); assertEquals(10, s.tempPct)
        assertNull(s.currentPct); assertNull(s.voltagePct)
        val old = (StatusParser.parse("$base}") as ParseResult.Valid).status
        assertNull(old.smokePct); assertNull(old.flamePct)
        val bad = (StatusParser.parse(base + ""","pct":{"smoke":250,"flame":"x"}}""") as ParseResult.Valid).status
        assertNull(bad.smokePct); assertNull(bad.flamePct)
    }

    @Test fun healthParserReadsTheMaskedNumber() {
        assertEquals("+91********48", HealthParser.parseAlertPhone("""{"alertPhone":"+91********48"}"""))
        assertEquals("", HealthParser.parseAlertPhone("""{"alertPhone":""}"""))
        assertNull(HealthParser.parseAlertPhone("""{"gpsFix":false}"""))
        assertNull(HealthParser.parseAlertPhone("nope"))
    }
}
