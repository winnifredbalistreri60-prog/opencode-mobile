package ru.dredd20.opencode.serve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAuthTest {
    @Test
    fun cookieNameIncludesPort() {
        assertEquals("opencode_session_35193", SessionAuth.sessionCookieName("127.0.0.1:35193"))
        assertEquals("opencode_session_4096", SessionAuth.sessionCookieName("localhost:4096"))
        assertEquals("opencode_session", SessionAuth.sessionCookieName("example.com"))
        assertEquals("opencode_session", SessionAuth.sessionCookieName(null))
    }

    @Test
    fun connectUrlIsPathOnOrigin() {
        assertEquals("http://127.0.0.1:35193/auth/connect/abc", SessionAuth.connectUrl("http://127.0.0.1:35193", "abc"))
        assertEquals("http://127.0.0.1:35193/auth/connect/abc", SessionAuth.connectUrl("http://127.0.0.1:35193/", "abc"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun connectUrlRejectsSlashInCode() {
        SessionAuth.connectUrl("http://127.0.0.1:1", "a/b")
    }

    @Test
    fun sessionCookieDetection() {
        val host = "127.0.0.1:35193"
        assertTrue(SessionAuth.cookieLooksLikeSession("opencode_session_35193=expires.sig; Path=/", host))
        assertFalse(SessionAuth.cookieLooksLikeSession("other=1", host))
        assertFalse(SessionAuth.cookieLooksLikeSession(null, host))
    }
}
