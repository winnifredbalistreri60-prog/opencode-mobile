package ru.dredd20.opencode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {
    private val label: (Int) -> String = { "port $it" }

    @Test
    fun portNotRepeated() {
        assertEquals("Server listening on 127.0.0.1:34183", StatusText.embedded("Server listening on 127.0.0.1:34183", 34183, label))
        assertEquals("Сервер уже запущен на порту 34183", StatusText.embedded("Сервер уже запущен на порту 34183", 34183, label))
    }

    @Test
    fun portAddedWhenMissing() {
        assertEquals("Starting… · port 34183", StatusText.embedded("Starting…", 34183, label))
        assertEquals("port 4096", StatusText.embedded("", 4096, label))
        assertNull(StatusText.embedded(" ", null, label))
        // 4096 inside 40960 is not the same port
        assertTrue(StatusText.embedded("on :40960", 4096, label)!!.endsWith("port 4096"))
        assertFalse(StatusText.mentionsPort("x:40960", 4096))
    }

    @Test
    fun setupErrorShownOnce() {
        val err = "cannot download opencode (retry resumes)"
        assertEquals("35%", StatusText.setupProgress(35, err, err))
        assertEquals("35%", StatusText.setupProgress(35, "", err))
        assertEquals("35%  Downloading", StatusText.setupProgress(35, "Downloading", null))
        assertEquals("35%  Unpacking", StatusText.setupProgress(35, "Unpacking", "network is unreachable"))
    }
}
