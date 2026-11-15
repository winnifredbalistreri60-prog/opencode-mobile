package ru.dredd20.opencode.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Careful edits of the guest `opencode.json` (pure logic, unit-tested).
 *
 * The app owns only a few keys: `update` ("disable", the APK updates OpenCode itself) and
 * `mcp.servers.phone` (built-in Phone API MCP). Everything else (user MCP servers, `plugins`,
 * providers, models, …) is preserved. A file that does not parse is backed up, never dropped.
 */
object OpenCodeConfig {
    const val PHONE_SERVER = "phone"
    const val FILE_NAME = "opencode.json"

    data class MergeResult(
        val json: String,
        /** True when the old text was not valid JSON and must be kept as a backup before writing. */
        val backupOld: Boolean,
        /** False when the existing file already had our keys; the caller may skip the write. */
        val changed: Boolean,
    )

    fun phoneServer(): JSONObject = JSONObject()
        .put("type", "local")
        .put("command", JSONArray().put("phone-mcp"))

    fun template(): JSONObject = JSONObject()
        .put("update", "disable")
        .put("mcp", JSONObject().put("servers", JSONObject().put(PHONE_SERVER, phoneServer())))

    /** Parse the config, or null when it is not a JSON object. */
    fun parse(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val t = org.json.JSONTokener(text).nextValue()
            t as? JSONObject
        }.getOrNull()
    }

    /** Merge our keys into [existing] (file text or null when missing). */
    fun merge(existing: String?): MergeResult {
        if (existing.isNullOrBlank()) return MergeResult(render(template()), backupOld = false, changed = true)
        val root = parse(existing) ?: return MergeResult(render(template()), backupOld = true, changed = true)
        val before = render(root)
        ensureOurKeys(root)
        val after = render(root)
        return MergeResult(after, backupOld = false, changed = before != after)
    }

    private fun ensureOurKeys(root: JSONObject) {
        root.put("update", "disable")
        val mcp = root.optJSONObject("mcp") ?: JSONObject().also { root.put("mcp", it) }
        val servers = mcp.optJSONObject("servers") ?: JSONObject().also { mcp.put("servers", it) }
        // Keep user fields on our server (disabled, timeout, …) but refresh what we own.
        val phone = servers.optJSONObject(PHONE_SERVER) ?: JSONObject()
        val ours = phoneServer()
        for (k in ours.keys()) phone.put(k, ours.get(k))
        // A remote-only key would make the local server invalid.
        for (k in listOf("url", "headers", "oauth")) phone.remove(k)
        servers.put(PHONE_SERVER, phone)
    }

    fun servers(root: JSONObject): JSONObject? = root.optJSONObject("mcp")?.optJSONObject("servers")

    /** Add or replace a user server. The built-in phone server cannot be replaced. */
    fun putServer(text: String?, name: String, server: JSONObject): String {
        require(isValidName(name)) { "bad server name" }
        require(name != PHONE_SERVER) { "phone is built-in" }
        val root = parse(text) ?: template()
        ensureOurKeys(root)
        servers(root)!!.put(name, server)
        return render(root)
    }

    /** Remove a user server. Returns null when nothing changed. The phone server is never removed. */
    fun removeServer(text: String?, name: String): String? {
        if (name == PHONE_SERVER) return null
        val root = parse(text) ?: return null
        val servers = servers(root) ?: return null
        if (!servers.has(name)) return null
        servers.remove(name)
        return render(root)
    }

    fun isValidName(name: String) = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))

    fun render(root: JSONObject): String = root.toString(2).replace("\\/", "/") + "\n"

    // ---- file helpers (private storage, 0600) ----

    fun configFile(rootfs: File) = File(rootfs, "root/.config/opencode/$FILE_NAME")

    /** Merge our keys into the guest file. Returns the backup file when a broken config was moved aside. */
    fun mergeFile(file: File, now: Long = System.currentTimeMillis()): File? {
        file.parentFile?.mkdirs()
        val old = if (file.isFile) file.readText() else null
        val r = merge(old)
        var backup: File? = null
        if (r.backupOld && old != null) {
            backup = File(file.parentFile, "$FILE_NAME.broken-$now.bak")
            backup.writeText(old)
            restrict(backup)
        }
        if (r.changed || !file.isFile) writeAtomic(file, r.json)
        return backup
    }

    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        restrict(tmp)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        restrict(file)
    }

    /** Header values may be secrets: owner-only access. */
    private fun restrict(f: File) {
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
    }
}
