package ru.dredd20.opencode.phone

import org.json.JSONObject

/** Parse termux-torch / MCP torch arguments. "off" must not default to on. */
object TorchArgs {
    fun parseOn(args: JSONObject, fallback: Boolean = true): Boolean {
        if (args.has("on") && !args.isNull("on")) {
            val raw = args.opt("on") ?: return fallback
            return when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                else -> truthy(raw.toString(), fallback)
            }
        }
        val argv = args.optJSONArray("argv") ?: return fallback
        if (argv.length() == 0) return fallback
        return truthy(argv.optString(0), fallback)
    }

    fun truthy(raw: String, fallback: Boolean = true): Boolean = when (raw.trim().lowercase()) {
        "off", "0", "false", "no", "disable", "disabled" -> false
        "on", "1", "true", "yes", "enable", "enabled" -> true
        else -> fallback
    }
}
