package ru.dredd20.opencode.serve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteOriginTest {
    @Test
    fun rejectsIncompleteUrl() {
        assertNull(RemoteOrigin.parse(""))
        assertNull(RemoteOrigin.parse("http://"))
        assertNull(RemoteOrigin.parse("http://:"))
        assertNull(RemoteOrigin.parse("ftp://10.0.2.2:47200"))
        assertEquals(RemoteOrigin.ParseIssue.EMPTY, RemoteOrigin.parseIssue("  "))
        assertEquals(RemoteOrigin.ParseIssue.UNSUPPORTED_SCHEME, RemoteOrigin.parseIssue("ftp://10.0.2.2"))
        assertEquals(RemoteOrigin.ParseIssue.CREDENTIALS_IN_URL, RemoteOrigin.parseIssue("https://user:secret@example.com"))
        assertEquals(RemoteOrigin.ParseIssue.PATH_NOT_SUPPORTED, RemoteOrigin.parseIssue("https://example.com/opencode"))
        assertEquals(RemoteOrigin.ParseIssue.QUERY_OR_FRAGMENT_NOT_SUPPORTED, RemoteOrigin.parseIssue("https://example.com/?x=1"))
        assertEquals(RemoteOrigin.ParseIssue.INVALID_PORT, RemoteOrigin.parseIssue("http://example.com:70000"))
    }

    @Test
    fun parsesCompleteHttp() {
        val p = RemoteOrigin.parse("http://10.0.2.2:47200")
        assertNotNull(p)
        assertEquals("10.0.2.2", p!!.host)
        assertEquals(47200, p.port)
        assertEquals("http://10.0.2.2:47200", p.origin)
    }

    @Test
    fun defaultHttpsPort() {
        val p = RemoteOrigin.parse("https://example.com/")
        assertEquals(443, p!!.port)
    }

    @Test
    fun explainsInvalidUrl() {
        assertNotNull(RemoteOrigin.errorMessage("ftp://example.com"))
        assertNull(RemoteOrigin.errorMessage("https://example.com:443"))
    }
}
