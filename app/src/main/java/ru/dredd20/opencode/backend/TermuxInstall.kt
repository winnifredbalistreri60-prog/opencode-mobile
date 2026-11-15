package ru.dredd20.opencode.backend

import android.content.Context
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.runtime.CommandResult

/** BUG-24: how OpenCode gets into Termux. The script itself is assets/termux/termux-install.sh. */
object TermuxInstall {
    const val ASSET = "termux/termux-install.sh"
    const val SCRIPT_NAME = "ocm-termux-install.sh"
    private const val EOF_MARK = "OCM_INSTALL_EOF"

    fun instructions(): String = tr(
        "1. Установите Termux с F-Droid или GitHub (не из Play), откройте его один раз.\n" +
            "2. Нажмите «Скопировать команду установки», вставьте её в Termux и нажмите Enter. " +
            "Скрипт поставит glibc из glibc-repo, официальный OpenCode для Linux (sha256 проверяется), " +
            "пропатчит только ELF-интерпретатор, создаст команду opencode и включит allow-external-apps=true.\n" +
            "3. Разрешите этому приложению «запускать команды в Termux» (кнопка ниже или Настройки Android → Приложения → OpenCode Mobile → Разрешения).\n" +
            "4. Нажмите «Запустить». Если не получится, причина из Termux появится здесь и в журнале (termux.log).\n" +
            "Нужен телефон arm64. На x86_64 Android (эмуляторы, часть Chromebook) seccomp не даёт запустить glibc-сборку: используйте встроенный режим.",
        "1. Install Termux from F-Droid or GitHub (not Play) and open it once.\n" +
            "2. Tap “Copy install command”, paste it into Termux and press Enter. " +
            "The script installs glibc from glibc-repo and the official OpenCode Linux build (sha256 checked), " +
            "patches only the ELF interpreter, creates the opencode command and sets allow-external-apps=true.\n" +
            "3. Allow this app to “Run commands in Termux” (button below or Android Settings → Apps → OpenCode Mobile → Permissions).\n" +
            "4. Tap Start. If it fails, the reason from Termux is shown here and in the log (termux.log).\n" +
            "Needs an arm64 phone. On x86_64 Android (emulators, some Chromebooks) seccomp blocks the glibc build: use the built-in mode.",
    )

    fun script(c: Context): String = c.assets.open(ASSET).bufferedReader().use { it.readText() }

    /** One paste for Termux: writes the script to $HOME and runs it with bash (stdin stays the terminal). */
    fun pasteCommand(script: String): String {
        require(script.lines().none { it.trim() == EOF_MARK }) { "script contains the heredoc marker" }
        return "cat > \"\$HOME/$SCRIPT_NAME\" <<'$EOF_MARK'\n" +
            script.trimEnd('\n') + "\n" +
            "$EOF_MARK\n" +
            "bash \"\$HOME/$SCRIPT_NAME\"\n"
    }
}

/** Turn a Termux RUN_COMMAND result into a short message for the user (secrets redacted). */
object TermuxFailure {
    fun describe(r: CommandResult, secret: String? = null, maxChars: Int = 1200): String {
        val head = when {
            r.error != null -> {
                val e = cleanError(r.error)
                if (e.contains("allow-external-apps")) tr(
                    "Termux не разрешает внешние команды: запустите в Termux команду установки (она включит allow-external-apps=true) или добавьте эту строку в ~/.termux/termux.properties и выполните termux-reload-settings.\nTermux: $e",
                    "Termux blocks external commands: run the install command in Termux (it sets allow-external-apps=true) or add that line to ~/.termux/termux.properties and run termux-reload-settings.\nTermux: $e")
                else tr("Termux: $e", "Termux: $e")
            }
            r.exitCode == TermuxScripts.EXIT_NOT_INSTALLED -> tr("OpenCode не установлен в Termux.", "OpenCode is not installed in Termux.")
            r.exitCode == TermuxScripts.EXIT_BROKEN_BINARY -> tr("opencode в Termux не запускается.", "opencode does not run in Termux.")
            r.exitCode == TermuxScripts.EXIT_NO_PASSWORD -> tr("Termux слишком старый (нужен 0.109+).", "Termux is too old (0.109+ needed).")
            else -> tr("opencode serve в Termux завершился, код ${r.exitCode ?: "?"}.", "opencode serve in Termux exited with code ${r.exitCode ?: "?"}.")
        }
        val err = redact(r.stderr, secret).trim()
        val out = redact(r.stdout, secret).trim()
        val detail = when {
            err.isNotEmpty() -> err
            out.isNotEmpty() -> out
            else -> ""
        }
        if (detail.isEmpty()) return head
        val tail = detail.lines().takeLast(15).joinToString("\n").takeLast(maxChars)
        return "$head\n$tail"
    }

    /** Termux formats errmsg as markdown ("Error Code: `2`", ``` fences); keep only the text. */
    fun cleanError(raw: String): String =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "```" && !it.startsWith("Error Code:") && it != "Error Message:" }
            .joinToString("\n") { it.replace("`", "") }
            .ifEmpty { raw.trim() }

    fun redact(s: String, secret: String?): String =
        if (secret.isNullOrEmpty()) s else s.replace(secret, "••••")
}
