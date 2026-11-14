package ru.dredd20.opencode.prefs

/** Process-wide flag so background services know whether the UI is in the foreground. */
object AppVisibility {
    @Volatile var resumed: Boolean = false
}
