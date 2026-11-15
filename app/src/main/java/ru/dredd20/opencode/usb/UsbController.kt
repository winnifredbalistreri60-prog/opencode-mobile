package ru.dredd20.opencode.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import ru.dredd20.opencode.hardware.HardwareArgs
import ru.dredd20.opencode.hardware.HardwareTransport
import ru.dredd20.opencode.i18n.tr
import java.nio.charset.Charset

/**
 * USB Host: device/interface listing, permission, USB-serial (MIT usb-serial-for-android),
 * HID reports and raw bulk/interrupt/control transfers.
 */
class UsbController(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var openDevice: String? = null
    @Volatile private var raw: Raw? = null

    private data class Raw(
        val key: String,
        val dev: UsbDevice,
        val conn: UsbDeviceConnection,
        val claimed: MutableSet<Int> = mutableSetOf(),
    )

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
                "deviceName" to dev.deviceName,
                "class" to dev.deviceClass,
                "subclass" to dev.deviceSubclass,
                "protocol" to dev.deviceProtocol,
                "serial" to runCatching { if (manager.hasPermission(dev)) dev.serialNumber else null }.getOrNull(),
                "hasPermission" to manager.hasPermission(dev),
                "usbSerial" to (key(dev) in serialIds),
                "interfaces" to interfacesOf(dev),
            )
        }
    }

    fun requestPermission(deviceKey: String): Map<String, Any?> {
        val dev = find(deviceKey) ?: error(tr("USB-устройство не найдено: $deviceKey", "USB device not found: $deviceKey"))
        if (manager.hasPermission(dev)) {
            return mapOf("key" to key(dev), "hasPermission" to true, "requested" to false)
        }
        askPermission(dev)
        return mapOf(
            "key" to key(dev),
            "hasPermission" to false,
            "requested" to true,
            "message" to tr(
                "Система запросила доступ к USB. Подтвердите диалог и повторите команду.",
                "The system asked for USB access. Confirm the dialog and retry the command.",
            ),
        )
    }

    fun open(deviceKey: String, baud: Int): Map<String, Any?> = synchronized(lock) {
        closeSerialLocked()
        val dev = requirePermitted(deviceKey)
        val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
            ?: error(tr("нет USB-serial драйвера для $deviceKey (CDC/CH340/CP210x/FTDI)", "no USB-serial driver for $deviceKey (CDC/CH340/CP210x/FTDI)"))
        val p = driver.ports.first()
        val conn = manager.openDevice(dev) ?: error("openDevice failed")
        p.open(conn)
        p.setParameters(baud.coerceIn(1200, 2_000_000), 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port = p
        openDevice = key(dev)
        mapOf("opened" to key(dev), "baud" to baud, "transport" to "usb-serial")
    }

    fun write(text: String, hex: String? = null): Map<String, Any?> {
        val p = port ?: error(tr("USB-serial устройство не открыто", "no USB serial device is open"))
        val bytes = HardwareArgs.payload(text, hex, required = true)
        p.write(bytes, 2_000)
        return mapOf("written" to bytes.size)
    }

    fun read(timeoutMs: Int): Map<String, Any?> {
        val p = port ?: error(tr("USB-serial устройство не открыто", "no USB serial device is open"))
        val buf = ByteArray(4096)
        val n = p.read(buf, timeoutMs.coerceIn(0, 30_000))
        val slice = if (n > 0) buf.copyOf(n) else ByteArray(0)
        return mapOf(
            "bytes" to n.coerceAtLeast(0),
            "text" to if (slice.isNotEmpty()) String(slice, Charset.forName("UTF-8")) else "",
            "hex" to HardwareArgs.formatHex(slice),
        )
    }

    fun close(): Map<String, Any?> = synchronized(lock) {
        val was = openDevice
        closeSerialLocked()
        closeRawLocked()
        mapOf("closed" to was)
    }

    fun serialTransport(): HardwareTransport {
        val p = port ?: error(tr("USB-serial устройство не открыто (сначала usb open)", "no USB serial device is open (run usb open first)"))
        val target = openDevice ?: "serial"
        return object : HardwareTransport {
            override val kind = "usb-serial"
            override val target: String = target
            override fun send(data: ByteArray) { p.write(data, 2_000) }
            override fun read(timeoutMs: Int): ByteArray {
                val buf = ByteArray(4096)
                val n = p.read(buf, timeoutMs.coerceIn(0, 30_000))
                return if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
            override fun close() { /* session close must not drop the shared port */ }
        }
    }

    fun hidWrite(deviceKey: String?, report: ByteArray, reportId: Int, timeoutMs: Int): Map<String, Any?> =
        synchronized(lock) {
            val r = ensureRaw(deviceKey)
            val hid = hidInterface(r.dev)
            claim(r, hid)
            val timeout = timeoutMs.coerceIn(1, 10_000)
            val out = hid.endpointOrNull(UsbConstants.USB_DIR_OUT, interruptOrBulk())
            val n = if (out != null) {
                val payload = if (reportId > 0) byteArrayOf(reportId.toByte()) + report else report
                r.conn.bulkTransfer(out, payload, payload.size, timeout)
            } else {
                val buf = if (reportId > 0) byteArrayOf(reportId.toByte()) + report else report
                r.conn.controlTransfer(
                    0x21, 0x09, (0x02 shl 8) or (reportId and 0xff),
                    hid.id, buf, buf.size, timeout,
                )
            }
            if (n < 0) error(tr("HID запись не удалась ($n)", "HID write failed ($n)"))
            mapOf("written" to n, "device" to r.key, "interface" to hid.id)
        }

    fun hidRead(deviceKey: String?, max: Int, reportId: Int, timeoutMs: Int): Map<String, Any?> =
        synchronized(lock) {
            val r = ensureRaw(deviceKey)
            val hid = hidInterface(r.dev)
            claim(r, hid)
            val timeout = timeoutMs.coerceIn(1, 10_000)
            val size = max.coerceIn(1, 1024)
            val buf = ByteArray(size)
            val epIn = hid.endpointOrNull(UsbConstants.USB_DIR_IN, interruptOrBulk())
            val n = if (epIn != null) {
                r.conn.bulkTransfer(epIn, buf, buf.size, timeout)
            } else {
                r.conn.controlTransfer(
                    0xA1, 0x01, (0x01 shl 8) or (reportId and 0xff),
                    hid.id, buf, buf.size, timeout,
                )
            }
            if (n < 0) error(tr("HID чтение не удалось ($n)", "HID read failed ($n)"))
            val slice = buf.copyOf(n)
            mapOf(
                "bytes" to n,
                "hex" to HardwareArgs.formatHex(slice),
                "text" to HardwareArgs.utf8(slice),
                "device" to r.key,
                "interface" to hid.id,
            )
        }

    fun transfer(
        deviceKey: String?,
        type: String,
        direction: String,
        endpointAddr: Int?,
        interfaceId: Int?,
        data: ByteArray,
        length: Int,
        timeoutMs: Int,
        requestType: Int?,
        request: Int?,
        value: Int?,
        index: Int?,
    ): Map<String, Any?> = synchronized(lock) {
        val r = ensureRaw(deviceKey)
        val timeout = timeoutMs.coerceIn(1, 30_000)
        val dirIn = direction.equals("in", true) || direction.equals("recv", true)
        val kind = type.lowercase()
        val n: Int
        val buf: ByteArray
        when (kind) {
            "control" -> {
                val intf = interfaceId?.let { id -> (0 until r.dev.interfaceCount).map { r.dev.getInterface(it) }.first { it.id == id } }
                if (intf != null) claim(r, intf)
                val size = if (dirIn) length.coerceIn(1, 4096) else data.size
                buf = if (dirIn) ByteArray(size) else data
                val rt = requestType ?: if (dirIn) 0xC0 else 0x40
                n = r.conn.controlTransfer(
                    rt,
                    request ?: 0,
                    value ?: 0,
                    index ?: (intf?.id ?: 0),
                    buf,
                    if (dirIn) buf.size else data.size,
                    timeout,
                )
            }
            "bulk", "interrupt" -> {
                val intf = pickInterface(r.dev, interfaceId, endpointAddr)
                claim(r, intf)
                val ep = pickEndpoint(intf, endpointAddr, if (dirIn) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT, kind)
                val size = if (dirIn) length.coerceIn(1, 16384) else data.size
                buf = if (dirIn) ByteArray(size) else data
                n = r.conn.bulkTransfer(ep, buf, buf.size, timeout)
            }
            else -> error(tr("тип передачи $type не поддержан (bulk, interrupt, control)", "unsupported transfer type $type (bulk, interrupt, control)"))
        }
        if (n < 0) error(tr("USB transfer не удался ($n)", "USB transfer failed ($n)"))
        val slice = if (dirIn) buf.copyOf(n) else buf
        mapOf(
            "bytes" to n,
            "hex" to if (dirIn) HardwareArgs.formatHex(slice) else HardwareArgs.formatHex(data),
            "text" to if (dirIn) HardwareArgs.utf8(slice) else "",
            "device" to r.key,
            "type" to kind,
            "direction" to if (dirIn) "in" else "out",
        )
    }

    private fun ensureRaw(deviceKey: String?): Raw {
        val cur = raw
        if (deviceKey.isNullOrBlank() && cur != null) return cur
        val key = deviceKey?.takeIf { it.isNotBlank() } ?: openDevice
            ?: error(tr("нужен параметр device", "device required"))
        if (cur != null && (cur.key == key || cur.dev.deviceName == key)) return cur
        closeRawLocked()
        val dev = requirePermitted(key)
        val conn = manager.openDevice(dev) ?: error("openDevice failed")
        return Raw(key(dev), dev, conn).also { raw = it }
    }

    private fun requirePermitted(deviceKey: String): UsbDevice {
        val dev = find(deviceKey) ?: error(tr("USB-устройство не найдено: $deviceKey", "USB device not found: $deviceKey"))
        if (!manager.hasPermission(dev)) {
            askPermission(dev)
            error(tr(
                "Нет разрешения на USB. Подтвердите системный диалог и повторите.",
                "USB permission requested; confirm the system dialog and retry.",
            ))
        }
        return dev
    }

    private fun askPermission(dev: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            context, dev.deviceId,
            Intent(ACTION_PERMISSION).setPackage(context.packageName).putExtra(UsbManager.EXTRA_DEVICE, dev),
            flags,
        )
        manager.requestPermission(dev, pi)
    }

    private fun claim(r: Raw, intf: UsbInterface) {
        if (r.claimed.add(intf.id)) {
            if (!r.conn.claimInterface(intf, true)) {
                r.claimed.remove(intf.id)
                error(tr("не удалось занять USB-интерфейс ${intf.id}", "failed to claim USB interface ${intf.id}"))
            }
        }
    }

    private fun hidInterface(dev: UsbDevice): UsbInterface {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) return intf
        }
        error(tr("на устройстве нет HID-интерфейса", "device has no HID interface"))
    }

    private fun pickInterface(dev: UsbDevice, interfaceId: Int?, endpointAddr: Int?): UsbInterface {
        if (interfaceId != null) {
            return (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == interfaceId }
                ?: error("USB interface $interfaceId not found")
        }
        if (endpointAddr != null) {
            for (i in 0 until dev.interfaceCount) {
                val intf = dev.getInterface(i)
                for (e in 0 until intf.endpointCount) {
                    if (intf.getEndpoint(e).address == endpointAddr) return intf
                }
            }
        }
        if (dev.interfaceCount == 1) return dev.getInterface(0)
        error(tr("укажите interface или endpoint", "interface or endpoint required"))
    }

    private fun pickEndpoint(intf: UsbInterface, address: Int?, direction: Int, type: String): UsbEndpoint {
        if (address != null) {
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.address == address) return ep
            }
            error("endpoint 0x${address.toString(16)} not found")
        }
        val wantType = if (type == "interrupt") UsbConstants.USB_ENDPOINT_XFER_INT else UsbConstants.USB_ENDPOINT_XFER_BULK
        return intf.endpointOrNull(direction, setOf(wantType))
            ?: intf.endpointOrNull(direction, interruptOrBulk())
            ?: error(tr("нет подходящей конечной точки", "no matching USB endpoint"))
    }

    private fun UsbInterface.endpointOrNull(direction: Int, types: Set<Int>): UsbEndpoint? {
        for (e in 0 until endpointCount) {
            val ep = getEndpoint(e)
            if (ep.direction == direction && ep.type in types) return ep
        }
        return null
    }

    private fun interruptOrBulk() = setOf(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_ENDPOINT_XFER_BULK)

    private fun closeSerialLocked() {
        runCatching { port?.close() }
        port = null
        openDevice = null
    }

    private fun closeRawLocked() {
        val r = raw
        raw = null
        if (r != null) {
            r.claimed.forEach { id ->
                runCatching {
                    val intf = (0 until r.dev.interfaceCount).map { r.dev.getInterface(it) }.firstOrNull { it.id == id }
                    if (intf != null) r.conn.releaseInterface(intf)
                }
            }
            runCatching { r.conn.close() }
        }
    }

    private fun interfacesOf(dev: UsbDevice): List<Map<String, Any?>> =
        (0 until dev.interfaceCount).map { i ->
            val intf = dev.getInterface(i)
            mapOf(
                "id" to intf.id,
                "class" to intf.interfaceClass,
                "subclass" to intf.interfaceSubclass,
                "protocol" to intf.interfaceProtocol,
                "endpoints" to (0 until intf.endpointCount).map { e ->
                    val ep = intf.getEndpoint(e)
                    mapOf(
                        "address" to ep.address,
                        "type" to epType(ep.type),
                        "direction" to if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                        "maxPacket" to ep.maxPacketSize,
                    )
                },
            )
        }

    private fun epType(t: Int) = when (t) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "other"
    }

    private fun find(key: String): UsbDevice? =
        manager.deviceList.values.firstOrNull {
            key(it) == key || it.deviceName == key || it.deviceName.endsWith(key) ||
                "%04x:%04x".format(it.vendorId, it.productId).equals(key, true)
        }

    private fun key(d: UsbDevice) = "%04x:%04x:%s".format(d.vendorId, d.productId, d.deviceName)

    companion object {
        const val ACTION_PERMISSION = "ru.dredd20.opencode.USB_PERMISSION"
    }
}
