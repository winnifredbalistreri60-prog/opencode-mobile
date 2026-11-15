package ru.dredd20.opencode.serve

import java.net.URI

/** Parse a remote serve URL only when it is complete enough to be a real origin. */
data class RemoteOrigin(val scheme: String, val host: String, val port: Int, val origin: String) {
    companion object {
        fun parse(raw: String): RemoteOrigin? {
            val url = raw.trim()
            if (url.isBlank()) return null
            val u = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = u.host?.trim().orEmpty()
            if (host.isBlank()) return null
            val port = when {
                u.port > 0 -> u.port
                scheme == "https" -> 443
                else -> 80
            }
            if (port !in 1..65535) return null
            return RemoteOrigin(scheme, host, port, "$scheme://$host:$port")
        }
    }
}
