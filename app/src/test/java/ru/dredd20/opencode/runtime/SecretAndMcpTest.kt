package ru.dredd20.opencode.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dredd20.opencode.prefs.AppPrefs
import ru.dredd20.opencode.phone.PhoneProtocol
import ru.dredd20.opencode.serve.AgentEventWatcher
import java.io.File

class SecretAndMcpTest {
    @Test
    fun serveCommandHasNoPasswordPlaceholder() {
        val cmd = SecretFiles.serveCommand(35193)
        assertTrue(cmd.contains("/run/opencode-password"))
        assertTrue(cmd.contains("opencode serve"))
        assertTrue(cmd.contains("--port 35193"))
        assertFalse(cmd.contains("OPENCODE_PASSWORD="))
    }

    @Test
    fun envFileBodyIsSingleAssignment() {
        assertEquals("OPENCODE_PASSWORD=abc-DEF_123\n", SecretFiles.envFileBody("OPENCODE_PASSWORD", "abc-DEF_123"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun guestEnvRejectsPasswordInArgv() {
        ProotCommand.guestEnv(mapOf("OPENCODE_PASSWORD" to "secret-value"))
    }

    @Test
    fun guestEnvDoesNotContainPasswordByDefault() {
        val argv = ProotCommand.guestEnv()
        assertFalse(argv.any { it.startsWith("OPENCODE_PASSWORD=") })
    }

    @Test
    fun noSeccompOnlyWhenFlagFileWouldExist() {
        // Documented policy: the env flag is set iff the noseccomp file exists (MURF).
        val l = ProotLayout("/lib", "/rootfs", "/host", "/tmpx", "/fake", "/runx")
        assertFalse(ProotCommand.env(l, noSeccomp = false, hasLoader32 = false).containsKey("PROOT_NO_SECCOMP"))
        assertEquals("1", ProotCommand.env(l, noSeccomp = true, hasLoader32 = false)["PROOT_NO_SECCOMP"])
    }

    @Test
    fun mcpConfigCommandIsArray() {
        val j = JSONObject(GuestFiles.openCodeConfigJson())
        val cmd = j.getJSONObject("mcp").getJSONObject("servers").getJSONObject("phone").getJSONArray("command")
        assertEquals(1, cmd.length())
        assertEquals("phone-mcp", cmd.getString(0))
        assertEquals("local", j.getJSONObject("mcp").getJSONObject("servers").getJSONObject("phone").getString("type"))
    }

    @Test
    fun phoneMcpSpeaksLineJsonRpc() {
        val script = File("src/main/assets/phone/phone-mcp.py")
        assertTrue("phone-mcp.py missing at ${script.absolutePath}", script.isFile)
        val src = script.readText()
        val writeFn = src.split("def write_msg")[1].split("def handle")[0]
        assertFalse("must write one JSON line", "sys.stdout.write(\"Content-Length" in writeFn)
        assertTrue("newline-delimited JSON", "json.dumps" in writeFn)
        val pb = ProcessBuilder("python3", script.absolutePath)
        pb.redirectErrorStream(true)
        val p = pb.start()
        val req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}\n"
        p.outputStream.write(req.toByteArray())
        p.outputStream.flush()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor()
        assertEquals("phone-mcp exited $ok: $out", 0, ok)
        val line = out.lineSequence().first { it.isNotBlank() }
        assertFalse(line.startsWith("Content-Length"))
        val j = JSONObject(line)
        assertEquals("2.0", j.getString("jsonrpc"))
        assertEquals(1, j.getInt("id"))
        assertEquals("phone-mcp", j.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
        assertEquals("0.3.0", j.getJSONObject("result").getJSONObject("serverInfo").getString("version"))
    }

    @Test
    fun phoneMcpListsHardwareTools() {
        val script = File("src/main/assets/phone/phone-mcp.py")
        val pb = ProcessBuilder("python3", script.absolutePath)
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.outputStream.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}\n".toByteArray())
        p.outputStream.flush()
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        assertEquals("phone-mcp tools/list exited $code: $out", 0, code)
        val line = out.lineSequence().first { it.isNotBlank() }
        val tools = JSONObject(line).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
        for (n in listOf("usb_hid_read", "hw_session_expect", "bluetooth_scan", "bluetooth_spp_connect",
                "wifi_scan", "wifi_connect", "net_mdns", "nfc_read", "camera_start", "camera_latest")) {
            assertTrue("missing tool $n in $names", n in names)
        }
    }

    @Test
    fun categoryDefaultsAndDialNotComms() {
        assertFalse(AppPrefs.categoryDefault(AppPrefs.CAT_LOCATION))
        assertFalse(AppPrefs.categoryDefault(AppPrefs.CAT_CAMERA))
        assertFalse(AppPrefs.categoryDefault(AppPrefs.CAT_CLIPBOARD))
        assertFalse(AppPrefs.categoryDefault(AppPrefs.CAT_COMMS))
        assertTrue(AppPrefs.categoryDefault(AppPrefs.CAT_SENSORS))
        assertTrue(AppPrefs.categoryDefault(AppPrefs.CAT_BLUETOOTH))
        assertTrue(AppPrefs.categoryDefault(AppPrefs.CAT_WIFI))
        assertTrue(AppPrefs.categoryDefault(AppPrefs.CAT_HARDWARE))
        assertFalse(AppPrefs.categoryDefault(AppPrefs.CAT_NFC))
        assertEquals("system", PhoneProtocol.categoryFor("telephony-call"))
        assertEquals("comms", PhoneProtocol.categoryFor("sms-send"))
        assertFalse(PhoneProtocol.DANGEROUS.contains("telephony-call"))
    }

    @Test
    fun agentEventType() {
        assertEquals("session.idle", AgentEventWatcher.eventType("""{"type":"session.idle","properties":{"sessionID":"s"}}"""))
        assertEquals("session.execution.succeeded", AgentEventWatcher.eventType("""{"type":"session.execution.succeeded"}"""))
        assertTrue("session.idle" in AgentEventWatcher.NOTIFY_TYPES)
    }
}
