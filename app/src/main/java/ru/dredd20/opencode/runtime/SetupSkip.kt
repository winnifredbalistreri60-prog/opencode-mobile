package ru.dredd20.opencode.runtime

import java.io.File

/** Step markers and skip rules for Retry: never re-download a file whose sha256 already matches. */
object SetupSkip {
    const val CLI_VERSION_MARKER = ".opencode-cli-version"
    const val APT_MARKER = ".opencode-apt-ok"
    const val CONFIG_MARKER = ".opencode-config-ok"

    fun shouldSkipDownload(target: File, expectedSha: String?, actualSha: String?): Boolean {
        if (!target.isFile || target.length() <= 0L) return false
        if (expectedSha.isNullOrBlank()) return true
        val exp = expectedSha.lowercase()
        val act = actualSha?.lowercase() ?: return false
        return exp == act
    }

    fun shouldSkipOpenCodeInstall(
        binaryExists: Boolean,
        installedVersion: String?,
        wantedVersion: String,
        force: Boolean,
    ): Boolean = !force && binaryExists && !installedVersion.isNullOrBlank() &&
        installedVersion.trim().startsWith(wantedVersion)

    fun shouldSkipApt(markerExists: Boolean, gitOk: Boolean, rgOk: Boolean, pythonOk: Boolean, force: Boolean): Boolean =
        !force && markerExists && gitOk && rgOk && pythonOk
}
