package ru.dredd20.opencode.prefs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SecretPrefsBlobTest {
    private val enc: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val dec: (String) -> ByteArray = { Base64.getDecoder().decode(it) }

    @Test
    fun roundTrip() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(40) { (it * 3).toByte() }
        val s = SecretPrefs.Blob.format(iv, ct, enc)
        assertTrue(s.startsWith("v1:"))
        val (iv2, ct2) = SecretPrefs.Blob.parse(s, dec)!!
        assertArrayEquals(iv, iv2)
        assertArrayEquals(ct, ct2)
    }

    @Test
    fun plaintextIsNotABlob() {
        assertNull(SecretPrefs.Blob.parse("hunter2", dec))
        assertNull(SecretPrefs.Blob.parse("v1:%%%:@@@", dec))
        assertNull(SecretPrefs.Blob.parse("v2:AAAA:BBBB", dec))
    }
}
