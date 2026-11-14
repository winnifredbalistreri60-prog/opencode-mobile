package ru.dredd20.opencode.serve

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextPolicyTest {
    @Test
    fun loopbackNotWarned() {
        assertFalse(CleartextPolicy.shouldWarnCleartext("http://127.0.0.1:4096"))
        assertFalse(CleartextPolicy.shouldWarnCleartext("http://localhost:4096"))
        assertFalse(CleartextPolicy.shouldWarnCleartext("https://example.com"))
    }

    @Test
    fun lanAndPublicHttpWarned() {
        assertTrue(CleartextPolicy.shouldWarnCleartext("http://10.0.2.2:47124"))
        assertTrue(CleartextPolicy.shouldWarnCleartext("http://192.168.1.10:4096"))
        assertTrue(CleartextPolicy.shouldWarnCleartext("http://example.com"))
    }

    @Test
    fun privateRanges() {
        assertTrue(CleartextPolicy.isPrivateOrLinkLocal("10.1.2.3"))
        assertTrue(CleartextPolicy.isPrivateOrLinkLocal("172.16.0.1"))
        assertTrue(CleartextPolicy.isPrivateOrLinkLocal("172.31.255.1"))
        assertFalse(CleartextPolicy.isPrivateOrLinkLocal("172.15.0.1"))
        assertTrue(CleartextPolicy.isPrivateOrLinkLocal("192.168.0.5"))
        assertTrue(CleartextPolicy.isPrivateOrLinkLocal("169.254.1.1"))
        assertFalse(CleartextPolicy.isPrivateOrLinkLocal("8.8.8.8"))
        assertTrue(CleartextPolicy.isLoopback("127.0.0.1"))
    }
}
