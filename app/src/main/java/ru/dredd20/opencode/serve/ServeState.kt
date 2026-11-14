package ru.dredd20.opencode.serve

import org.json.JSONObject
import java.io.File

/**
 * Persisted loopback server coordinates. The password is stored in the app-private
 * file and is never included in [toString] / log-friendly dumps.
 */
data class ServeState(
    val port: Int,
    val pid: Int,
    val password: String,
    val startedAt: Long,
    val hostname: String = "127.0.0.1",
) {
    val origin: String get() = "http://$hostname:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("port", port)
        .put("pid", pid)
        .put("password", password)
        .put("startedAt", startedAt)
        .put("hostname", hostname)

    /** Safe for logs and UI diagnostics: no password. */
    fun redact(): Map<String, Any?> = mapOf(
        "port" to port,
        "pid" to pid,
        "hostname" to hostname,
        "startedAt" to startedAt,
        "passwordLength" to password.length,
    )

    override fun toString(): String = "ServeState(port=$port, pid=$pid, hostname=$hostname, password=***)"

    companion object {
        fun parse(text: String): ServeState? = runCatching {
            val j = JSONObject(text)
            val port = j.getInt("port")
            val pid = j.getInt("pid")
            val password = j.getString("password")
            if (port !in 1..65535 || password.isBlank()) return null
            ServeState(
                port = port,
                pid = pid,
                password = password,
                startedAt = j.optLong("startedAt", 0L),
                hostname = j.optString("hostname", "127.0.0.1").ifBlank { "127.0.0.1" },
            )
        }.getOrNull()

        fun read(file: File): ServeState? =
            if (file.isFile) parse(file.readText()) else null

        fun write(file: File, state: ServeState) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(state.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(state.toJson().toString())
                tmp.delete()
            }
        }
    }
}

object ServeReuse {
    /**
     * Reuse an existing serve if the recorded pid is still alive and health
     * succeeds. A live pid on a port that now belongs to someone else is NOT reused.
     */
    fun shouldReuse(state: ServeState?, pidAlive: Boolean, healthOk: Boolean, portHolderPid: Int?): Boolean {
        if (state == null) return false
        if (!pidAlive) return false
        if (!healthOk) return false
        if (portHolderPid != null && portHolderPid != state.pid && portHolderPid > 0) return false
        return true
    }
}
