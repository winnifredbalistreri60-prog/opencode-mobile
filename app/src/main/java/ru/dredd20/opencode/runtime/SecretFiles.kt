package ru.dredd20.opencode.runtime

/**
 * Secrets must not appear on a process argv (they show up in `ps`).
 * The serve password is written to a 0600 file bind-mounted at [GUEST_PASSWORD_PATH]
 * and sourced by the guest shell.
 */
object SecretFiles {
    const val SERVE_PASSWORD_ENV = "OPENCODE_PASSWORD"
    const val GUEST_PASSWORD_PATH = "/run/opencode-password"
    val FORBIDDEN_IN_ARGV = setOf(SERVE_PASSWORD_ENV, "PHONE_TOKEN")

    fun envFileBody(name: String, value: String): String {
        require(name.matches(Regex("[A-Z_][A-Z0-9_]*"))) { "bad env name $name" }
        require(!value.contains('\n') && !value.contains('\r')) { "secret must be a single line" }
        return "$name=$value\n"
    }

    /** Guest command that sources the password file then execs serve. No secret in the string. */
    fun serveCommand(port: Int): String {
        require(port in 1..65535)
        return "set -a; . $GUEST_PASSWORD_PATH; set +a; exec opencode serve --hostname 127.0.0.1 --port $port"
    }
}
