package ru.dredd20.opencode.ui

/**
 * OpenCode web UI switches to the desktop (wide) layout at min-width: 768px
 * (packages/app: createMediaQuery("(min-width: 768px)") and max-width: 767px CSS).
 * 640px is used for a few compact dialogs only.
 */
object ViewportPolicy {
    const val DESKTOP_MIN_DP = 768
    const val COMPACT_MAX_DP = 599
    const val MEDIUM_MAX_DP = 839

    fun useDesktopWebLayout(widthDp: Int): Boolean = widthDp >= DESKTOP_MIN_DP

    enum class WidthClass { Compact, Medium, Expanded }

    fun widthClass(widthDp: Int): WidthClass = when {
        widthDp <= COMPACT_MAX_DP -> WidthClass.Compact
        widthDp <= MEDIUM_MAX_DP -> WidthClass.Medium
        else -> WidthClass.Expanded
    }
}
