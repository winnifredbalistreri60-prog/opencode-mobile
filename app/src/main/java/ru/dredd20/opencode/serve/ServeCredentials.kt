package ru.dredd20.opencode.serve

import java.security.SecureRandom
import java.util.Base64

object ServeCredentials {
    const val USERNAME = "opencode"

    /** URL-safe random password, same encoding OpenCode uses (base64url, 32 bytes). */
    fun randomPassword(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun basicHeaderValue(password: String): String {
        val raw = "$USERNAME:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }
}
