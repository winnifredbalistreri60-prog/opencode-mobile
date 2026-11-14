package ru.dredd20.opencode.serve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServeStateTest {
    @Test
    fun roundTripAndRedaction() {
        val st = ServeState(port = 41234, pid = 99, password = "s3cret-value", startedAt = 1L)
        assertFalse(st.toString().contains("s3cret-value"))
        val dump = st.redact()
        assertEquals(41234, dump["port"])
        assertEquals(st.password.length, dump["passwordLength"])
        assertFalse(dump.values.any { it.toString() == "s3cret-value" })
        val parsed = ServeState.parse(st.toJson().toString())
        assertNotNull(parsed)
        assertEquals(st.port, parsed!!.port)
        assertEquals(st.password, parsed.password)
    }

    @Test
    fun writeAndReadFile() {
        val dir = File("build/tmp-test").apply { mkdirs() }
        val f = File(dir, "serve-state.json")
        val st = ServeState(2222, 7, "abcdef", 5L)
        ServeState.write(f, st)
        val back = ServeState.read(f)!!
        assertEquals(2222, back.port)
        assertEquals("abcdef", back.password)
        f.delete()
    }

    @Test
    fun reuseRequiresLivePidAndHealthAndSameHolder() {
        val st = ServeState(1, 10, "p", 0L)
        assertTrue(ServeReuse.shouldReuse(st, pidAlive = true, healthOk = true, portHolderPid = 10))
        assertFalse(ServeReuse.shouldReuse(st, pidAlive = false, healthOk = true, portHolderPid = 10))
        assertFalse(ServeReuse.shouldReuse(st, pidAlive = true, healthOk = false, portHolderPid = 10))
        assertFalse(ServeReuse.shouldReuse(st, pidAlive = true, healthOk = true, portHolderPid = 99))
        assertFalse(ServeReuse.shouldReuse(null, pidAlive = true, healthOk = true, portHolderPid = 10))
    }
}
