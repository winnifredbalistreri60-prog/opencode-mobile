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
        val p = RemoteOrigin.parse("https://example.com/path")
        assertEquals(443, p!!.port)
    }
}
