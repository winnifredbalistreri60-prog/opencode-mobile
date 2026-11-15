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
    /**
     * Remote password, encrypted with an Android Keystore key ("remote_password_enc").
     * A plaintext "remote_password" from 0.2.0 and older is migrated on first read and removed.
     * If the Keystore is unusable (rare, broken devices) the value is kept as before, in plaintext.
     */
    fun remotePassword(c: Context): String {
        val prefs = p(c)
        prefs.getString(KEY_REMOTE_PW_ENC, null)?.let { enc ->
            return runCatching { SecretPrefs.decrypt(enc) }.getOrDefault("")
        }
        val legacy = prefs.getString(KEY_REMOTE_PW_PLAIN, "") ?: ""
        if (legacy.isNotEmpty()) setRemotePassword(c, legacy)
        return legacy
    }

    fun setRemotePassword(c: Context, v: String) {
        val e = p(c).edit()
        if (v.isEmpty()) {
            e.remove(KEY_REMOTE_PW_ENC).remove(KEY_REMOTE_PW_PLAIN)
        } else {
            val enc = runCatching { SecretPrefs.encrypt(v) }.getOrNull()
            if (enc != null) e.putString(KEY_REMOTE_PW_ENC, enc).remove(KEY_REMOTE_PW_PLAIN)
            else e.putString(KEY_REMOTE_PW_PLAIN, v).remove(KEY_REMOTE_PW_ENC)
        }
        e.apply()
    }

    /** Encrypts a legacy plaintext Remote password, if any. Safe to call repeatedly. */
    fun migrateSecrets(c: Context) {
        if (p(c).contains(KEY_REMOTE_PW_PLAIN) && !p(c).contains(KEY_REMOTE_PW_ENC)) remotePassword(c)
    }

    private const val KEY_REMOTE_PW_PLAIN = "remote_password"
    private const val KEY_REMOTE_PW_ENC = "remote_password_enc"

    fun setupDone(c: Context) = p(c).getBoolean("setup_done", false)
    fun setSetupDone(c: Context, v: Boolean) = p(c).edit().putBoolean("setup_done", v).apply()

    fun updateAutoCheck(c: Context) = p(c).getBoolean("update_auto_check", true)
    fun setUpdateAutoCheck(c: Context, v: Boolean) = p(c).edit().putBoolean("update_auto_check", v).apply()
    fun updateLastCheck(c: Context) = p(c).getLong("update_last_check", 0L)
    fun setUpdateLastCheck(c: Context, v: Long) = p(c).edit().putLong("update_last_check", v).apply()
    fun updatePostponedCode(c: Context) = p(c).getInt("update_postponed_code", 0)
    fun setUpdatePostponedCode(c: Context, v: Int) = p(c).edit().putInt("update_postponed_code", v).apply()

    /** Sensitive categories default off so a freshly installed agent cannot use them. */
    fun categoryDefault(key: String): Boolean = when (key) {
        CAT_LOCATION, CAT_CAMERA, CAT_CLIPBOARD, CAT_COMMS, CAT_NFC -> false
        else -> true
    }

    fun categoryEnabled(c: Context, key: String, default: Boolean = categoryDefault(key)): Boolean =
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
    const val CAT_BLUETOOTH = "bluetooth"
    const val CAT_NFC = "nfc"
    const val CAT_WIFI = "wifi"
    const val CAT_HARDWARE = "hardware"
}
