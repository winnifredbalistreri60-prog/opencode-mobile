package ru.dredd20.opencode.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareArgsTest {
    @Test
    fun parseHexAcceptsSpacesAndPrefix() {
        assertArrayEquals(byteArrayOf(0x0a, 0x0b, 0xff.toByte()), HardwareArgs.parseHex("0x0A 0b ff"))
        assertArrayEquals(byteArrayOf(), HardwareArgs.parseHex("  "))
        assertEquals("0a0bff", HardwareArgs.formatHex(byteArrayOf(0x0a, 0x0b, 0xff.toByte())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseHexRejectsOddLength() {
        HardwareArgs.parseHex("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseHexRejectsJunk() {
        HardwareArgs.parseHex("zz")
    }

    @Test
    fun payloadPrefersHex() {
        assertArrayEquals(byteArrayOf(0x41), HardwareArgs.payload("hello", "41"))
        assertArrayEquals("hi".toByteArray(), HardwareArgs.payload("hi", null))
        assertArrayEquals(byteArrayOf(), HardwareArgs.payload(null, null, required = false))
    }

    @Test
    fun resolutionAndCoercion() {
        assertEquals(1280 to 720, HardwareArgs.parseResolution("1280x720"))
        assertNull(HardwareArgs.parseResolution(null))
        assertEquals(500, HardwareArgs.coerceIntervalMs(0))
        assertEquals(50, HardwareArgs.coerceIntervalMs(10))
        assertEquals(60_000, HardwareArgs.coerceIntervalMs(999_999))
        assertEquals(8, HardwareArgs.coerceRing(0))
        assertEquals(64, HardwareArgs.coerceRing(1000))
        assertEquals(5_000, HardwareArgs.coerceTimeoutMs(0, 5_000))
        assertEquals(90_000, HardwareArgs.coerceTimeoutMs(1_000_000, 5_000))
    }

    @Test
    fun patternLiteralAndRegex() {
        assertTrue(HardwareArgs.pattern("OK").containsMatchIn("ready OK\n"))
        assertTrue(HardwareArgs.pattern("a+b", literal = true).containsMatchIn("xa+by"))
    }
}
