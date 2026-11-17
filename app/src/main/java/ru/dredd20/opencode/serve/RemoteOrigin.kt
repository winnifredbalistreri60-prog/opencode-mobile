package ru.dredd20.opencode.serve

import ru.dredd20.opencode.i18n.tr
import java.net.URI

/** Parse a remote serve URL only when it is complete enough to be a real origin. */
data class RemoteOrigin(val scheme: String, val host: String, val port: Int, val origin: String) {
    enum class ParseIssue {
        EMPTY,
        MALFORMED,
        UNSUPPORTED_SCHEME,
        MISSING_HOST,
        INVALID_PORT,
        CREDENTIALS_IN_URL,
        PATH_NOT_SUPPORTED,
        QUERY_OR_FRAGMENT_NOT_SUPPORTED,
    }

    companion object {
        fun parseIssue(raw: String): ParseIssue? {
            val url = raw.trim()
            if (url.isBlank()) return ParseIssue.EMPTY
            val uri = runCatching { URI(url) }.getOrNull() ?: return ParseIssue.MALFORMED
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return ParseIssue.UNSUPPORTED_SCHEME
            if (uri.rawUserInfo != null) return ParseIssue.CREDENTIALS_IN_URL
            if (uri.host.isNullOrBlank()) return ParseIssue.MISSING_HOST
            if (uri.port == 0 || uri.port > 65535 || uri.port < -1) return ParseIssue.INVALID_PORT
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return ParseIssue.PATH_NOT_SUPPORTED
            if (uri.rawQuery != null || uri.rawFragment != null) return ParseIssue.QUERY_OR_FRAGMENT_NOT_SUPPORTED
            return null
        }

        /** Localized input error; null means the URL is syntactically valid. */
        fun errorMessage(raw: String): String? = when (parseIssue(raw)) {
            null -> null
            ParseIssue.EMPTY -> tr("Укажите URL удалённого сервера.", "Enter the remote server URL.")
            ParseIssue.MALFORMED -> tr("Не удалось разобрать URL. Проверьте формат, например https://server.example:4096.", "Could not parse the URL. Use a form such as https://server.example:4096.")
            ParseIssue.UNSUPPORTED_SCHEME -> tr("Поддерживаются только URL с http:// или https://.", "Only http:// and https:// URLs are supported.")
            ParseIssue.MISSING_HOST -> tr("В URL не указан адрес сервера.", "The URL does not contain a server host.")
            ParseIssue.INVALID_PORT -> tr("Порт должен быть в диапазоне 1–65535.", "The port must be between 1 and 65535.")
            ParseIssue.CREDENTIALS_IN_URL -> tr("Не добавляйте логин и пароль в URL. Укажите их отдельно в настройках.", "Do not put a username or password in the URL. Enter credentials separately in settings.")
            ParseIssue.PATH_NOT_SUPPORTED -> tr("Укажите адрес сервера без пути после имени хоста; прокси-путь пока не поддерживается.", "Enter the server origin without a path after the host; reverse-proxy paths are not supported yet.")
            ParseIssue.QUERY_OR_FRAGMENT_NOT_SUPPORTED -> tr("Уберите query и fragment из URL сервера.", "Remove the query and fragment from the server URL.")
        }

        fun parse(raw: String): RemoteOrigin? {
            if (parseIssue(raw) != null) return null
            val u = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val scheme = u.scheme.lowercase()
            val host = u.host.trim()
            val port = when {
                u.port > 0 -> u.port
                scheme == "https" -> 443
                else -> 80
            }
            return RemoteOrigin(scheme, host, port, "$scheme://$host:$port")
        }
    }
}
