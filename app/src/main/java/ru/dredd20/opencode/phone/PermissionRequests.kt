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
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        -> bluetooth().toList()
        Manifest.permission.NEARBY_WIFI_DEVICES -> wifiScan().toList()
        else -> listOf(permission)
    }

    fun expand(permissions: Collection<String>): Array<String> =
        permissions.flatMap { expand(it) }.distinct().toTypedArray()

    fun bluetooth(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    fun wifiScan(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}
