package ru.dredd20.opencode.phone

import android.Manifest
import android.os.Build

object PermissionRequests {
    /** Android 12+ requires COARSE to be requested together with FINE. */
    fun expand(permission: String): List<String> = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        -> if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        } else listOf(permission)
        else -> listOf(permission)
    }

    fun expand(permissions: Collection<String>): Array<String> =
        permissions.flatMap { expand(it) }.distinct().toTypedArray()
}
