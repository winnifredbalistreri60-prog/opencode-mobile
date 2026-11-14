package ru.dredd20.opencode.runtime

data class CommandResult(val stdout: String, val stderr: String, val exitCode: Int?, val error: String?) {
    val ok: Boolean get() = error == null && (exitCode == null || exitCode == 0)
    fun combined(): String = buildString {
        append(stdout)
        if (stderr.isNotBlank()) { if (isNotEmpty()) append("\n"); append("[stderr]\n").append(stderr) }
        if (error != null) { if (isNotEmpty()) append("\n"); append(error) }
    }
}
