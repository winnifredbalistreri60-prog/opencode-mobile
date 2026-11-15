package ru.dredd20.opencode.hardware

/**
 * Pure helpers for Hardware API command arguments. No Android types, so unit tests can run on the JVM.
 */
object HardwareArgs {
    fun parseHex(raw: String): ByteArray {
        val s = raw.trim().removePrefix("0x").removePrefix("0X").replace(Regex("\\s+"), "")
        if (s.isEmpty()) return ByteArray(0)
        require(s.length % 2 == 0) { "hex length must be even, got ${s.length}" }
        require(s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "invalid hex: $raw" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun formatHex(bytes: ByteArray, limit: Int = 4096): String {
        val n = bytes.size.coerceAtMost(limit)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append("%02x".format(bytes[i].toInt() and 0xff))
        if (bytes.size > limit) sb.append("…")
        return sb.toString()
    }

    /** Prefer explicit hex; otherwise UTF-8 text. */
    fun payload(text: String?, hex: String?, required: Boolean = true): ByteArray {
        val fromHex = hex?.takeIf { it.isNotBlank() }
        val fromText = text?.takeIf { it.isNotEmpty() }
        return when {
            fromHex != null -> parseHex(fromHex)
            fromText != null -> fromText.toByteArray(Charsets.UTF_8)
            required -> error("data required (text or hex)")
            else -> ByteArray(0)
        }
    }

    fun parseResolution(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val m = Regex("^(\\d+)[xX*](\\d+)$").matchEntire(raw.trim())
            ?: error("resolution must look like 1280x720, got $raw")
        val w = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        require(w in 64..7680 && h in 64..4320) { "resolution out of range: ${w}x$h" }
        return w to h
    }

    fun coerceIntervalMs(ms: Int, default: Int = 500): Int =
        (if (ms <= 0) default else ms).coerceIn(50, 60_000)

    fun coerceTimeoutMs(ms: Int, default: Int, max: Int = 90_000): Int =
        (if (ms <= 0) default else ms).coerceIn(1, max)

    fun coerceRing(n: Int, default: Int = 8): Int =
        (if (n <= 0) default else n).coerceIn(1, 64)

    fun pattern(raw: String, literal: Boolean = false): Regex {
        require(raw.isNotEmpty()) { "pattern required" }
        return if (literal) Regex.fromLiteral(raw) else Regex(raw)
    }

    fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}
