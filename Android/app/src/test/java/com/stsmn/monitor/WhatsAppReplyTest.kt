package com.stsmn.monitor

import com.stsmn.monitor.domain.WhatsAppReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppReplyTest {
    @Test fun queuedIsSuccess() {
        assertNull(WhatsAppReply.classify(200, "<html><b>Message queued</b>. You will receive it in a few seconds.</html>"))
        assertNull(WhatsAppReply.classify(200, "Message queued. You will receive it in a few seconds."))
    }
    @Test fun http200WithErrorTextIsNotSuccess() {
        val r = WhatsAppReply.classify(200, "<p>ERROR: APIKey is invalid</p>")
        assertNotNull(r); assertTrue(r!!.contains("key"))
    }
    @Test fun notActivated() { assertTrue(WhatsAppReply.classify(200, "ERROR: phone number not registered")!!.contains("activate")) }
    @Test fun rateLimit() { assertTrue(WhatsAppReply.classify(429, "")!!.contains("rate")) }
    @Test fun serverDown() { assertTrue(WhatsAppReply.classify(503, "")!!.contains("down")) }
    @Test fun emptyBodyIsNotSuccess() { assertEquals("service ne HTTP 200 diya, par koi message nahi", WhatsAppReply.classify(200, "")) }
}
