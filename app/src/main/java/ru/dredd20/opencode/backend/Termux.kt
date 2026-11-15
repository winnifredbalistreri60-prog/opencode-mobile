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

/** RUN_COMMAND helper, adapted from MURF (MIT). Every command gets a PendingIntent for its result. */
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

    /** Called for results nobody waits for any more (for example serve exiting after Stop). */
    @Volatile var onLateResult: ((Int, CommandResult) -> Unit)? = null

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun ready(context: Context) = isInstalled(context) && hasPermission(context)

    fun instructions(): String = TermuxInstall.instructions()

    fun notReadyReason(context: Context): String? = when {
        !isInstalled(context) -> tr("Установите Termux (F-Droid/GitHub)", "Install Termux (F-Droid/GitHub)")
        !hasPermission(context) -> tr("Нужно разрешение «запускать команды в Termux» и allow-external-apps=true", "Grant “Run commands in Termux” and set allow-external-apps=true")
        else -> null
    }

    private fun baseIntent(command: String, stdin: String?, label: String?): Intent =
        Intent(ACTION).setClassName(PACKAGE, SERVICE)
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "2")
            .apply {
                if (stdin != null) putExtra("com.termux.RUN_COMMAND_STDIN", stdin)
                if (label != null) putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
            }

    private fun start(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    /** A started command. [result] completes when the command exits (stdout, stderr, exit code). */
    class Launched(val id: Int, val result: CompletableDeferred<CommandResult>)

    /**
     * Start [command] in the background with a result PendingIntent, without waiting.
     * [stdin] is passed through RUN_COMMAND_STDIN (not argv). Returns a failed result if it cannot start.
     */
    fun launch(context: Context, command: String, stdin: String? = null, label: String? = null): Launched {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<CommandResult>()
        if (!isInstalled(context)) {
            deferred.complete(CommandResult("", "", null, tr("Termux не установлен", "Termux is not installed")))
            return Launched(id, deferred)
        }
        if (!hasPermission(context)) {
            deferred.complete(CommandResult("", "", null, tr("Нет разрешения RUN_COMMAND", "RUN_COMMAND permission missing")))
            return Launched(id, deferred)
        }
        waiting[id] = deferred
        val resultIntent = Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(context, id, resultIntent, flags)
        val intent = baseIntent(command, stdin, label).putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        try {
            start(context, intent)
        } catch (e: Exception) {
            waiting.remove(id)
            deferred.complete(CommandResult("", "", null, tr("Не удалось запустить Termux: ${e.message}", "Could not start Termux: ${e.message}")))
        }
        return Launched(id, deferred)
    }

    /** Stop waiting for [id]; a later result goes to [onLateResult]. */
    fun forget(id: Int) { waiting.remove(id) }

    suspend fun run(context: Context, command: String, timeoutMs: Long = 120_000, stdin: String? = null): CommandResult {
        val l = launch(context, command, stdin)
        return try {
            withTimeoutOrNull(timeoutMs) { l.result.await() }
                ?: CommandResult("", "", null, tr("Termux не ответил за ${timeoutMs / 1000} с", "Termux did not answer within ${timeoutMs / 1000} s"))
        } finally {
            waiting.remove(l.id)
        }
    }

    internal fun deliver(id: Int, result: CommandResult) {
        val d = waiting.remove(id)
        if (d != null) d.complete(result) else onLateResult?.invoke(id, result)
    }
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
                error = if (err > 0) (errmsg?.takeIf { it.isNotBlank() } ?: "Termux error $err") else null,
            )
        }
        Termux.deliver(id, res)
    }
}
