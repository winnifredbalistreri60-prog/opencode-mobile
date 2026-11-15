package ru.dredd20.opencode.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OpenCodeConfigTest {
    private fun servers(text: String) = JSONObject(text).getJSONObject("mcp").getJSONObject("servers")

    @Test
    fun emptyOrMissingGivesTemplate() {
        for (old in listOf(null, "", "  \n")) {
            val r = OpenCodeConfig.merge(old)
            assertFalse(r.backupOld)
            assertTrue(r.changed)
            assertEquals("disable", JSONObject(r.json).getString("update"))
            assertEquals("phone-mcp", servers(r.json).getJSONObject("phone").getJSONArray("command").getString(0))
        }
    }

    @Test
    fun brokenJsonIsBackedUpNotDropped() {
        val r = OpenCodeConfig.merge("{ \"mcp\": { oops ")
        assertTrue(r.backupOld)
        assertTrue(servers(r.json).has("phone"))
        val arr = OpenCodeConfig.merge("[1,2,3]")
        assertTrue("non-object root is treated as broken", arr.backupOld)
    }

    @Test
    fun brokenFileKeepsBackupOnDisk() {
        val dir = Files.createTempDirectory("occfg").toFile()
        val f = File(dir, "opencode.json")
        f.writeText("not json at all")
        val backup = OpenCodeConfig.mergeFile(f, now = 42L)
        assertNotNull(backup)
        assertEquals("opencode.json.broken-42.bak", backup!!.name)
        assertEquals("not json at all", backup.readText())
        assertTrue(servers(f.readText()).has("phone"))
        dir.deleteRecursively()
    }

    @Test
    fun userServersPluginsAndSettingsSurvive() {
        val old = """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "model": "anthropic/claude-x",
              "plugins": ["opencode-acme-plugin@1.2.0", {"package": "@x/y", "options": {"a": 1}}],
              "mcp": { "servers": {
                "context7": { "type": "remote", "url": "https://mcp.context7.com/mcp", "headers": { "Authorization": "Bearer s3cret" } },
                "local1": { "type": "local", "command": ["npx", "-y", "srv"], "disabled": true }
              } },
              "provider": { "x": { "options": { "baseURL": "http://h" } } }
            }
        """.trimIndent()
        val r = OpenCodeConfig.merge(old)
        assertFalse(r.backupOld)
        val j = JSONObject(r.json)
        assertEquals("anthropic/claude-x", j.getString("model"))
        assertEquals(2, j.getJSONArray("plugins").length())
        assertEquals("https://opencode.ai/config.json", j.getString("\$schema"))
        assertEquals("http://h", j.getJSONObject("provider").getJSONObject("x").getJSONObject("options").getString("baseURL"))
        val s = servers(r.json)
        assertEquals("Bearer s3cret", s.getJSONObject("context7").getJSONObject("headers").getString("Authorization"))
        assertTrue(s.getJSONObject("local1").getBoolean("disabled"))
        assertTrue(s.has("phone"))
        assertEquals("disable", j.getString("update"))
        assertFalse("no escaped slashes", r.json.contains("\\/"))
    }

    @Test
    fun ourKeyIsUpdatedButUserFieldsOnItStay() {
        val old = """{"update":"latest","mcp":{"servers":{"phone":{"type":"remote","url":"https://x","command":["old-phone"],"disabled":true,"timeout":{"startup":5000}}}}}"""
        val r = OpenCodeConfig.merge(old)
        assertTrue(r.changed)
        val j = JSONObject(r.json)
        assertEquals("disable", j.getString("update"))
        val phone = servers(r.json).getJSONObject("phone")
        assertEquals("local", phone.getString("type"))
        assertEquals("phone-mcp", phone.getJSONArray("command").getString(0))
        assertEquals(1, phone.getJSONArray("command").length())
        assertFalse(phone.has("url"))
        assertTrue(phone.getBoolean("disabled"))
        assertEquals(5000, phone.getJSONObject("timeout").getInt("startup"))
    }

    @Test
    fun unchangedWhenAlreadyMerged() {
        val first = OpenCodeConfig.merge(null).json
        val again = OpenCodeConfig.merge(first)
        assertFalse(again.changed)
        assertEquals(first, again.json)
    }

    @Test
    fun mcpWithoutServersGetsServers() {
        val r = OpenCodeConfig.merge("""{"mcp":{}}""")
        assertTrue(servers(r.json).has("phone"))
    }

    @Test
    fun putAndRemoveServer() {
        val base = OpenCodeConfig.merge("""{"plugins":["a"]}""").json
        val withSrv = OpenCodeConfig.putServer(base, "context7", JSONObject().put("type", "remote").put("url", "https://mcp.context7.com/mcp"))
        assertEquals("https://mcp.context7.com/mcp", servers(withSrv).getJSONObject("context7").getString("url"))
        assertEquals(1, JSONObject(withSrv).getJSONArray("plugins").length())
        val removed = OpenCodeConfig.removeServer(withSrv, "context7")!!
        assertFalse(servers(removed).has("context7"))
        assertTrue(servers(removed).has("phone"))
        assertNull("phone is never removed", OpenCodeConfig.removeServer(removed, "phone"))
        assertNull("missing server: no change", OpenCodeConfig.removeServer(removed, "nope"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotReplacePhone() {
        OpenCodeConfig.putServer(null, "phone", JSONObject().put("type", "remote").put("url", "https://x"))
    }

    @Test
    fun namesAreValidated() {
        assertTrue(OpenCodeConfig.isValidName("context7"))
        assertTrue(OpenCodeConfig.isValidName("my_srv-2"))
        assertFalse(OpenCodeConfig.isValidName(""))
        assertFalse(OpenCodeConfig.isValidName("-x"))
        assertFalse(OpenCodeConfig.isValidName("a/b"))
        assertFalse(OpenCodeConfig.isValidName("a b"))
    }

    @Test
    fun guestFilesTemplateMatches() {
        val j = JSONObject(GuestFiles.openCodeConfigJson())
        assertEquals("phone-mcp", servers(j.toString()).getJSONObject("phone").getJSONArray("command").getString(0))
    }
}
