package ru.dredd20.opencode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class WebHistoryPolicyTest {
    @Test
    fun originIncludesPort() {
        assertEquals("http://127.0.0.1:40067", WebHistoryPolicy.originOf("http://127.0.0.1:40067/auth/connect/abc"))
        assertEquals("http://127.0.0.1:80", WebHistoryPolicy.originOf("http://127.0.0.1/"))
        assertEquals("https://example.com:443", WebHistoryPolicy.originOf("https://example.com/path"))
        assertNull(WebHistoryPolicy.originOf(""))
        assertNull(WebHistoryPolicy.originOf(null))
    }

    @Test
    fun differentPortsAreDifferentOrigins() {
        assertFalse(WebHistoryPolicy.sameOrigin("http://127.0.0.1:40603/", "http://127.0.0.1:40067/"))
        assertTrue(WebHistoryPolicy.sameOrigin("http://127.0.0.1:40067/session", "http://127.0.0.1:40067/"))
    }

    @Test
    fun shouldNotGoBackToPreviousPort() {
        assertFalse(WebHistoryPolicy.shouldGoBack("http://127.0.0.1:40067", "http://127.0.0.1:40603/"))
        assertTrue(WebHistoryPolicy.shouldGoBack("http://127.0.0.1:40067", "http://127.0.0.1:40067/auth/connect/x"))
        assertFalse(WebHistoryPolicy.shouldGoBack(null, "http://127.0.0.1:40067/"))
    }
}
