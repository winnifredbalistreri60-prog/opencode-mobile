package ru.dredd20.opencode.runtime

/** Official OpenCode CLI v2.3.0 glibc builds. Hashes were verified against SHA256SUMS in the study workspace. */
object OpenCodeRelease {
    const val VERSION = "2.3.0"
    const val BASE = "https://github.com/anomalyco/opencode/releases/download/v2.3.0"

    data class Artifact(val name: String, val sha256: String, val arch: String)

    val LINUX_X64 = Artifact(
        name = "opencode-linux-x64.tar.gz",
        sha256 = "fc0f81522aceeabac47693d88389efa2bc517ce74c2c38fd6967819fcc6e4d3b",
        arch = "amd64",
    )
    val LINUX_ARM64 = Artifact(
        name = "opencode-linux-arm64.tar.gz",
        sha256 = "3207fb5cdec448d479242c0f1a14f4f7bdfcad3a79209b5eb8f9003725e93711",
        arch = "arm64",
    )

    fun forAbi(abi: String): Artifact? = when (abi) {
        "arm64-v8a" -> LINUX_ARM64
        "x86_64" -> LINUX_X64
        else -> null
    }

    fun url(artifact: Artifact): String = "$BASE/${artifact.name}"
}

object MinFreeSpace {
    /** Ubuntu base + official CLI (~240 MiB on disk) + apt packages + headroom. */
    const val MEGABYTES = 2500L
}
