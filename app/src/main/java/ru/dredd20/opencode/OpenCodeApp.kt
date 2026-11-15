package ru.dredd20.opencode

import android.app.Application
import ru.dredd20.opencode.phone.PhoneApiService
import ru.dredd20.opencode.runtime.RuntimeService

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeService.ensureChannel(this)
        PhoneApiService.ensureChannel(this)
        // 0.2.0 and older kept the Remote password in plaintext: move it under the Keystore key now.
        Thread({ runCatching { ru.dredd20.opencode.prefs.AppPrefs.migrateSecrets(this) } }, "ocm-migrate").start()
    }
}
