package ru.dredd20.opencode.backend

import android.content.Context
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import ru.dredd20.opencode.serve.PortAllocator
import ru.dredd20.opencode.serve.ServeCredentials
import ru.dredd20.opencode.serve.ServeHealth
import ru.dredd20.opencode.serve.ServeReuse
import ru.dredd20.opencode.serve.ServeState
import java.io.File

/** Start or reuse `opencode serve` inside Termux with a dynamic port and password. */
object TermuxServe {
    fun stateFile(c: Context) = File(c.filesDir, "termux-serve-state.json")

    suspend fun ensureRunning(c: Context): Result<ServeState> {
        if (!Termux.ready(c)) return Result.failure(IllegalStateException(Termux.notReadyReason(c)))
        val existing = ServeState.read(stateFile(c))
        if (existing != null && ServeHealth.check(existing.origin, existing.password).ok) {
            return Result.success(existing)
        }
        val port = PortAllocator.pickFreePort("127.0.0.1", existing?.port)
        val password = ServeCredentials.randomPassword()
        // Look for a previously installed official binary, else `opencode` on PATH (npm/install script).
        val cmd = """
            set -e
            export OPENCODE_PASSWORD=$(printf %s ${shellQuote(password)})
            export OPENCODE_DISABLE_AUTOUPDATE=1
            export OPENCODE_FILEWATCHER_DISABLE=1
            export OPENCODE_DISABLE_FILEWATCHER=1
            if command -v opencode >/dev/null 2>&1; then BIN=opencode
            elif [ -x ${'$'}HOME/.opencode/bin/opencode ]; then BIN=${'$'}HOME/.opencode/bin/opencode
            else echo 'opencode is not installed in Termux' >&2; exit 2
            fi
            ${'$'}BIN serve --hostname 127.0.0.1 --port $port
        """.trimIndent()
        Termux.runDetached(c, cmd)
        repeat(40) {
            kotlinx.coroutines.delay(500)
            if (ServeHealth.check("http://127.0.0.1:$port", password).ok) {
                val st = ServeState(port, -1, password, System.currentTimeMillis())
                ServeState.write(stateFile(c), st)
                return Result.success(st)
            }
        }
        return Result.failure(IllegalStateException(tr("не дождались opencode serve в Termux", "timed out waiting for opencode serve in Termux")))
    }

    suspend fun stop(c: Context) {
        if (Termux.ready(c)) {
            Termux.run(c, "pkill -f 'opencode serve' || true", 15_000)
        }
        stateFile(c).delete()
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
