package ru.dredd20.opencode.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotAndReleaseTest {
    @Test
    fun envIsolatesXdgAndDisablesUpdates() {
        val l = ProotLayout("/lib", "/rootfs", "/host", "/tmpx", "/fake", "/runx")
        val env = ProotCommand.env(l, noSeccomp = true, hasLoader32 = false)
        assertEquals("/root", env["HOME"])
        assertEquals("/tmp", env["TMPDIR"])
        assertEquals("/root/.local/share", env["XDG_DATA_HOME"])
        assertEquals("/root/.config", env["XDG_CONFIG_HOME"])
        assertEquals("/root/.local/state", env["XDG_STATE_HOME"])
        assertEquals("/root/.cache", env["XDG_CACHE_HOME"])
        assertEquals("1", env["OPENCODE_DISABLE_AUTOUPDATE"])
        assertEquals("1", env["OPENCODE_FILEWATCHER_DISABLE"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertFalse(env.values.any { it.startsWith("/data/") })
    }

    @Test
    fun argsBindRunAndHost() {
        val l = ProotLayout("/n", "/r", "/h", "/t", "/f", "/runhost")
        val a = ProotCommand.args(l, listOf("/bin/bash"), fakeProcFiles = listOf("version"))
        assertTrue(a.contains("--kill-on-exit"))
        assertTrue(a.contains("/runhost:/run"))
        assertTrue(a.contains("/h:/opencode-host"))
        assertTrue(a.contains("/f/version:/proc/version"))
    }

    @Test
    fun procPpidParser() {
        val stat = "123 (some name) S 45 1 1 0 0"
        assertEquals(45, ProcTree.parsePpid(stat))
        val kids = ProcTree.descendants(1, java.io.File("does-not-exist-proc"))
        assertTrue(kids.isEmpty())
    }

    @Test
    fun officialHashesPinned() {
        assertEquals("fc0f81522aceeabac47693d88389efa2bc517ce74c2c38fd6967819fcc6e4d3b", OpenCodeRelease.LINUX_X64.sha256)
        assertEquals("3207fb5cdec448d479242c0f1a14f4f7bdfcad3a79209b5eb8f9003725e93711", OpenCodeRelease.LINUX_ARM64.sha256)
        assertEquals(OpenCodeRelease.LINUX_ARM64, OpenCodeRelease.forAbi("arm64-v8a"))
        assertEquals(OpenCodeRelease.LINUX_X64, OpenCodeRelease.forAbi("x86_64"))
        assertEquals(null, OpenCodeRelease.forAbi("armeabi-v7a"))
    }

    @Test
    fun ubuntuBasePicker() {
        val sums = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  ubuntu-base-24.04-base-amd64.tar.gz
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  ubuntu-base-24.04.3-base-amd64.tar.gz
            cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc  ubuntu-base-24.04.3-base-arm64.tar.gz
        """.trimIndent()
        val img = UbuntuBase.pick(sums, "amd64")!!
        assertEquals("ubuntu-base-24.04.3-base-amd64.tar.gz", img.name)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", img.sha256)
        assertEquals("amd64", UbuntuBase.archFor("x86_64"))
        assertEquals("arm64", UbuntuBase.archFor("arm64-v8a"))
        assertEquals(null, UbuntuBase.archFor("armeabi-v7a"))
    }
}
