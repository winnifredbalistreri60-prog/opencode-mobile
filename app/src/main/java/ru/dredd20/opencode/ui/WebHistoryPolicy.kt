package ru.dredd20.opencode.ui

import java.net.URI

/**
 * Same-origin check for WebView back navigation.
 * Host alone is not enough: http://127.0.0.1:40067 and :40603 must not mix.
 */
object WebHistoryPolicy {
    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = when {
            u.port > 0 -> u.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return "$scheme://$host"
        }
        return "$scheme://$host:$port"
    }

    fun sameOrigin(a: String?, b: String?): Boolean {
        val oa = originOf(a) ?: return false
        val ob = originOf(b) ?: return false
        return oa == ob
    }

    /** True if going back to [previousUrl] would stay on [currentOrigin]. */
    fun shouldGoBack(currentOrigin: String?, previousUrl: String?): Boolean {
        if (currentOrigin.isNullOrBlank() || previousUrl.isNullOrBlank()) return false
        return sameOrigin(currentOrigin, previousUrl)
    }
}
