package ru.dredd20.opencode.plugins

import org.json.JSONArray
import org.json.JSONObject
import ru.dredd20.opencode.i18n.tr

/** One header a remote server documents (`remotes[].headers[]`). */
data class HeaderSpec(
    val name: String,
    val description: String,
    val required: Boolean,
    val secret: Boolean,
    val default: String?,
    /** Registry `value`, e.g. "Bearer {smithery_api_key}": the user's input goes into the {…} placeholder. */
    val value: String? = null,
) {
    /** Placeholder names in [value], in order, without braces. */
    val variables: List<String> get() = value?.let { HeaderTemplate.variables(it) }.orEmpty()
}

/** run5 bug 1: registry header templates ("Bearer {key}") must be filled, not replaced by the raw key. */
object HeaderTemplate {
    private val VAR = Regex("\\{([A-Za-z0-9_.-]+)\\}")

    fun variables(template: String): List<String> = VAR.findAll(template).map { it.groupValues[1] }.distinct().toList()

    /**
     * Header value to write. [input] is what the user typed. No template: input (or a fixed registry value when
     * the input is empty). One variable: the input goes into the placeholder, unless the user already typed the
     * whole value ("Bearer abc" for "Bearer {key}"). Several different variables are not fillable from one field:
     * the input is used as is.
     */
    fun fill(spec: HeaderSpec, input: String): String {
        val v = input.trim()
        val t = spec.value?.trim()
        if (t.isNullOrEmpty()) return v
        val vars = variables(t)
        if (vars.isEmpty()) return v.ifEmpty { t }
        if (v.isEmpty()) return ""
        if (vars.size > 1) return v
        val m = VAR.find(t)!!
        val prefix = t.substring(0, m.range.first)
        val suffix = t.substring(m.range.last + 1)
        val p = prefix.trim()
        if (p.isNotEmpty() && v.startsWith(p, ignoreCase = true) && v.length > p.length && v[p.length].isWhitespace() &&
            (suffix.isEmpty() || v.endsWith(suffix))) {
            return v // the user already typed the whole value
        }
        return t.replace(m.value, v)
    }

    /**
     * Changing the key of an installed server: keep the auth scheme of the old value ("Bearer ", "Token ", "Basic ")
     * when the new input is a bare key.
     */
    fun rewrite(old: String?, input: String): String {
        val v = input.trim()
        val scheme = old?.trim()?.let { Regex("^(Bearer|Token|Basic|Bot)\\s+", RegexOption.IGNORE_CASE).find(it)?.value }
        if (scheme == null || v.isEmpty()) return v
        return if (v.startsWith(scheme.trim(), ignoreCase = true) && v.length > scheme.trim().length && v[scheme.trim().length].isWhitespace()) v
        else scheme + v
    }
}

data class RemoteSpec(val type: String, val url: String, val headers: List<HeaderSpec>)

data class PackageSpec(val registryType: String, val identifier: String, val runtimeHint: String?, val transport: String?)

/** Server entry from the Official MCP Registry (`/v0.1/servers`, `server` object). */
data class RegistryServer(
    val name: String,
    val title: String?,
    val description: String,
    val version: String,
    val websiteUrl: String?,
    val repositoryUrl: String?,
    val iconUrl: String?,
    val remotes: List<RemoteSpec>,
    val packages: List<PackageSpec>,
    val status: String,
) {
    /** Title, else a readable part of the id ("com.notion/mcp" → "notion", not "mcp"). */
    val displayName: String get() = title?.takeIf { it.isNotBlank() } ?: Registry.idLabel(name)
}

sealed class Availability {
    /** Can be installed in one tap: a Streamable HTTP remote. */
    data class Installable(val remote: RemoteSpec) : Availability()
    data class Unavailable(val reason: String) : Availability()
}

data class RegistryPage(val servers: List<RegistryServer>, val nextCursor: String?)

object Registry {
    const val BASE = "https://registry.modelcontextprotocol.io"

