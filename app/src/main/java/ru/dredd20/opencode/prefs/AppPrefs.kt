package ru.dredd20.opencode.prefs

import android.content.Context

enum class RuntimeMode { EMBEDDED, TERMUX, REMOTE }

object AppPrefs {
    private const val NAME = "opencode_mobile"
    private fun p(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun mode(c: Context): RuntimeMode =
        runCatching { RuntimeMode.valueOf(p(c).getString("mode", RuntimeMode.EMBEDDED.name)!!) }
            .getOrDefault(RuntimeMode.EMBEDDED)

    fun setMode(c: Context, m: RuntimeMode) = p(c).edit().putString("mode", m.name).apply()

    fun remoteUrl(c: Context) = p(c).getString("remote_url", "") ?: ""
    fun setRemoteUrl(c: Context, v: String) = p(c).edit().putString("remote_url", v).apply()
    fun remoteUser(c: Context) = p(c).getString("remote_user", "opencode") ?: "opencode"
    fun setRemoteUser(c: Context, v: String) = p(c).edit().putString("remote_user", v).apply()
    fun remotePassword(c: Context) = p(c).getString("remote_password", "") ?: ""
    fun setRemotePassword(c: Context, v: String) = p(c).edit().putString("remote_password", v).apply()

    fun setupDone(c: Context) = p(c).getBoolean("setup_done", false)
    fun setSetupDone(c: Context, v: Boolean) = p(c).edit().putBoolean("setup_done", v).apply()

    /** Category toggles. comms (SMS/contacts) default off. */
    fun categoryEnabled(c: Context, key: String, default: Boolean = true): Boolean =
        p(c).getBoolean("cat_$key", default)

    fun setCategoryEnabled(c: Context, key: String, v: Boolean) =
        p(c).edit().putBoolean("cat_$key", v).apply()

    const val CAT_SENSORS = "sensors"
    const val CAT_LOCATION = "location"
    const val CAT_CAMERA = "camera"
    const val CAT_CLIPBOARD = "clipboard"
    const val CAT_NOTIFY = "notify"
    const val CAT_SYSTEM = "system" // vibrate, torch, tts, volume, battery, wifi
    const val CAT_COMMS = "comms"   // sms, contacts — default off
    const val CAT_USB = "usb"
}
