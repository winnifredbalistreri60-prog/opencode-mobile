package ru.dredd20.opencode.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraRingTest {
    @Test
    fun pruneKeepsNewest() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cam-ring-${System.nanoTime()}").apply { mkdirs() }
        try {
            val files = (1..5).map { i ->
                File(dir, "frame-$i.jpg").also {
                    it.writeText("x")
                    it.setLastModified(1_000L * i)
                }
            }
            CameraFiles.prune(dir, 3)
            assertFalse(files[0].exists())
            assertFalse(files[1].exists())
            assertTrue(files[2].exists())
            assertTrue(files[3].exists())
            assertTrue(files[4].exists())
            assertEquals(3, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
