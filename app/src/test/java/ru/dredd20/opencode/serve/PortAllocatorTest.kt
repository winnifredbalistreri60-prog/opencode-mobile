package ru.dredd20.opencode.serve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class PortAllocatorTest {
    @Test
    fun pickFreePortIsBoundableAndNotZero() {
        val port = PortAllocator.pickFreePort()
        assertTrue(port in 1..65535)
        // The allocator releases the socket, so we can bind it (best-effort; a race is possible).
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { s ->
            assertEquals(port, s.localPort)
        }
    }

    @Test
    fun preferredPortIsUsedWhenFree() {
        val reserved = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val preferred = reserved.localPort
        reserved.close()
        val got = PortAllocator.pickFreePort("127.0.0.1", preferred)
        assertEquals(preferred, got)
    }

    @Test
    fun occupiedPreferredPortIsSkipped() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val preferred = occupied.localPort
            assertFalse(PortAllocator.isFree("127.0.0.1", preferred))
            val got = PortAllocator.pickFreePort("127.0.0.1", preferred)
            assertNotEquals(preferred, got)
            assertTrue(got in 1..65535)
        }
    }

    @Test
    fun neverHardcodes4096WhenOccupied() {
        val holder = tryBind(4096)
        try {
            val got = PortAllocator.pickFreePort("127.0.0.1", 4096)
            if (holder != null) assertNotEquals(4096, got)
            assertTrue(got in 1..65535)
        } finally {
            holder?.close()
        }
    }

    private fun tryBind(port: Int): ServerSocket? = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    } catch (_: Exception) { null }
}
