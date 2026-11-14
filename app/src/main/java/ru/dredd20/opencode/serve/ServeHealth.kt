package ru.dredd20.opencode.serve

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HealthResult(val ok: Boolean, val httpCode: Int?, val version: String?)

object ServeHealth {
    fun check(origin: String, password: String, client: OkHttpClient = defaultClient(), timeoutMs: Long = 3_000): HealthResult {
        val req = Request.Builder()
            .url("$origin/api/info")
            .header("Authorization", Credentials.basic(ServeCredentials.USERNAME, password))
            .get()
            .build()
        return try {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                .newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    val ver = runCatching { JSONObject(body).optString("version").ifBlank { null } }.getOrNull()
                    HealthResult(resp.isSuccessful, resp.code, ver)
                }
        } catch (_: Exception) {
            HealthResult(false, null, null)
        }
    }

    fun unauthorizedWithoutPassword(origin: String, client: OkHttpClient = defaultClient()): Boolean = try {
        val req = Request.Builder().url("$origin/api/info").get().build()
        client.newCall(req).execute().use { it.code == 401 }
    } catch (_: Exception) { false }

    private fun defaultClient() = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
}
