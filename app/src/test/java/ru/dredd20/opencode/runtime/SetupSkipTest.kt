package ru.dredd20.opencode.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SetupSkipTest {
    @Test
    fun skipDownloadWhenShaMatches() {
        val dir = createTempDirectory("oc-skip").toFile()
        val f = File(dir, "opencode-linux-x64.tar.gz")
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(SetupSkip.shouldSkipDownload(f, "abc", "ABC"))
        assertFalse(SetupSkip.shouldSkipDownload(f, "abc", "def"))
        assertFalse(SetupSkip.shouldSkipDownload(File(dir, "missing"), "abc", "abc"))
        dir.deleteRecursively()
    }

    @Test
    fun skipOpenCodeWhenVersionMarkerMatches() {
        assertTrue(SetupSkip.shouldSkipOpenCodeInstall(true, "2.3.0", "2.3.0", force = false))
        assertFalse(SetupSkip.shouldSkipOpenCodeInstall(true, "2.3.0", "2.3.0", force = true))
        assertFalse(SetupSkip.shouldSkipOpenCodeInstall(true, null, "2.3.0", force = false))
        assertFalse(SetupSkip.shouldSkipOpenCodeInstall(false, "2.3.0", "2.3.0", force = false))
    }

    @Test
    fun skipAptWhenMarkerAndBinariesPresent() {
        assertTrue(SetupSkip.shouldSkipApt(true, true, true, true, force = false))
        assertFalse(SetupSkip.shouldSkipApt(true, true, false, true, force = false))
        assertFalse(SetupSkip.shouldSkipApt(false, true, true, true, force = false))
    }
}
