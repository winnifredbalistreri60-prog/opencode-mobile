package ru.dredd20.opencode

import android.app.Application
import ru.dredd20.opencode.phone.PhoneApiService
import ru.dredd20.opencode.runtime.RuntimeService

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeService.ensureChannel(this)
        PhoneApiService.ensureChannel(this)
    }
}
