package ru.dredd20.opencode.phone

import org.json.JSONObject

/**
 * Canonical JSON the CLI prints for Phone API responses.
 * Errors are always a JSON object, never a bare permission string.
 */
object PhoneClientJson {
    const val PERMISSION_PENDING = "permission_pending"

    fun forResponse(ok: Boolean, error: String?, needsPermission: String?, data: Any?): JSONObject {
        if (ok) {
            return when (data) {
                is JSONObject -> data
                null -> JSONObject().put("ok", true)
                else -> JSONObject().put("ok", true).put("data", PhoneResponse.wrap(data))
            }
        }
        val out = JSONObject()
        if (!needsPermission.isNullOrBlank()) {
            out.put("error", PERMISSION_PENDING)
            out.put("permission", needsPermission)
        } else {
            out.put("error", error?.takeIf { it.isNotBlank() } ?: "request failed")
        }
        return out
    }
}
