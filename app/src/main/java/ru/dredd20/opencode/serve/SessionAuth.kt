package ru.dredd20.opencode.serve

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Official OpenCode 2.x browser login: POST /api/pair with Basic auth, then navigate
 * the WebView to /auth/connect/{code}. The server sets an HttpOnly session cookie
 * and redirects to /. The password never enters JavaScript.
 *
 * Cookie naming matches packages/server/src/auth.ts: the port is included because
 * browsers share cookies across ports on the same host.
 */
object SessionAuth {
    fun sessionCookieName(hostHeader: String?): String {
        val host = hostHeader?.trim().orEmpty()
        val port = runCatching {
            val u = java.net.URI("http://" + host)
            if (u.port > 0) u.port.toString() else ""
        }.getOrDefault("")
        return if (port.isNotEmpty()) "opencode_session_$port" else "opencode_session"
    }

    fun connectUrl(origin: String, code: String): String {
        require(code.isNotBlank() && '/' !in code && '?' !in code)
        return origin.trimEnd('/') + "/auth/connect/" + code
    }

    fun issuePairingCode(
        origin: String,
        username: String,
        password: String,
        client: OkHttpClient = defaultClient(),
        timeoutMs: Long = 8_000,
    ): String? {
        if (origin.isBlank() || password.isBlank()) return null
        val req = Request.Builder()
            .url(origin.trimEnd('/') + "/api/pair")
            .header("Authorization", Credentials.basic(username, password))
            .header("Accept", "application/json")
            .post(ByteArray(0).toRequestBody(null, 0, 0))
            .build()
        return try {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                .newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val body = resp.body.string()
                    JSONObject(body).optString("code").takeIf { it.isNotBlank() }
                }
        } catch (_: Exception) {
            null
        }
    }

    fun cookieLooksLikeSession(cookieHeader: String?, hostHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val name = sessionCookieName(hostHeader)
        return cookieHeader.split(';').any { it.trim().startsWith("$name=") }
    }

    private fun defaultClient() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
}
