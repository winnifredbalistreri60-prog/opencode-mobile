package ru.dredd20.opencode.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import ru.dredd20.opencode.serve.PortAllocator
import ru.dredd20.opencode.serve.ServeCredentials
import ru.dredd20.opencode.serve.ServeHealth
import ru.dredd20.opencode.serve.ServeState
import java.io.File

/**
 * Start or reuse `opencode serve` inside Termux with a dynamic port and password.
 * RUN_COMMAND always carries a result PendingIntent (BUG-25): if serve dies, its stderr and exit code
 * come back at once and are shown to the user and written to termux.log.
 */
object TermuxServe {
    const val LOG = "termux.log"
    /** First start can be slow (database migration, cold page cache), so wait longer than 20 s. */
    private const val START_TIMEOUT_MS = 90_000L
    private val lock = Mutex()

    fun stateFile(c: Context) = File(c.filesDir, "termux-serve-state.json")

    private fun log(c: Context, line: String) = EmbeddedRuntime.appendLog(c, LOG, line)

    private fun installLateLogger(c: Context) {
        val app = c.applicationContext
        if (Termux.onLateResult == null) {
            Termux.onLateResult = { id, r ->
                log(app, "termux command #$id finished later: exit=${r.exitCode} err=${r.error ?: "-"}")
                TermuxFailure.redact(r.stderr, null).trim().takeIf { it.isNotEmpty() }?.let {
                    log(app, "stderr (tail):\n" + it.lines().takeLast(20).joinToString("\n"))
                }
            }
        }
    }

    /**
     * Runs on Dispatchers.IO: ServeHealth is blocking OkHttp. Before 0.2.1 this was called on the main
     * thread, so every health check failed with NetworkOnMainThreadException (swallowed) and Start timed out
     * after 20 s even when serve was up (one of the causes of BUG-25).
     */
    suspend fun ensureRunning(c: Context): Result<ServeState> = withContext(Dispatchers.IO) { lock.withLock {
        installLateLogger(c)
        if (!Termux.ready(c)) return@withLock Result.failure(IllegalStateException(Termux.notReadyReason(c)))
        val existing = ServeState.read(stateFile(c))
        if (existing != null && ServeHealth.check(existing.origin, existing.password).ok) {
            return@withLock Result.success(existing)
        }
        val port = PortAllocator.pickFreePort("127.0.0.1", existing?.port)
        val password = ServeCredentials.randomPassword()
        log(c, "start: opencode serve on 127.0.0.1:$port")
        val launched = Termux.launch(c, TermuxScripts.serve(port), stdin = password + "\n", label = "OpenCode Mobile: opencode serve :$port")
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < START_TIMEOUT_MS) {
            if (launched.result.isCompleted) {
                val r = launched.result.await()
                val msg = TermuxFailure.describe(r, password)
                log(c, "start failed: exit=${r.exitCode} err=${r.error ?: "-"}\n$msg")
                if (r.error == null) {
                    // serve itself wrote to serve.log: show its tail too.
                    val tail = Termux.run(c, TermuxScripts.tailLog(30), 15_000)
                    TermuxFailure.redact(tail.stdout, password).trim().takeIf { it.isNotEmpty() }?.let { log(c, "serve.log (tail):\n$it") }
                }
                return@withLock Result.failure(IllegalStateException(msg))
            }
            if (ServeHealth.check("http://127.0.0.1:$port", password).ok) {
                val st = ServeState(port, -1, password, System.currentTimeMillis())
                ServeState.write(stateFile(c), st)
                Termux.forget(launched.id) // its exit is logged by onLateResult
                log(c, "running on 127.0.0.1:$port after ${(System.currentTimeMillis() - started) / 1000} s")
                return@withLock Result.success(st)
            }
            delay(500)
        }
        Termux.forget(launched.id)
        // Do not leave a half-started server behind.
        Termux.run(c, TermuxScripts.stop(port), 20_000)
        val msg = tr(
            "opencode serve в Termux не ответил за ${START_TIMEOUT_MS / 1000} с. Откройте Termux и выполните opencode --version.",
            "opencode serve in Termux did not answer within ${START_TIMEOUT_MS / 1000} s. Open Termux and run opencode --version.",
        )
        log(c, "start timeout on port $port")
        Result.failure(IllegalStateException(msg))
    } }

    suspend fun stop(c: Context) = withContext(Dispatchers.IO) { lock.withLock {
        installLateLogger(c)
        val st = ServeState.read(stateFile(c))
        if (Termux.ready(c)) {
            val r = Termux.run(c, TermuxScripts.stop(st?.port), 20_000)
            log(c, "stop (port ${st?.port ?: "?"}): ${r.stdout.trim().ifEmpty { "-" }} exit=${r.exitCode} err=${r.error ?: "-"}")
        }
        stateFile(c).delete()
    } }
}
