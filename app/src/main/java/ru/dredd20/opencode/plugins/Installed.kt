package ru.dredd20.opencode.plugins

import org.json.JSONObject
import ru.dredd20.opencode.runtime.OpenCodeConfig

data class InstalledServer(
    val name: String,
    val type: String,
    val target: String,
    val builtIn: Boolean,
    val inConfig: Boolean,
    val status: String,
    val error: String?,
    val title: String?,
    val integrationID: String?,
    val headerNames: List<String>,
)

object Installed {
    const val WAITING = "waiting"

    /**
     * Join servers from opencode.json with live statuses. Right after start `GET /api/mcp` is empty for a few
     * seconds; a configured server missing from the response is then shown as [WAITING], not as an error.
     */
    fun merge(config: JSONObject?, live: List<McpStatus>?): List<InstalledServer> {
        val servers = config?.let { OpenCodeConfig.servers(it) }
        val byName = live.orEmpty().associateBy { it.name }
        val out = ArrayList<InstalledServer>()
        val names = LinkedHashSet<String>()
        servers?.keys()?.forEach { names += it }
        live?.forEach { names += it.name }
        for (n in names) {
            val c = servers?.optJSONObject(n)
            val s = byName[n]
            val type = c?.optString("type")?.ifBlank { null } ?: if (n == OpenCodeConfig.PHONE_SERVER) "local" else "remote"
            val target = when {
                c == null -> ""
                type == "local" -> c.optJSONArray("command")?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }.orEmpty()
                else -> c.optString("url")
            }
            val disabled = c?.optBoolean("disabled", false) == true
            out += InstalledServer(
                name = n,
                type = type,
                target = target,
                builtIn = n == OpenCodeConfig.PHONE_SERVER,
                inConfig = c != null,
                status = s?.status ?: if (disabled) "disabled" else WAITING,
                error = s?.error,
                title = s?.title,
                integrationID = s?.integrationID,
                headerNames = c?.optJSONObject("headers")?.keys()?.asSequence()?.toList().orEmpty(),
            )
        }
        // Built-in first, then by name.
        return out.sortedWith(compareBy<InstalledServer>({ !it.builtIn }, { it.name }))
    }

    /** True while the server still warms up: configured servers but nothing in the live list. */
    fun warmingUp(config: JSONObject?, live: List<McpStatus>?): Boolean {
        val n = config?.let { OpenCodeConfig.servers(it)?.length() } ?: 0
        return live != null && live.isEmpty() && n > 0
    }

    fun urlsInstalled(config: JSONObject?): Set<String> {
        val servers = config?.let { OpenCodeConfig.servers(it) } ?: return emptySet()
        return servers.keys().asSequence().mapNotNull { servers.optJSONObject(it)?.optString("url")?.ifBlank { null } }.toSet()
    }
}
