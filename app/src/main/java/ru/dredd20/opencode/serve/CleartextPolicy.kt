package ru.dredd20.opencode.serve

import java.net.URI

/**
 * When a remote URL uses http://, Android's Network Security Config must allow cleartext.
 * Domain tags cannot express CIDR, so the app permits cleartext globally and warns the
 * user for any http URL that is not loopback.
 */
object CleartextPolicy {
    fun hostOf(url: String): String? = runCatching {
        val raw = url.trim()
        val withScheme = if (':' !in raw.substringBefore('/')) "http://$raw" else raw
        URI(withScheme).host
    }.getOrNull()?.trim()?.trim('[', ']')?.lowercase()

    fun schemeOf(url: String): String? = runCatching {
        val raw = url.trim()
        val withScheme = if (':' !in raw.substringBefore('/')) "http://$raw" else raw
        URI(withScheme).scheme?.lowercase()
    }.getOrNull()

    fun isLoopback(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().lowercase().trim('[', ']')
        return h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0:0:0:0:0:0:0:1"
    }

    fun isPrivateOrLinkLocal(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (isLoopback(host)) return true
        val h = host.trim().lowercase().trim('[', ']')
        // Emulator host loopback alias
        if (h == "10.0.2.2" || h == "10.0.3.2") return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt(); val b = parts[1].toInt()
            if (a == 10) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 169 && b == 254) return true
        }
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
        return false
    }

    /** Show a warning when the user enters a non-HTTPS remote URL. */
    fun shouldWarnCleartext(url: String): Boolean {
        val s = schemeOf(url) ?: return false
        if (s != "http") return false
        return !isLoopback(hostOf(url))
    }
}
