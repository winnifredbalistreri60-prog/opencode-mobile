package ru.dredd20.opencode.backend

/**
 * Shell scripts run in Termux through RUN_COMMAND (`bash -lc <script>`).
 *
 * Rules (BUG-25 and the Termux part of BUG-9):
 * - No secret is ever part of a script, so nothing secret lands on argv or in Termux logs.
 *   The serve password arrives on stdin (RUN_COMMAND_STDIN, Termux 0.109+), is written to a
 *   0600 file and sourced right before `exec`, so `opencode serve` replaces the shell (no bash parent
 *   keeping the password on its command line).
 * - Stop only touches our own process: the pid from our pidfile, and only if its cmdline still
 *   says `serve … --port <our port>`. Never `pkill -f`.
 * - Failures print a clear reason to stderr; the app shows it and writes it to termux.log.
 */
object TermuxScripts {
    /** Directory in Termux $HOME that holds our pidfile, port file and password file (mode 0700). */
    const val STATE_DIR = "\$HOME/.opencode-mobile"

    /** Exit codes of [serve] before `exec` (after `exec` it is the code of opencode itself). */
    const val EXIT_NO_PASSWORD = 64
    const val EXIT_NOT_INSTALLED = 127
    const val EXIT_BROKEN_BINARY = 126

    private fun checkPort(port: Int) = require(port in 1..65535) { "bad port $port" }

    /** Locate opencode: our launcher on PATH, then the official install script location. */
    private val findBin = """
        BIN=""
        if command -v opencode >/dev/null 2>&1; then BIN="${'$'}(command -v opencode)"
        elif [ -x "${'$'}HOME/.local/lib/opencode/opencode" ]; then BIN="${'$'}HOME/.local/lib/opencode/opencode"
        elif [ -x "${'$'}HOME/.opencode/bin/opencode" ]; then BIN="${'$'}HOME/.opencode/bin/opencode"
        fi
    """.trimIndent()

    /** Kill the process from our pidfile if (and only if) it is our `opencode serve` on [portExpr]. */
    private fun killOurs(portExpr: String) = """
        ocm_kill_ours() {
          local pid port cmd
          pid="${'$'}(cat "${'$'}D/serve.pid" 2>/dev/null || true)"
          port="$portExpr"
          case "${'$'}pid" in ''|*[!0-9]*) return 1 ;; esac
          [ -r "/proc/${'$'}pid/cmdline" ] || return 1
          cmd="${'$'}(tr '\0' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null || true)"
          case "${'$'}cmd" in *" serve "*"--port ${'$'}port "*) ;; *) return 1 ;; esac
          kill -TERM "${'$'}pid" 2>/dev/null || true
          for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
            kill -0 "${'$'}pid" 2>/dev/null || return 0
            sleep 0.25
          done
          kill -KILL "${'$'}pid" 2>/dev/null || true
          return 0
        }
    """.trimIndent()

    /**
     * Start `opencode serve` on 127.0.0.1:[port]. stdin: one line with the password.
     * stdout of serve goes to $STATE_DIR/serve.log (kept small), stderr stays on the RUN_COMMAND pipe
     * so an early crash comes back to the app in the PendingIntent result.
     */
    fun serve(port: Int): String {
        checkPort(port)
        return """
            umask 077
            D="$STATE_DIR"
            mkdir -p "${'$'}D" && chmod 700 "${'$'}D"
            IFS= read -r OCM_PW || true
            if [ -z "${'$'}OCM_PW" ]; then
              echo "OpenCode Mobile: no password on stdin. Update Termux to 0.109 or newer (F-Droid or GitHub)." >&2
              exit $EXIT_NO_PASSWORD
            fi
            ${killOurs("\$(cat \"\$D/serve.port\" 2>/dev/null || true)")}
            ocm_kill_ours && echo "stopped our previous server" || true
            $findBin
            if [ -z "${'$'}BIN" ]; then
              echo "OpenCode is not installed in Termux. Copy the install command from OpenCode Mobile (Termux mode) and run it in Termux." >&2
              exit $EXIT_NOT_INSTALLED
            fi
            OCM_VER="${'$'}(timeout 60 "${'$'}BIN" --version 2>&1 < /dev/null)"; OCM_RC=${'$'}?
            if [ ${'$'}OCM_RC -ne 0 ]; then
              echo "\"${'$'}BIN --version\" failed (exit ${'$'}OCM_RC):" >&2
              printf '%s\n' "${'$'}OCM_VER" | tail -n 20 >&2
              if [ ${'$'}OCM_RC -eq 159 ]; then
                echo "Killed by SIGSYS: Android seccomp blocked a system call of the glibc build (known on x86_64 Android; arm64 is the supported target)." >&2
              fi
              echo "Re-run the install command from OpenCode Mobile in Termux." >&2
              exit $EXIT_BROKEN_BINARY
            fi
            echo "binary: ${'$'}BIN (${'$'}OCM_VER)"
            printf 'OPENCODE_PASSWORD=%s\n' "${'$'}OCM_PW" > "${'$'}D/serve.env"
            chmod 600 "${'$'}D/serve.env"
            unset OCM_PW
            echo ${'$'}${'$'} > "${'$'}D/serve.pid"
            echo $port > "${'$'}D/serve.port"
            if [ -f "${'$'}D/serve.log" ] && [ "${'$'}(wc -c < "${'$'}D/serve.log")" -gt 1000000 ]; then mv -f "${'$'}D/serve.log" "${'$'}D/serve.log.1"; fi
            echo "--- ${'$'}(date) serve --port $port" >> "${'$'}D/serve.log"
            set -a; . "${'$'}D/serve.env"; set +a
            export OPENCODE_DISABLE_AUTOUPDATE=1 OPENCODE_DISABLE_FILEWATCHER=1 OPENCODE_FILEWATCHER_DISABLE=1
            exec "${'$'}BIN" serve --hostname 127.0.0.1 --port $port < /dev/null >> "${'$'}D/serve.log"
        """.trimIndent()
    }

    /** Stop only our server on [port]; always removes the password, pid and port files. */
    fun stop(port: Int?): String {
        if (port != null) checkPort(port)
        val portExpr = port?.toString() ?: "\$(cat \"\$D/serve.port\" 2>/dev/null || true)"
        return """
            D="$STATE_DIR"
            ${killOurs(portExpr)}
            if ocm_kill_ours; then echo "stopped"; else echo "our server is not running"; fi
            rm -f "${'$'}D/serve.env" "${'$'}D/serve.pid" "${'$'}D/serve.port"
        """.trimIndent()
    }

    /** Last lines of serve.log, for the in-app log after a failure. */
    fun tailLog(lines: Int = 40): String = "tail -n ${lines.coerceIn(1, 200)} \"$STATE_DIR/serve.log\" 2>/dev/null || true"
}
