package ru.dredd20.opencode.ui

/** Small pure helpers for status lines (run3 items 6 and 7). */
object StatusText {
    /**
     * Embedded status: the runtime message plus the port, but only if the message does not already
     * name it ("Server listening on 127.0.0.1:34183 · port 34183" → just the message).
     */
    fun embedded(message: String, port: Int?, portLabel: (Int) -> String): String? {
        val msg = message.trim()
        val portPart = port?.takeIf { !mentionsPort(msg, it) }?.let(portLabel)
        return listOfNotNull(msg.ifBlank { null }, portPart).joinToString(" · ").ifBlank { null }
    }

    fun mentionsPort(text: String, port: Int): Boolean =
        Regex("(?<![0-9])$port(?![0-9])").containsMatchIn(text)

    /**
     * Setup progress line. When the same text is also shown as the error below, show only the percent
     * (the error used to appear twice).
     */
    fun setupProgress(pct: Int, message: String, error: String?): String {
        val m = message.trim()
        val e = error?.trim().orEmpty()
        val dup = e.isNotEmpty() && (m.isEmpty() || m == e || e.contains(m) || m.contains(e))
        return if (dup || m.isEmpty()) "$pct%" else "$pct%  $m"
    }
}
