package ru.dredd20.opencode.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {
    private val page = """
    {"servers":[
      {"server":{"name":"com.example/remote","title":"Example","description":"remote one","version":"1.0.0",
        "remotes":[{"type":"streamable-http","url":"https://mcp.example.com/mcp",
          "headers":[{"name":"Authorization","description":"Bearer key","isSecret":true,"isRequired":false}]}],
        "websiteUrl":"https://example.com","icons":[{"src":"https://example.com/i.png"}]},
       "_meta":{"io.modelcontextprotocol.registry/official":{"status":"active","isLatest":true}}},
      {"server":{"name":"io.github.x/npm-only","description":"npm","version":"0.1.0",
        "packages":[{"registryType":"npm","identifier":"@x/srv","runtimeHint":"npx","transport":{"type":"stdio"}}]}},
      {"server":{"name":"io.github.x/py","description":"py","version":"0.1.0",
        "packages":[{"registryType":"pypi","identifier":"srv","runtimeHint":"uvx"}]}},
      {"server":{"name":"ai.x/sse","description":"sse","version":"1","remotes":[{"type":"sse","url":"https://x.ai/sse"}]}},
      {"server":{"name":"ai.x/tmpl","description":"t","version":"1","remotes":[{"type":"streamable-http","url":"https://{tenant}.x.ai/mcp"}]}},
      {"server":{"name":"ai.x/docker","description":"d","version":"1","packages":[{"registryType":"oci","identifier":"x/y"}]}},
      {"server":{"name":"gone/srv","version":"1"},"_meta":{"io.modelcontextprotocol.registry/official":{"status":"deleted"}}},
      {"server":{"description":"no name"}}
    ],"metadata":{"nextCursor":"ai.x/docker:1","count":8}}
    """.trimIndent()

    @Test
    fun parsesPageAndCursor() {
        val p = Registry.parsePage(page)
        assertEquals(7, p.servers.size) // nameless entry dropped
        assertEquals("ai.x/docker:1", p.nextCursor)
        val ex = p.servers[0]
        assertEquals("Example", ex.displayName)
        assertEquals("https://mcp.example.com/mcp", ex.remotes[0].url)
        assertEquals("streamable-http", ex.remotes[0].type)
        assertTrue(ex.remotes[0].headers[0].secret)
        assertFalse(ex.remotes[0].headers[0].required)
        assertEquals("https://example.com/i.png", ex.iconUrl)
        assertEquals("npm", p.servers[1].packages[0].registryType)
        assertEquals("npx", p.servers[1].packages[0].runtimeHint)
    }

    @Test
    fun lastPageHasNoCursor() {
        assertNull(Registry.parsePage("""{"servers":[],"metadata":{"count":0}}""").nextCursor)
    }

    @Test
    fun filtersRemoteHttpOnly() {
        val p = Registry.parsePage(page).servers.associateBy { it.name }
        assertTrue(Registry.availability(p["com.example/remote"]!!) is Availability.Installable)
        for (n in listOf("io.github.x/npm-only", "io.github.x/py", "ai.x/sse", "ai.x/tmpl", "ai.x/docker")) {
            val a = Registry.availability(p[n]!!)
            assertTrue("$n must be unavailable", a is Availability.Unavailable)
            assertTrue((a as Availability.Unavailable).reason.isNotBlank())
        }
        val reasons = listOf("io.github.x/npm-only", "io.github.x/py").map { (Registry.availability(p[it]!!) as Availability.Unavailable).reason }
        assertTrue(reasons[0].contains("npx"))
        assertTrue(reasons[1].contains("uvx"))
    }

    @Test
    fun visibleDropsDeletedAndDuplicates() {
        val p = Registry.parsePage(page).servers
        val v = Registry.visible(p + p)
        assertFalse(v.any { it.name == "gone/srv" })
        assertEquals(v.size, v.map { it.name }.toSet().size)
    }

    @Test
    fun localSearch() {
        val s = Registry.parsePage(page).servers[0]
        assertTrue(Registry.matches(s, "exam"))
        assertTrue(Registry.matches(s, "REMOTE one"))
        assertFalse(Registry.matches(s, "sentry"))
        assertTrue(Registry.matches(s, ""))
    }

    @Test
    fun suggestNameIsSafeAndUnique() {
        val s = Registry.parsePage(page).servers[0]
        assertEquals("example", Registry.suggestName(s, emptySet()))
        assertEquals("example-2", Registry.suggestName(s, setOf("example")))
        val phone = s.copy(title = "Phone")
        assertEquals("phone-remote", Registry.suggestName(phone, emptySet()))
    }

    @Test
    fun remoteConfigDropsEmptyHeaders() {
        val c = Registry.remoteConfig("https://a/mcp", mapOf("Authorization" to "Bearer k", "X-Empty" to ""))
        assertEquals("remote", c.getString("type"))
        assertEquals("Bearer k", c.getJSONObject("headers").getString("Authorization"))
        assertFalse(c.getJSONObject("headers").has("X-Empty"))
        assertFalse(Registry.remoteConfig("https://a/mcp", emptyMap()).has("headers"))
    }

    @Test
    fun listUrlEncodesAndSkipsShortSearch() {
        assertEquals("${Registry.BASE}/v0.1/servers?version=latest&limit=50", Registry.listUrl(null, "ab"))
        val u = Registry.listUrl("ai.x/docker:1", "sentry mcp")
        assertTrue(u.contains("cursor=ai.x%2Fdocker%3A1"))
        assertTrue(u.contains("search=sentry+mcp"))
    }

    @Test
    fun cacheRoundTrip() {
        val s = Registry.parsePage(page).servers[0]
        val back = Registry.fromCacheJson(JSONObject(Registry.toJson(s).toString()))!!
        assertEquals(s, back)
    }

    @Test
    fun mcpStatusParsingAndWarmup() {
        val live = McpApi.parseStatus("""{"location":{"directory":"/root"},"data":[
            {"name":"phone","status":{"status":"connected"}},
            {"name":"sentry","status":{"status":"needs_auth","error":"Unauthorized"},"integrationID":"mcp_1"}]}""")
        assertEquals(2, live.size)
        assertEquals("needs_auth", live[1].status)
        assertEquals("Unauthorized", live[1].error)
        assertEquals("mcp_1", live[1].integrationID)
        val cfg = JSONObject("""{"mcp":{"servers":{"phone":{"type":"local","command":["phone-mcp"]},"ctx":{"type":"remote","url":"https://c/mcp","headers":{"K":"secret"}}}}}""")
        val merged = Installed.merge(cfg, live)
        assertEquals("phone", merged[0].name)
        assertTrue(merged[0].builtIn)
        val ctx = merged.first { it.name == "ctx" }
        assertEquals(Installed.WAITING, ctx.status)
        assertEquals(listOf("K"), ctx.headerNames)
        assertFalse("header values never surface", ctx.toString().contains("secret"))
        val sentry = merged.first { it.name == "sentry" }
        assertFalse(sentry.inConfig)
        assertTrue(Installed.warmingUp(cfg, emptyList()))
        assertFalse(Installed.warmingUp(cfg, live))
        assertFalse("unknown when server is down", Installed.warmingUp(cfg, null))
        assertEquals(setOf("https://c/mcp"), Installed.urlsInstalled(cfg))
        assertEquals(listOf("/root", "/tmp"), McpApi.parseLocations("""[{"directory":"/root"},{"directory":"/tmp"}]"""))
    }

    // ---- run5 ----

    private val smithery = """{"server":{"name":"ai.smithery/smithery-notion","description":"n","version":"1",
        "remotes":[{"type":"streamable-http","url":"https://server.smithery.ai/@smithery/notion/mcp",
          "headers":[{"name":"Authorization","description":"Bearer token","isRequired":true,"isSecret":true,
                      "value":"Bearer {smithery_api_key}"}]}]}}"""

    @Test
    fun headerTemplateIsFilled() {
        val s = Registry.parseServer(JSONObject(smithery))!!
        val h = s.remotes[0].headers[0]
        assertEquals("Bearer {smithery_api_key}", h.value)
        assertEquals(listOf("smithery_api_key"), h.variables)
        assertEquals("Bearer abc123", HeaderTemplate.fill(h, "abc123"))
        assertEquals("Bearer abc123", HeaderTemplate.fill(h, "  abc123 "))
        // The user pasted the whole value: no "Bearer Bearer".
        assertEquals("Bearer abc123", HeaderTemplate.fill(h, "Bearer abc123"))
        assertEquals("", HeaderTemplate.fill(h, ""))
        // Value survives the cache round-trip.
        val back = Registry.fromCacheJson(Registry.toJson(s))!!
        assertEquals("Bearer {smithery_api_key}", back.remotes[0].headers[0].value)
    }

    @Test
    fun headerWithoutTemplateKeepsRawInput() {
        val raw = HeaderSpec("Authorization", "", false, true, null, null)
        assertEquals("k", HeaderTemplate.fill(raw, "k"))
        val fixed = HeaderSpec("X-Mode", "", false, false, null, "readonly")
        assertEquals("readonly", HeaderTemplate.fill(fixed, ""))
        assertEquals("custom", HeaderTemplate.fill(fixed, "custom"))
        val two = HeaderSpec("X", "", false, false, null, "{a}:{b}")
        assertEquals("v", HeaderTemplate.fill(two, "v"))
    }

    @Test
    fun rewriteKeepsAuthScheme() {
        assertEquals("Bearer new", HeaderTemplate.rewrite("Bearer old", "new"))
        assertEquals("Bearer new", HeaderTemplate.rewrite("Bearer old", "Bearer new"))
        assertEquals("new", HeaderTemplate.rewrite("old", "new"))
        assertEquals("Token new", HeaderTemplate.rewrite("Token old", "new"))
    }

    @Test
    fun suggestNameSkipsGenericLastSegment() {
        val notion = Registry.parseServer(JSONObject("""{"server":{"name":"com.notion/mcp","description":"","version":"1"}}"""))!!
        assertEquals("notion", Registry.suggestName(notion, emptySet()))
        assertEquals("notion", notion.displayName)
        assertEquals("notion-2", Registry.suggestName(notion, setOf("notion")))
        val gh = Registry.parseServer(JSONObject("""{"server":{"name":"io.github.acme/server","description":"","version":"1"}}"""))!!
        assertEquals("acme", Registry.suggestName(gh, emptySet()))
        val titled = notion.copy(title = "Notion Workspace")
        assertEquals("notion-workspace", Registry.suggestName(titled, emptySet()))
        val genericTitle = notion.copy(title = "MCP")
        assertEquals("notion", Registry.suggestName(genericTitle, emptySet()))
        val smith = Registry.parseServer(JSONObject(smithery))!!
        assertEquals("smithery-notion", Registry.suggestName(smith, emptySet()))
    }
}
