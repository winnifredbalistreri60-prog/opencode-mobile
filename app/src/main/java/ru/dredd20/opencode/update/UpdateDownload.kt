package ru.dredd20.opencode.update

import java.io.IOException

/** Pure rules for resuming the APK download (run4 item 3). */
object UpdateDownload {
    /** Verification failures (sha256, size, signature, package). Network errors stay IOException. */
    class VerifyException(message: String) : Exception(message)

    enum class Next { APPEND, RESTART, ALREADY_COMPLETE }

    /** .part file name is tied to versionCode and sha256, so a different build never reuses it. */
    fun partName(versionCode: Int, sha256: String) = "opencode-mobile-$versionCode-${sha256.lowercase().take(12)}.apk.part"
    fun apkName(versionCode: Int) = "opencode-mobile-$versionCode.apk"

    /** Range start to request, or null for a full download. */
    fun rangeStart(partLen: Long, declaredSize: Long): Long? = when {
        partLen <= 0 -> null
        declaredSize > 0 && partLen > declaredSize -> null // garbage, start over
        else -> partLen
    }

    /** What to do with the response to a request that had `Range: bytes=<rangeStart>-`. */
    fun next(httpCode: Int, rangeStart: Long?, declaredSize: Long, partLen: Long): Next = when {
        rangeStart == null -> Next.RESTART
        httpCode == 206 -> Next.APPEND
        httpCode == 416 && declaredSize > 0 && partLen == declaredSize -> Next.ALREADY_COMPLETE
        else -> Next.RESTART // 200: server ignored Range, or 416 with a wrong length
    }

    /** "Content-Range: bytes 100-999/1000" must start where our .part ends. */
    fun contentRangeStart(header: String?): Long? =
        header?.let { Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** IOException = network or HTTP trouble: "Download failed", the .part is kept for Retry. */
    fun isNetworkError(t: Throwable): Boolean = t is IOException
}
