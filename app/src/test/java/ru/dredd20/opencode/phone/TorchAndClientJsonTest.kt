package ru.dredd20.opencode.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchAndClientJsonTest {
    @Test
    fun torchOffFromArgv() {
        val off = JSONObject().put("argv", JSONArray().put("off"))
        assertFalse(TorchArgs.parseOn(off, fallback = true))
        val on = JSONObject().put("argv", JSONArray().put("on"))
        assertTrue(TorchArgs.parseOn(on, fallback = false))
        val flag = JSONObject().put("on", false)
        assertFalse(TorchArgs.parseOn(flag, fallback = true))
        val empty = JSONObject()
        assertTrue(TorchArgs.parseOn(empty, fallback = true))
    }

    @Test
    fun permissionErrorIsJsonPending() {
        val j = PhoneClientJson.forResponse(
            ok = false,
            error = "android.permission.ACCESS_FINE_LOCATION",
            needsPermission = "android.permission.ACCESS_FINE_LOCATION",
            data = null,
        )
        assertEquals(PhoneClientJson.PERMISSION_PENDING, j.getString("error"))
        assertEquals("android.permission.ACCESS_FINE_LOCATION", j.getString("permission"))
        assertFalse(j.toString().startsWith("android.permission"))
    }

    @Test
    fun genericErrorIsJson() {
        val j = PhoneClientJson.forResponse(false, "no flashlight", null, null)
        assertEquals("no flashlight", j.getString("error"))
        assertFalse(j.has("permission"))
    }
}
