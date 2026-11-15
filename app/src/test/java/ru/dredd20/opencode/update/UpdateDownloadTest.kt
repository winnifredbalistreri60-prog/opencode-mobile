package ru.dredd20.opencode.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

class UpdateDownloadTest {
    @Test
    fun rangeStart() {
        assertNull(UpdateDownload.rangeStart(0, 1000))
        assertEquals(400L, UpdateDownload.rangeStart(400, 1000))
        assertEquals(1000L, UpdateDownload.rangeStart(1000, 1000))
        assertNull(UpdateDownload.rangeStart(1200, 1000))
    }

    @Test
    fun nextStep() {
        assertEquals(UpdateDownload.Next.APPEND, UpdateDownload.next(206, 400, 1000, 400))
        assertEquals(UpdateDownload.Next.RESTART, UpdateDownload.next(200, 400, 1000, 400))
        assertEquals(UpdateDownload.Next.RESTART, UpdateDownload.next(200, null, 1000, 0))
        assertEquals(UpdateDownload.Next.ALREADY_COMPLETE, UpdateDownload.next(416, 1000, 1000, 1000))
        assertEquals(UpdateDownload.Next.RESTART, UpdateDownload.next(416, 400, 1000, 400))
    }

    @Test
    fun contentRange() {
        assertEquals(400L, UpdateDownload.contentRangeStart("bytes 400-999/1000"))
        assertEquals(0L, UpdateDownload.contentRangeStart("bytes 0-9/*"))
        assertNull(UpdateDownload.contentRangeStart(null))
        assertNull(UpdateDownload.contentRangeStart("garbage"))
    }

    @Test
    fun networkVsVerify() {
        assertTrue(UpdateDownload.isNetworkError(SocketException("Software caused connection abort")))
        assertTrue(UpdateDownload.isNetworkError(IOException("HTTP 503")))
        assertFalse(UpdateDownload.isNetworkError(UpdateDownload.VerifyException("sha256 mismatch")))
        assertFalse(UpdateDownload.isNetworkError(IllegalStateException("x")))
    }

    @Test
    fun partNameTiedToBuild() {
        assertEquals("opencode-mobile-11-abcdef012345.apk.part", UpdateDownload.partName(11, "ABCDEF0123456789"))
        assertEquals("opencode-mobile-11.apk", UpdateDownload.apkName(11))
    }
}
