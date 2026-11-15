package ru.dredd20.opencode.phone

import org.json.JSONArray
import org.json.JSONObject

data class PhoneRequest(
    val id: String,
    val token: String,
    val cmd: String,
    val args: JSONObject,
) {
    fun argString(key: String, fallback: String? = null): String? =
        if (args.has(key) && !args.isNull(key)) args.optString(key) else fallback

    fun argInt(key: String, fallback: Int): Int = args.optInt(key, fallback)
    fun argBool(key: String, fallback: Boolean = false): Boolean = args.optBoolean(key, fallback)

    fun text(): String = argString("text")
        ?: args.optJSONArray("argv")?.let { arr -> (0 until arr.length()).joinToString(" ") { arr.optString(it) } }
        ?: ""
}

data class PhoneResponse(
    val id: String,
    val ok: Boolean,
    val data: Any? = null,
    val error: String? = null,
    val needsPermission: String? = null,
    val needsConfirm: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("id", id).put("ok", ok).also { j ->
        if (data != null) j.put("data", wrap(data))
        if (error != null) j.put("error", error)
        if (needsPermission != null) j.put("needs_permission", needsPermission)
        if (needsConfirm) j.put("needs_confirm", true)
    }

    companion object {
        fun ok(id: String, data: Any? = null) = PhoneResponse(id, true, data = data)
        fun error(id: String, error: String, needsPermission: String? = null, needsConfirm: Boolean = false) =
            PhoneResponse(id, false, error = error, needsPermission = needsPermission, needsConfirm = needsConfirm)

        fun wrap(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is JSONObject, is JSONArray, is Number, is Boolean, is String -> value
            is Map<*, *> -> JSONObject(value)
            is Iterable<*> -> JSONArray().also { a -> value.forEach { a.put(wrap(it)) } }
            else -> value.toString()
        }
    }
}

object PhoneProtocol {
    val COMMANDS = setOf(
        "sensor", "battery-status", "location", "camera-photo",
        "clipboard-get", "clipboard-set", "notification", "toast",
        "vibrate", "torch", "tts-speak", "volume", "wifi-connectioninfo",
        "share", "open-url", "telephony-call", "usb",
        "sms-send", "sms-list", "contact-list",
        "hw-session", "bluetooth", "wifi-scaninfo", "wifi-connect", "net",
        "nfc", "camera-capture", "camera-start", "camera-stop", "camera-latest",
    )

    val DANGEROUS = setOf(
        "sms-send", "sms-list", "contact-list", "camera-photo", "open-url",
        "camera-capture", "camera-start", "nfc",
    )

    fun parse(line: String): PhoneRequest? = runCatching {
        val j = JSONObject(line)
        val cmd = j.getString("cmd").trim()
        if (cmd.isEmpty()) return null
        PhoneRequest(
            id = j.optString("id").ifBlank { "0" },
            token = j.optString("token"),
            cmd = cmd,
            args = j.optJSONObject("args") ?: JSONObject(),
        )
    }.getOrNull()

    fun tokenOk(provided: String, expected: String): Boolean =
        expected.isNotEmpty() && provided.isNotEmpty() && provided == expected

    fun categoryFor(cmd: String): String? = when (cmd) {
        "sensor", "battery-status" -> "sensors"
        "location", "wifi-connectioninfo" -> "location"
        "camera-photo", "camera-capture", "camera-start", "camera-stop", "camera-latest" -> "camera"
        "clipboard-get", "clipboard-set" -> "clipboard"
        "notification", "toast" -> "notify"
        "vibrate", "torch", "tts-speak", "volume" -> "system"
        "sms-send", "sms-list", "contact-list" -> "comms"
        "telephony-call" -> "system"
        "usb" -> "usb"
        "hw-session" -> "hardware"
        "bluetooth" -> "bluetooth"
        "wifi-connectioninfo", "wifi-scaninfo" -> "location"
        "wifi-connect" -> "wifi"
        "net" -> "system"
        "nfc" -> "nfc"
        "share", "open-url" -> "system"
        else -> null
    }
}
