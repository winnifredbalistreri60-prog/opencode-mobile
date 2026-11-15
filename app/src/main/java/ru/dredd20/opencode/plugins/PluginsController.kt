package ru.dredd20.opencode.plugins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.runtime.OpenCodeConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * State and actions of the «Plugins» screen. Catalog: Official MCP Registry with a local cache for offline use.
 * Install = merge into opencode.json (embedded mode) + PUT /api/experimental/mcp/:name for an immediate connect.
 * Header values (API keys) live only in opencode.json in app-private storage; they are never logged.
 */
class PluginsController(
    private val cacheDir: File,
    private val scope: CoroutineScope,
    /** Guest opencode.json, or null when the server config is not ours (Termux, remote). */
    private val configFile: () -> File?,
    private val conn: () -> ServerConn?,
    private val log: (String) -> Unit = {},
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    // Catalog
    var catalog by mutableStateOf<List<RegistryServer>>(emptyList()); private set
    var catalogLoading by mutableStateOf(false); private set
    var catalogError by mutableStateOf<String?>(null); private set
    var offline by mutableStateOf(false); private set
    var cachedAt by mutableStateOf(0L); private set
    var nextCursor by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(""); private set
    var onlyAvailable by mutableStateOf(true)

    // Installed
    var installed by mutableStateOf<List<InstalledServer>>(emptyList()); private set
    var warming by mutableStateOf(false); private set
    var serverReachable by mutableStateOf(true); private set
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var installedUrls by mutableStateOf<Set<String>>(emptySet()); private set

    val persistent: Boolean get() = configFile() != null

    private var searchJob: Job? = null
    private var pollJob: Job? = null
    private val cacheFile get() = File(cacheDir, "registry-cache.json")

    fun start() {
        if (catalog.isEmpty()) loadCatalog(reset = true)
        refreshInstalled(poll = true)
    }

    fun updateQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(400)
            loadCatalog(reset = true)
        }
    }

    fun loadMore() {
        if (nextCursor != null && !catalogLoading && !offline) loadCatalog(reset = false)
    }

    fun retry() = loadCatalog(reset = true)

    private fun loadCatalog(reset: Boolean) {
        val q = query.trim()
        val cursor = if (reset) null else nextCursor
        catalogLoading = true
        catalogError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Short queries (< 3 chars) are rejected by the registry: list and filter locally.
                    var page = fetch(Registry.listUrl(cursor, q.takeIf { it.length >= 3 }))
                    var items = page.servers
                    if (q.isNotEmpty() && q.length < 3) items = items.filter { Registry.matches(it, q) }
                    page = page.copy(servers = items)
                    saveCache(page.servers)
                    page
                }
            }
            result.onSuccess { page ->
                offline = false
                val merged = if (reset) page.servers else catalog + page.servers
                catalog = Registry.visible(merged)
                nextCursor = page.nextCursor
                cachedAt = System.currentTimeMillis()
            }.onFailure { e ->
                val cached = withContext(Dispatchers.IO) { readCache() }
                if (cached != null && (reset || catalog.isEmpty())) {
                    offline = true
                    cachedAt = cached.first
                    catalog = Registry.visible(cached.second.filter { Registry.matches(it, q) })
                    nextCursor = null
                }
                catalogError = tr("Каталог недоступен: ", "Catalog unavailable: ") + (e.message ?: e.javaClass.simpleName)
            }
            catalogLoading = false
        }
    }

    private fun fetch(url: String): RegistryPage {
        val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "OpenCode-Mobile").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return Registry.parsePage(r.body.string())
        }
    }

    @Synchronized
    private fun saveCache(items: List<RegistryServer>) {
        if (items.isEmpty()) return
        val old = readCache()?.second.orEmpty()
        val byName = LinkedHashMap<String, RegistryServer>()
        old.forEach { byName[it.name] = it }
        items.forEach { byName[it.name] = it }
        val list = byName.values.toList().takeLast(MAX_CACHE)
        val arr = JSONArray(); list.forEach { arr.put(Registry.toJson(it)) }
        cacheDir.mkdirs()
        val tmp = File(cacheFile.path + ".tmp")
        tmp.writeText(JSONObject().put("savedAt", System.currentTimeMillis()).put("servers", arr).toString())
        if (!tmp.renameTo(cacheFile)) { cacheFile.writeText(tmp.readText()); tmp.delete() }
    }

    private fun readCache(): Pair<Long, List<RegistryServer>>? = runCatching {
        if (!cacheFile.isFile) return null
        val o = JSONObject(cacheFile.readText())
        val arr = o.getJSONArray("servers")
        o.optLong("savedAt") to (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Registry::fromCacheJson) }
    }.getOrNull()

    // ---- installed ----

    private fun readConfig(): JSONObject? = configFile()?.takeIf { it.isFile }?.let { OpenCodeConfig.parse(it.readText()) }

    fun refreshInstalled(poll: Boolean = false) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tries = if (poll) 20 else 1
            while (tries-- > 0) {
                val (cfg, live) = withContext(Dispatchers.IO) {
                    val cfg = runCatching { readConfig() }.getOrNull()
                    val live = conn()?.let { c -> runCatching { McpApi(c).status() }.getOrNull() }
                    cfg to live
                }
                serverReachable = live != null
                installed = Installed.merge(cfg, live)
                installedUrls = Installed.urlsInstalled(cfg) + installed.filter { it.type == "remote" }.map { it.target }.toSet()
                warming = Installed.warmingUp(cfg, live)
                val transient = installed.any { it.status == Installed.WAITING || it.status == "pending" }
                if (!poll || (!warming && !transient && live != null)) break
                delay(2_000)
            }
        }
    }

    /** One-tap install. [name] is the config key; [headers] values are secrets and are never logged. */
    fun install(server: RegistryServer, remote: RemoteSpec, name: String, headers: Map<String, String>, onDone: (String?) -> Unit = {}) {
        if (busy != null) return
        val n = name.trim()
        if (!OpenCodeConfig.isValidName(n) || n == OpenCodeConfig.PHONE_SERVER) {
            onDone(tr("Имя: латиница, цифры, - и _, до 64 символов, не «phone».", "Name: letters, digits, - and _, up to 64 chars, not “phone”."))
            return
        }
        val missing = remote.headers.filter { it.required && headers[it.name].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            onDone(tr("Заполните: ", "Fill in: ") + missing.joinToString { it.name })
            return
        }
        busy = n
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    val cfg = Registry.remoteConfig(remote.url, headers)
                    configFile()?.let { f ->
                        val old = if (f.isFile) f.readText() else null
                        OpenCodeConfig.writeAtomic(f, OpenCodeConfig.putServer(old, n, cfg))
                    }
                    val c = conn()
                    val apiErr = c?.let { McpApi(it).add(n, cfg) }
                    log("plugins: install ${server.name} as $n (headers: ${headers.keys.joinToString()}) api=${apiErr ?: if (c == null) "no server" else "ok"}")
                    when {
                        c == null && configFile() != null -> null // saved; connects on the next start
                        c == null -> tr("Сервер OpenCode не запущен", "The OpenCode server is not running")
                        else -> apiErr
                    }
                }.getOrElse { e -> e.message ?: e.javaClass.simpleName }
            }
            busy = null
            message = err ?: tr("Установлен «$n»", "Installed “$n”")
            onDone(err)
            refreshInstalled(poll = true)
        }
    }

    fun uninstall(name: String) {
        if (name == OpenCodeConfig.PHONE_SERVER || busy != null) return
        busy = name
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    configFile()?.let { f ->
                        val upd = OpenCodeConfig.removeServer(if (f.isFile) f.readText() else null, name)
                        if (upd != null) OpenCodeConfig.writeAtomic(f, upd)
                    }
                    val e = conn()?.let { McpApi(it).remove(name) }
                    log("plugins: remove $name api=${e ?: "ok"}")
                    e
                }.getOrElse { e -> e.message ?: e.javaClass.simpleName }
            }
            busy = null
            message = err?.let { tr("Удалено из конфига, но сервер ответил: $it", "Removed from config, server said: $it") }
                ?: tr("Удалён «$name»", "Removed “$name”")
            refreshInstalled(poll = false)
        }
    }

    companion object {
        const val MAX_CACHE = 3000
    }
}
