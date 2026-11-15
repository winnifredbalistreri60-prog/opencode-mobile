package ru.dredd20.opencode.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothUuidTest {
    @Test
    fun shortUuidExpands() {
        assertEquals(
            "0000180f-0000-1000-8000-00805f9b34fb",
            BluetoothController.normalizeUuid("180F"),
        )
        assertEquals(
            "00001101-0000-1000-8000-00805f9b34fb",
            BluetoothController.normalizeUuid("00001101-0000-1000-8000-00805F9B34FB"),
        )
    }
}