    fun parsePage(text: String): RegistryPage {
        val root = JSONObject(text)
        val arr = root.optJSONArray("servers") ?: JSONArray()
        val out = ArrayList<RegistryServer>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val s = parseServer(item) ?: continue
            out += s
        }
        val next = root.optJSONObject("metadata")?.optString("nextCursor")?.takeIf { it.isNotBlank() && it != "null" }
        return RegistryPage(out, next)
    }

    /** Parses one list item (`{server, _meta}`) or a bare `server` object. Returns null for unusable entries. */
    fun parseServer(item: JSONObject): RegistryServer? {
        val s = item.optJSONObject("server") ?: item
        val name = s.optString("name").trim()
        if (name.isEmpty()) return null
        val meta = item.optJSONObject("_meta")?.optJSONObject("io.modelcontextprotocol.registry/official")
        val status = meta?.optString("status")?.ifBlank { null } ?: s.optString("status").ifBlank { "active" }
        val remotes = ArrayList<RemoteSpec>()
        s.optJSONArray("remotes")?.let { ra ->
            for (i in 0 until ra.length()) {
                val r = ra.optJSONObject(i) ?: continue
                val url = r.optString("url").trim()
                if (url.isEmpty()) continue
                val headers = ArrayList<HeaderSpec>()
                r.optJSONArray("headers")?.let { ha ->
                    for (j in 0 until ha.length()) {
                        val h = ha.optJSONObject(j) ?: continue
                        val hn = h.optString("name").trim()
                        if (hn.isEmpty()) continue
                        headers += HeaderSpec(
                            name = hn,
                            description = h.optString("description"),
                            required = h.optBoolean("isRequired", false),
                            secret = h.optBoolean("isSecret", false),
                            default = h.optString("default").takeIf { h.has("default") && it.isNotBlank() },
                            value = h.optString("value").takeIf { h.has("value") && it.isNotBlank() },
                        )
                    }
                }
                remotes += RemoteSpec(r.optString("type").trim().lowercase(), url, headers)
            }
        }
        val packages = ArrayList<PackageSpec>()
        s.optJSONArray("packages")?.let { pa ->
            for (i in 0 until pa.length()) {
                val p = pa.optJSONObject(i) ?: continue
                packages += PackageSpec(
                    registryType = p.optString("registryType").lowercase(),
                    identifier = p.optString("identifier"),
                    runtimeHint = p.optString("runtimeHint").ifBlank { null },
                    transport = p.optJSONObject("transport")?.optString("type")?.ifBlank { null },
                )
            }
        }
        val icon = s.optJSONArray("icons")?.optJSONObject(0)?.optString("src")?.takeIf { it.startsWith("https://") }
        return RegistryServer(
            name = name,
            title = s.optString("title").ifBlank { null },
            description = s.optString("description"),
            version = s.optString("version"),
            websiteUrl = s.optString("websiteUrl").takeIf { it.startsWith("http") },
            repositoryUrl = s.optJSONObject("repository")?.optString("url")?.takeIf { it.startsWith("http") },
            iconUrl = icon,
            remotes = remotes,
            packages = packages,
            status = status,
        )
    }

    /** What the phone can run today: Streamable HTTP remotes only (OpenCode 2.3 has no SSE client and no npx/uvx in the rootfs). */
    fun availability(s: RegistryServer): Availability {
        val http = s.remotes.firstOrNull { it.type == "streamable-http" && isPlainHttpsUrl(it.url) }
        if (http != null) return Availability.Installable(http)
        if (s.remotes.any { it.type == "streamable-http" }) {
            return Availability.Unavailable(tr(
                "Недоступно пока: адрес сервера требует параметров ({…}) или не HTTPS.",
                "Not available yet: the server URL needs parameters ({…}) or is not HTTPS.",
            ))
        }
        if (s.remotes.any { it.type == "sse" }) {
            return Availability.Unavailable(tr(
                "Недоступно пока: сервер работает только по SSE, а OpenCode 2.3 подключает удалённые MCP по Streamable HTTP.",
                "Not available yet: the server only speaks SSE, and OpenCode 2.3 connects remote MCP over Streamable HTTP.",
            ))
        }
        val types = s.packages.map { it.runtimeHint ?: it.registryType }.toSet()
        if (s.packages.any { it.registryType == "npm" || it.runtimeHint in setOf("npx", "node", "bunx") }) {
            return Availability.Unavailable(tr(
                "Недоступно пока: локальный сервер через npx/Node. Node.js в среде телефона ещё нет.",
                "Not available yet: a local server via npx/Node. Node.js is not in the phone environment yet.",
            ))
        }
        if (s.packages.any { it.registryType == "pypi" || it.runtimeHint in setOf("uvx", "uv", "pip") }) {
            return Availability.Unavailable(tr(
                "Недоступно пока: локальный сервер через uvx/PyPI. uv в среде телефона ещё нет.",
                "Not available yet: a local server via uvx/PyPI. uv is not in the phone environment yet.",
            ))
        }
        if (s.packages.any { it.registryType == "oci" }) {
            return Availability.Unavailable(tr("Недоступно: нужен Docker, на телефоне его нет.", "Not available: needs Docker, which phones do not have."))
        }
        return Availability.Unavailable(tr(
            "Недоступно: нет удалённого адреса (${types.joinToString().ifBlank { "—" }}).",
            "Not available: no remote URL (${types.joinToString().ifBlank { "—" }}).",
        ))
    }

    fun isPlainHttpsUrl(url: String): Boolean =
        url.startsWith("https://") && '{' !in url && '}' !in url && ' ' !in url

    fun isRemoteInstallable(s: RegistryServer) = availability(s) is Availability.Installable

    /** Hide deleted entries; keep the newest version per name. */
    fun visible(list: List<RegistryServer>): List<RegistryServer> =
        list.filter { it.status != "deleted" }.distinctBy { it.name }

    /** Local search for offline mode: name, title, description. */
    fun matches(s: RegistryServer, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return q.split(Regex("\\s+")).all { w ->
            s.name.lowercase().contains(w) || (s.title?.lowercase()?.contains(w) == true) || s.description.lowercase().contains(w)
        }
    }

    /** Last id segments that say nothing about the server ("com.notion/mcp" → "mcp"). */
    private val GENERIC = setOf("mcp", "server", "mcp-server", "remote", "api", "http", "sse", "streamable-http", "default", "main")

    /**
     * Default config name (run5 bug 2): the title, else the last id segment, else, when that is generic ("mcp"),
     * the segment before it ("com.notion" → "notion"). Lowercased, sanitised, never "phone", unique among [taken].
     */
    private fun clean(x: String) = x.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-', '_').take(40)

    /** Readable part of a registry id: the last segment, or the one before it when the last is generic. */
    fun idLabel(id: String): String {
        val segs = id.split('/').filter { it.isNotBlank() }
        val last = segs.lastOrNull().orEmpty()
        if (clean(last) !in GENERIC && clean(last).isNotEmpty()) return last
        // Reverse-DNS namespace: "com.notion" → "notion", "io.github.user" → "user".
        val p = segs.dropLast(1).lastOrNull().orEmpty().substringAfterLast('.')
        return p.ifEmpty { last.ifEmpty { id } }
    }

    fun suggestName(s: RegistryServer, taken: Set<String>): String {
        val fromId = clean(idLabel(s.name))
        val base = s.title?.takeIf { it.isNotBlank() }?.let { clean(it) }?.takeIf { it.isNotEmpty() && it !in GENERIC }
            ?: fromId.ifBlank { "mcp" }
        val first = if (base == "phone") "phone-remote" else base
        if (first !in taken) return first
        var i = 2
        while ("$first-$i" in taken) i++
        return "$first-$i"
    }

    /** Remote MCP config for opencode.json. `oauth` is left at the default (auto-detect) so servers with OAuth still offer «Sign in». */
    fun remoteConfig(url: String, headers: Map<String, String>): JSONObject {
        val o = JSONObject().put("type", "remote").put("url", url)
        val h = headers.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        if (h.isNotEmpty()) o.put("headers", JSONObject().apply { h.forEach { (k, v) -> put(k, v) } })
        return o
    }

    fun listUrl(cursor: String?, search: String?, limit: Int = 50): String {
        val sb = StringBuilder("$BASE/v0.1/servers?version=latest&limit=$limit")
        if (!cursor.isNullOrBlank()) sb.append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
        val q = search?.trim().orEmpty()
        if (q.length >= 3) sb.append("&search=").append(java.net.URLEncoder.encode(q, "UTF-8"))
        return sb.toString()
    }

    // ---- cache (JSON, app-private) ----

    fun toJson(s: RegistryServer): JSONObject = JSONObject()
        .put("name", s.name).put("title", s.title ?: JSONObject.NULL).put("description", s.description)
        .put("version", s.version).put("websiteUrl", s.websiteUrl ?: JSONObject.NULL)
        .put("repositoryUrl", s.repositoryUrl ?: JSONObject.NULL).put("iconUrl", s.iconUrl ?: JSONObject.NULL)
        .put("status", s.status)
        .put("remotes", JSONArray().apply {
            s.remotes.forEach { r ->
                put(JSONObject().put("type", r.type).put("url", r.url).put("headers", JSONArray().apply {
                    r.headers.forEach { h ->
                        put(JSONObject().put("name", h.name).put("description", h.description).put("isRequired", h.required)
                            .put("isSecret", h.secret).apply { if (h.default != null) put("default", h.default); if (h.value != null) put("value", h.value) })
                    }
                }))
            }
        })
        .put("packages", JSONArray().apply {
            s.packages.forEach { p ->
                put(JSONObject().put("registryType", p.registryType).put("identifier", p.identifier)
                    .apply { p.runtimeHint?.let { put("runtimeHint", it) }; p.transport?.let { put("transport", JSONObject().put("type", it)) } })
            }
        })

    fun fromCacheJson(o: JSONObject): RegistryServer? {
        // Same shape as the registry `server` object, plus status. Nulls come back as "null" strings in org.json.
        fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).ifBlank { null }
        val base = parseServer(JSONObject().put("server", o)) ?: return null
        return base.copy(
            title = o.str("title"), websiteUrl = o.str("websiteUrl"), repositoryUrl = o.str("repositoryUrl"),
            iconUrl = o.str("iconUrl"), status = o.optString("status", "active"),
        )
    }
}
