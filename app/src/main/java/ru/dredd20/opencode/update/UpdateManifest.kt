package ru.dredd20.opencode.update

import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/**
 * Pure update logic (no Android APIs) so it is covered by JVM unit tests.
 *
 * Release manifest `opencode-mobile-version.json` (attached to every GitHub release):
 * {"versionCode":4,"versionName":"0.1.3",
 *  "url":"https://github.com/<owner>/opencode-mobile/releases/download/v0.1.3/opencode-mobile-0.1.3.apk",
 *  "sha256":"<64 hex>","size":123,"minSdk":26,"notes":"…"}
 */
object UpdateManifest {
    const val MANIFEST_NAME = "opencode-mobile-version.json"
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 300L * 1024 * 1024
    const val MAX_NOTES = 4000
    const val CHECK_INTERVAL_MS = 6 * 3600_000L

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val minSdk: Int,
        val notes: String,
    )

    private val SHA_RE = Regex("^[0-9a-f]{64}$")

    /**
     * Parse and validate a manifest. Returns null when anything is off:
     * the APK URL must start with [allowedPrefix] (the repo's release download path),
     * must not contain `..`, `?`, `#` or `@`, and must end with `.apk`.
     */
    fun parse(text: String, allowedPrefix: String): Info? = runCatching {
        val j = JSONObject(text)
        val url = j.getString("url").trim()
        val sha = j.getString("sha256").trim().lowercase()
        val code = j.getInt("versionCode")
        if (!isAllowedUrl(url, allowedPrefix) || !url.endsWith(".apk")) return null
        if (!SHA_RE.matches(sha)) return null
        if (code <= 0) return null
        val size = j.optLong("size", -1L)
        if (size > MAX_APK_BYTES) return null
        Info(
            versionCode = code,
            versionName = j.optString("versionName", "").ifBlank { code.toString() }.take(64),
            url = url,
            sha256 = sha,
            size = if (size > 0) size else -1L,
            minSdk = j.optInt("minSdk", 0),
            notes = j.optString("notes", "").take(MAX_NOTES),
        )
    }.getOrNull()

    fun isAllowedUrl(url: String, allowedPrefix: String): Boolean =
        allowedPrefix.isNotBlank() && url.startsWith(allowedPrefix) &&
            !url.contains("..") && !url.contains('?') && !url.contains('#') && !url.contains('@') &&
            url.length > allowedPrefix.length

    /**
     * GitHub API fallback: from `GET /repos/{owner}/{repo}/releases/latest` take the
     * `browser_download_url` of the [MANIFEST_NAME] asset (only if it is under [allowedPrefix]).
     */
    fun manifestUrlFromApi(json: String, allowedPrefix: String): String? = runCatching {
        val j = JSONObject(json)
        if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
        val assets = j.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == MANIFEST_NAME) {
                val u = a.optString("browser_download_url")
                return if (isAllowedUrl(u, allowedPrefix) && u.endsWith("/$MANIFEST_NAME")) u else null
            }
        }
        null
    }.getOrNull()

    fun isNewer(info: Info, currentCode: Int): Boolean = info.versionCode > currentCode

    /** Auto check at most once per [interval]; a clock moved backwards also allows a check. */
    fun shouldAutoCheck(enabled: Boolean, nowMs: Long, lastCheckMs: Long, interval: Long = CHECK_INTERVAL_MS): Boolean =
        enabled && (lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= interval)

    /** Auto check shows the dialog unless the user postponed exactly this (or a newer) version. */
    fun shouldPrompt(info: Info, currentCode: Int, postponedCode: Int, auto: Boolean): Boolean =
        isNewer(info, currentCode) && (!auto || info.versionCode > postponedCode)

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** Constant-time compare of two hex digests. */
    fun sameDigest(a: String, b: String): Boolean = MessageDigest.isEqual(a.lowercase().toByteArray(), b.lowercase().toByteArray())

    /** At least one signer certificate of the downloaded APK must match the installed app. */
    fun signersMatch(apk: Set<String>, installed: Set<String>): Boolean =
        apk.isNotEmpty() && installed.isNotEmpty() && apk.intersect(installed).isNotEmpty()

    /** What the main button of the dialog does in the "downloaded and verified" state. */
    enum class ReadyAction { INSTALL, OPEN_SETTINGS }

    /**
     * MURF bug: after "Allow from this source" the button stayed "Open settings".
     * Here the action is recomputed from the live permission every time the app resumes.
     */
    fun readyAction(canInstallPackages: Boolean): ReadyAction =
        if (canInstallPackages) ReadyAction.INSTALL else ReadyAction.OPEN_SETTINGS
}
