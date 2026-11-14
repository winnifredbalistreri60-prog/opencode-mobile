package ru.dredd20.opencode.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class PhoneProtocolTest {
    @Test
    fun parseValidRequest() {
        val req = PhoneProtocol.parse("""{"v":1,"id":"a","token":"t","cmd":"battery-status","args":{"n":2}}""")
        assertEquals("a", req!!.id)
        assertEquals("t", req.token)
        assertEquals("battery-status", req.cmd)
        assertEquals(2, req.argInt("n", 0))
    }

    @Test
    fun parseRejectsJunk() {
        assertNull(PhoneProtocol.parse(""))
        assertNull(PhoneProtocol.parse("{"))
        assertNull(PhoneProtocol.parse("""{"id":"1"}"""))
    }

    @Test
    fun tokenComparison() {
        assertTrue(PhoneProtocol.tokenOk("abc", "abc"))
        assertFalse(PhoneProtocol.tokenOk("", "abc"))
        assertFalse(PhoneProtocol.tokenOk("abc", ""))
        assertFalse(PhoneProtocol.tokenOk("abc", "xyz"))
    }

    @Test
    fun categoriesAndDangerous() {
        assertEquals("comms", PhoneProtocol.categoryFor("sms-send"))
        assertEquals("camera", PhoneProtocol.categoryFor("camera-photo"))
        assertEquals("system", PhoneProtocol.categoryFor("telephony-call"))
        assertFalse("telephony-call" in PhoneProtocol.DANGEROUS)
        assertTrue("battery-status" in PhoneProtocol.COMMANDS)
        assertFalse("eval" in PhoneProtocol.COMMANDS)
    }

    @Test
    fun responseJson() {
        val ok = PhoneResponse.ok("1", mapOf("percentage" to 80)).toJson()
        assertTrue(ok.getBoolean("ok"))
        assertEquals(80, ok.getJSONObject("data").getInt("percentage"))
        val err = PhoneResponse.error("1", "nope", needsPermission = "android.permission.CAMERA").toJson()
        assertFalse(err.getBoolean("ok"))
        assertEquals("android.permission.CAMERA", err.getString("needs_permission"))
    }

    @Test
    fun textFromArgv() {
        val req = PhoneRequest("1", "t", "toast", JSONObject().put("argv", org.json.JSONArray().put("hello").put("world")))
        assertEquals("hello world", req.text())
    }
}
