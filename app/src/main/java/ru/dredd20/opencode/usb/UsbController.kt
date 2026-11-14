package ru.dredd20.opencode.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.nio.charset.Charset

/**
 * USB OTG listing + USB-serial (MIT usb-serial-for-android).
 * Passing a raw fd into proot via SCM_RIGHTS (termux-usb -e) is deferred to v0.2.
 */
class UsbController(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var openDevice: String? = null

    fun list(): List<Map<String, Any?>> {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val serialIds = drivers.flatMap { d -> d.ports.map { it.device } }.map { key(it) }.toSet()
        return manager.deviceList.values.map { dev ->
            mapOf(
                "key" to key(dev),
                "vendorId" to dev.vendorId,
                "productId" to dev.productId,
                "name" to (dev.productName ?: dev.deviceName),
                "manufacturer" to dev.manufacturerName,
                "serial" to runCatching { if (manager.hasPermission(dev)) dev.serialNumber else null }.getOrNull(),
                "hasPermission" to manager.hasPermission(dev),
                "usbSerial" to (key(dev) in serialIds),
            )
        }
    }

    fun open(deviceKey: String, baud: Int): Map<String, Any?> {
        close()
        val dev = find(deviceKey) ?: error("USB device not found: $deviceKey")
        if (!manager.hasPermission(dev)) {
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            manager.requestPermission(dev, pi)
            error("USB permission requested; retry after the system dialog")
        }
        val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
            ?: error("no USB-serial driver for $deviceKey")
        val p = driver.ports.first()
        val conn = manager.openDevice(dev) ?: error("openDevice failed")
        p.open(conn)
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port = p
        openDevice = deviceKey
        return mapOf("opened" to deviceKey, "baud" to baud)
    }

    fun write(text: String): Map<String, Any?> {
        val p = port ?: error("no USB serial device is open")
        val bytes = text.toByteArray(Charset.forName("UTF-8"))
        p.write(bytes, 2_000)
        return mapOf("written" to bytes.size)
    }

    fun read(timeoutMs: Int): Map<String, Any?> {
        val p = port ?: error("no USB serial device is open")
        val buf = ByteArray(4096)
        val n = p.read(buf, timeoutMs.coerceIn(0, 30_000))
        val text = if (n > 0) String(buf, 0, n, Charset.forName("UTF-8")) else ""
        return mapOf("bytes" to n.coerceAtLeast(0), "text" to text)
    }

    fun close(): Map<String, Any?> {
        runCatching { port?.close() }
        port = null
        val was = openDevice
        openDevice = null
        return mapOf("closed" to was)
    }

    private fun find(key: String): UsbDevice? =
        manager.deviceList.values.firstOrNull { key(it) == key || it.deviceName == key || it.deviceName.endsWith(key) }

    private fun key(d: UsbDevice) = "%04x:%04x:%s".format(d.vendorId, d.productId, d.deviceName)

    companion object {
        const val ACTION_PERMISSION = "ru.dredd20.opencode.USB_PERMISSION"
    }
}
