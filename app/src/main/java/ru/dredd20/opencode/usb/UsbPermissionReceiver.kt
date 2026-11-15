package ru.dredd20.opencode.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import ru.dredd20.opencode.runtime.EmbeddedRuntime

class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbController.ACTION_PERMISSION) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        EmbeddedRuntime.appendLog(context, "phone.log", "USB permission granted=$granted")
    }
}
