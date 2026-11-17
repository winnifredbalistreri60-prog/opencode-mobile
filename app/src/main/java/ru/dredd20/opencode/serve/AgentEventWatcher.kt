package ru.dredd20.opencode.serve

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.dredd20.opencode.MainActivity
import ru.dredd20.opencode.R
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.prefs.AppVisibility
import java.util.concurrent.TimeUnit

/**
 * Listens to the official `/api/event` SSE stream and posts a notification when
 * an agent turn finishes, but only if the app UI is not in the foreground.
 */
class AgentEventWatcher(private val context: Context) {
    private var job: Job? = null

    fun start(scope: CoroutineScope, origin: String, password: String) {
        stop()
        if (origin.isBlank() || password.isBlank()) return
        job = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                try {
                    listen(origin, password)
                    backoff = 1_000L
                } catch (_: Exception) {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun listen(origin: String, password: String) {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val req = Request.Builder()
            .url(origin.trimEnd('/') + "/api/event")
            .header("Authorization", Credentials.basic(ServeCredentials.USERNAME, password))
            .header("Accept", "text/event-stream")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("sse http ${resp.code}")
            val body = resp.body.byteStream().bufferedReader()
            val data = StringBuilder()
            while (true) {
                val line = body.readLine() ?: break
                when {
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                    line.isBlank() -> {
                        if (data.isNotEmpty()) {
                            handleEvent(data.toString())
                            data.clear()
                        }
                    }
                }
            }
        }
    }

    internal fun handleEvent(raw: String) {
        val type = eventType(raw) ?: return
        if (type !in NOTIFY_TYPES) return
        if (AppVisibility.resumed) return
        notifyReady()
    }

    private fun notifyReady() {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 44,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(tr("Ответ агента готов", "Agent reply is ready"))
            .setContentText(tr("OpenCode закончил ответ. Нажмите, чтобы открыть сессию.", "OpenCode finished a reply. Tap to open the session."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_ID, n) }
    }

    companion object {
        const val CHANNEL = "opencode_agent"
        const val NOTIFY_ID = 8110
        val NOTIFY_TYPES = setOf("session.idle", "session.execution.succeeded")

        fun eventType(raw: String): String? = runCatching {
            val j = JSONObject(raw)
            val direct = j.optString("type").ifBlank { "" }
            val nested = j.optJSONObject("payload")?.optString("type").orEmpty()
            direct.ifBlank { nested }.ifBlank { null }
        }.getOrNull()

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, tr("Ответы агента", "Agent replies"), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }
    }
}
