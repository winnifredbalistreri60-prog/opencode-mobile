package ru.dredd20.opencode.backend

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.runtime.CommandResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** RUN_COMMAND helper, adapted from MURF (MIT). */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "$PREFIX/bin/bash"
    const val EXTRA_ID = "oc_termux_id"

    private val ids = AtomicInteger((System.currentTimeMillis() % 100000).toInt())
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<CommandResult>>()

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun ready(context: Context) = isInstalled(context) && hasPermission(context)

    fun notReadyReason(context: Context): String? = when {
        !isInstalled(context) -> tr("Установите Termux (F-Droid/GitHub)", "Install Termux (F-Droid/GitHub)")
        !hasPermission(context) -> tr("Нужно разрешение «запускать команды в Termux» и allow-external-apps=true", "Grant “Run commands in Termux” and set allow-external-apps=true")
        else -> null
    }

    private fun baseIntent(command: String, background: Boolean): Intent =
        Intent(ACTION).setClassName(PACKAGE, SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", if (background) "2" else "0")

    private fun start(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    fun runDetached(context: Context, command: String) {
        if (!ready(context)) return
        start(context, baseIntent(command, background = true))
    }

    suspend fun run(context: Context, command: String, timeoutMs: Long = 120_000): CommandResult {
        if (!isInstalled(context)) return CommandResult("", "", null, tr("Termux не установлен", "Termux is not installed"))
        if (!hasPermission(context)) return CommandResult("", "", null, tr("Нет разрешения RUN_COMMAND", "RUN_COMMAND permission missing"))
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<CommandResult>()
        waiting[id] = deferred
        val resultIntent = Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(context, id, resultIntent, flags)
        val intent = baseIntent(command, background = true).putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        return try {
            start(context, intent)
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: CommandResult("", "", null, tr("Termux не ответил за ${timeoutMs / 1000} с", "Termux did not answer within ${timeoutMs / 1000} s"))
        } catch (e: Exception) {
            CommandResult("", "", null, tr("Не удалось запустить Termux: ${e.message}", "Could not start Termux: ${e.message}"))
        } finally {
            waiting.remove(id)
        }
    }

    internal fun deliver(id: Int, result: CommandResult) { waiting[id]?.complete(result) }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Termux.EXTRA_ID, -1)
        val bundle = intent.getBundleExtra("result")
        val res = if (bundle == null) {
            CommandResult("", "", null, tr("Пустой ответ Termux", "Empty response from Termux"))
        } else {
            val err = bundle.getInt("err", -1)
            val errmsg = bundle.getString("errmsg")
            CommandResult(
                stdout = bundle.getString("stdout").orEmpty(),
                stderr = bundle.getString("stderr").orEmpty(),
                exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null,
                error = if (err > 0 && !errmsg.isNullOrBlank()) errmsg else null,
            )
        }
        Termux.deliver(id, res)
    }
}
