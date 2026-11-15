package ru.dredd20.opencode.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.util.ArrayDeque

class ScriptedTransport(
    private val incoming: ArrayDeque<ByteArray>,
    override val kind: String = "fake",
    override val target: String = "dev",
    private val emptySleepMs: Long = 2,
) : HardwareTransport {
    val sent = mutableListOf<ByteArray>()
    var closed = false
    override fun send(data: ByteArray) { sent += data.copyOf() }
    override fun read(timeoutMs: Int): ByteArray {
        val next = incoming.poll()
        if (next != null) return next
        if (emptySleepMs > 0) Thread.sleep(minOf(timeoutMs, emptySleepMs.toInt()).toLong().coerceAtLeast(0))
        return ByteArray(0)
    }
    override fun close() { closed = true }
}

class HardwareSessionTest {
    @Test
    fun sendAndReadRoundTrip() {
        val t = ScriptedTransport(ArrayDeque(listOf("pong".toByteArray())))
        val log = StringWriter()
        val s = HardwareSession("s1", t, log)
        assertEquals(4, s.send("ping".toByteArray()))
        assertEquals("pong", String(s.read(100)))
        assertEquals("ping", String(t.sent.single()))
        val text = log.toString()
        assertTrue(text.contains("TX"))
        assertTrue(text.contains("RX"))
        assertTrue(text.contains("70696e67") || text.contains("ping"))
    }

    @Test
    fun expectMatchesAcrossChunksAndKeepsLeftover() {
        val t = ScriptedTransport(
            ArrayDeque(listOf("hel".toByteArray(), "lo OK rest".toByteArray())),
        )
        val s = HardwareSession("s1", t)
        val r = s.expect(Regex("OK"), 500)
        assertTrue(r.matched)
        assertEquals("hello OK", r.text)
        assertEquals(" rest", r.leftover)
        // leftover is returned by the next read without touching the transport
        assertEquals(" rest", String(s.read(10)))
    }

    @Test
    fun expectTimesOutAndPreservesBuffer() {
        val t = ScriptedTransport(ArrayDeque(listOf("nope".toByteArray())), emptySleepMs = 5)
        val s = HardwareSession("s1", t)
        val r = s.expect(Regex("READY"), 30)
        assertFalse(r.matched)
        assertTrue(r.text.contains("nope"))
        assertTrue(r.elapsedMs >= 0)
        // unmatched bytes stay pending and are returned on read
        assertEquals("nope", String(s.read(10)))
    }

    @Test
    fun storeOpenListCloseAndLogFile() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "hw-sess-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = HardwareSessionStore(logDir = dir, clock = { 42 })
            val s = store.open(ScriptedTransport(ArrayDeque()), id = "abc")
            assertEquals("abc", s.id)
            assertEquals(1, store.list().size)
            s.send("hi".toByteArray())
            val closed = store.close("abc")
            assertEquals("abc", closed["closed"])
            assertTrue(store.list().isEmpty())
            val log = store.logFile("abc")!!
            assertTrue(log.isFile)
            val body = log.readText()
            assertTrue(body.contains("OPEN fake dev"))
            assertTrue(body.contains("TX"))
            assertTrue(body.contains("CLOSE"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IllegalStateException::class)
    fun sendAfterCloseFails() {
        val s = HardwareSession("s1", ScriptedTransport(ArrayDeque()))
        s.close()
        s.send("x".toByteArray())
    }

    @Test(expected = IllegalStateException::class)
    fun missingSessionFails() {
        HardwareSessionStore().get("nope")
    }
}
