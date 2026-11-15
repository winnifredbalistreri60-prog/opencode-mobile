package ru.dredd20.opencode.plugins

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ServerConn(val origin: String, val user: String, val password: String)

data class McpStatus(val name: String, val status: String, val error: String?, val title: String?, val integrationID: String?)

/**
 * OpenCode 2.3 MCP HTTP API. `PUT/DELETE /api/experimental/mcp/:server` act on one loaded location and only
 * until restart, so the caller also writes opencode.json. Request bodies may carry header secrets: never log them.
 */
class McpApi(private val conn: ServerConn, private val client: OkHttpClient = defaultClient()) {
    private val json = "application/json".toMediaType()

    private fun req(path: String) = Request.Builder()
        .url(conn.origin.trimEnd('/') + path)
        .header("Authorization", Credentials.basic(conn.user, conn.password))
        .header("Accept", "application/json")

    fun status(): List<McpStatus> {
        client.newCall(req("/api/mcp").get().build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return parseStatus(r.body.string())
        }
    }

    /** Directories of locations the server has loaded (sessions in other projects). Empty when unknown. */
    fun loadedLocations(): List<String> = runCatching {
        client.newCall(req("/api/debug/location").get().build()).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            parseLocations(r.body.string())
        }
    }.getOrDefault(emptyList())

    /** Connect now in the default location and every loaded one. Returns the first HTTP error, or null on success. */
    fun add(name: String, config: JSONObject): String? {
        val body = JSONObject().put("config", config).toString().toRequestBody(json)
        var err: String? = null
        for (q in locationQueries()) {
            client.newCall(req("/api/experimental/mcp/" + enc(name) + q).put(body).build()).execute().use { r ->
                if (!r.isSuccessful && err == null) err = "HTTP ${r.code}: " + r.body.string().take(200)
            }
        }
        return err
    }

    fun remove(name: String): String? {
        var err: String? = null
        for (q in locationQueries()) {
            client.newCall(req("/api/experimental/mcp/" + enc(name) + q).delete().build()).execute().use { r ->
                if (!r.isSuccessful && r.code != 404 && err == null) err = "HTTP ${r.code}"
            }
        }
        return err
    }

    private fun locationQueries(): List<String> =
        listOf("") + loadedLocations().distinct().map { "?" + enc("location[directory]") + "=" + enc(it) }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun parseStatus(text: String): List<McpStatus> {
            val root = JSONObject(text)
            val arr = root.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<McpStatus>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val st = o.optJSONObject("status")
                val status = st?.optString("status") ?: o.optString("status")
                out += McpStatus(
                    name = o.optString("name"),
                    status = status.ifBlank { "unknown" },
                    error = st?.optString("error")?.ifBlank { null },
                    title = o.optString("title").ifBlank { null },
                    integrationID = o.optString("integrationID").ifBlank { null },
                )
            }
            return out
        }

        fun parseLocations(text: String): List<String> {
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("directory")?.ifBlank { null } }
        }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
