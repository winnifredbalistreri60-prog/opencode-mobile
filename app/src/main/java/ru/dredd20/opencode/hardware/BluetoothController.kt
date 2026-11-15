package ru.dredd20.opencode.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.phone.PermissionRequests
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("MissingPermission")
class BluetoothController(private val context: Context) {
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var gattAddress: String? = null
    private val servicesReady = AtomicReference<CountDownLatch?>(null)
    private val opLatch = AtomicReference<CountDownLatch?>(null)
    private val opResult = AtomicReference<ByteArray?>(null)
    private val opError = AtomicReference<String?>(null)
    private val notifies = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val notifyWaiters = ConcurrentHashMap<String, CountDownLatch>()

    private var spp: BluetoothSocket? = null
    private var sppAddress: String? = null

    fun permissions(): Array<String> = PermissionRequests.bluetooth()

    fun scan(timeoutMs: Int, classic: Boolean): Map<String, Any?> {
        val ad = readyAdapter()
        val timeout = HardwareArgs.coerceTimeoutMs(timeoutMs, 8_000, 30_000)
        val found = ConcurrentHashMap<String, Map<String, Any?>>()
        val bleLatch = CountDownLatch(1)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device ?: return
                val addr = d.address ?: return
                found[addr] = mapOf(
                    "address" to addr,
                    "name" to (d.name ?: result.scanRecord?.deviceName),
                    "rssi" to result.rssi,
                    "kind" to "ble",
                    "connectable" to if (Build.VERSION.SDK_INT >= 26) result.isConnectable else true,
                )
            }
            override fun onScanFailed(errorCode: Int) {
                opError.set("BLE scan failed ($errorCode)")
                bleLatch.countDown()
            }
        }
        val scanner = ad.bluetoothLeScanner
            ?: error(tr("BLE сканер недоступен (адаптер выключен?)", "BLE scanner unavailable (is Bluetooth off?)"))
        scanner.startScan(cb)
        val classicReceiver = if (classic) classicScan(ad, found) else null
        try {
            bleLatch.await(timeout.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            runCatching { scanner.stopScan(cb) }
            if (classic) runCatching { ad.cancelDiscovery() }
            if (classicReceiver != null) runCatching { context.unregisterReceiver(classicReceiver) }
        }
        opError.getAndSet(null)?.let { error(it) }
        return mapOf(
            "count" to found.size,
            "devices" to found.values.sortedBy { it["address"] as String },
            "classic" to classic,
            "timeout_ms" to timeout,
        )
    }

    private fun classicScan(ad: BluetoothAdapter, found: ConcurrentHashMap<String, Map<String, Any?>>): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val d = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val addr = d.address ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                found.putIfAbsent(
                    addr,
                    mapOf(
                        "address" to addr,
                        "name" to d.name,
                        "rssi" to rssi,
                        "kind" to "classic",
                    ),
                )
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        ad.startDiscovery()
        return receiver
    }

    fun connect(address: String, timeoutMs: Int): Map<String, Any?> = synchronized(lock) {
        val ad = readyAdapter()
        val dev = ad.getRemoteDevice(address.trim())
        disconnectGattLocked()
        val ready = CountDownLatch(1)
        servicesReady.set(ready)
        opError.set(null)
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    opError.compareAndSet(null, "disconnected (status=$status)")
                    servicesReady.get()?.countDown()
                    opLatch.get()?.countDown()
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    opError.compareAndSet(null, "connect failed (status=$status)")
                    servicesReady.get()?.countDown()
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) opError.compareAndSet(null, "service discovery failed ($status)")
                servicesReady.get()?.countDown()
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                finishOp(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null, status, "read")
            }
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                finishOp(if (status == BluetoothGatt.GATT_SUCCESS) value else null, status, "read")
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                finishOp(c.value, status, "write")
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                onNotify(c.uuid.toString(), c.value ?: ByteArray(0))
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                onNotify(c.uuid.toString(), value)
            }
        }
        val g = if (Build.VERSION.SDK_INT >= 23) {
            dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(context, false, cb)
        } ?: error("connectGatt returned null")
        gatt = g
        gattAddress = dev.address
        val ok = ready.await(HardwareArgs.coerceTimeoutMs(timeoutMs, 12_000, 30_000).toLong(), TimeUnit.MILLISECONDS)
        if (!ok) {
            disconnectGattLocked()
            error(tr("таймаут BLE-подключения к ${dev.address}", "BLE connect timed out for ${dev.address}"))
        }
        opError.getAndSet(null)?.let {
            disconnectGattLocked()
            error(it)
        }
        mapOf(
            "connected" to dev.address,
            "name" to dev.name,
            "services" to serviceList(g),
        )
    }

    fun services(): Map<String, Any?> {
        val g = gatt ?: error(tr("нет активного BLE-подключения", "no active BLE connection"))
        return mapOf("address" to gattAddress, "services" to serviceList(g))
    }

    fun readChar(uuid: String, timeoutMs: Int): Map<String, Any?> {
        val g = gatt ?: error(tr("нет активного BLE-подключения", "no active BLE connection"))
        val ch = findChar(g, uuid)
        beginOp()
        if (!g.readCharacteristic(ch)) error("readCharacteristic rejected")
        val value = awaitOp("read", timeoutMs)
        return mapOf("uuid" to ch.uuid.toString(), "hex" to HardwareArgs.formatHex(value), "text" to HardwareArgs.utf8(value), "bytes" to value.size)
    }

    fun writeChar(uuid: String, data: ByteArray, timeoutMs: Int): Map<String, Any?> {
        val g = gatt ?: error(tr("нет активного BLE-подключения", "no active BLE connection"))
        val ch = findChar(g, uuid)
        beginOp()
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        if (!ok) error("writeCharacteristic rejected")
        awaitOp("write", timeoutMs)
        return mapOf("uuid" to ch.uuid.toString(), "written" to data.size)
    }

    fun notify(uuid: String, enable: Boolean): Map<String, Any?> {
        val g = gatt ?: error(tr("нет активного BLE-подключения", "no active BLE connection"))
        val ch = findChar(g, uuid)
        if (!g.setCharacteristicNotification(ch, enable)) error("setCharacteristicNotification failed")
        val desc = ch.getDescriptor(CCCD) ?: error("CCCD descriptor not found")
        val payload = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(desc, payload) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            desc.value = payload
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
        }
        if (!ok) error("writeDescriptor rejected")
        val key = ch.uuid.toString()
        if (!enable) {
            notifies.remove(key)
            notifyWaiters.remove(key)?.countDown()
        } else {
            notifies.putIfAbsent(key, ArrayDeque())
        }
        return mapOf("uuid" to key, "notify" to enable)
    }

    fun notifyWait(uuid: String, timeoutMs: Int): Map<String, Any?> {
        val key = normalizeUuid(uuid)
        val q = notifies[key] ?: notifies.entries.firstOrNull { it.key.equals(key, true) }?.value
            ?: error(tr("уведомления не включены для $uuid", "notifications are not enabled for $uuid"))
        synchronized(q) {
            if (q.isNotEmpty()) {
                val v = q.removeFirst()
                return mapOf("uuid" to key, "hex" to HardwareArgs.formatHex(v), "text" to HardwareArgs.utf8(v), "bytes" to v.size, "queued" to q.size)
            }
        }
        val latch = CountDownLatch(1)
        notifyWaiters[key] = latch
        latch.await(HardwareArgs.coerceTimeoutMs(timeoutMs, 5_000, 60_000).toLong(), TimeUnit.MILLISECONDS)
        notifyWaiters.remove(key, latch)
        val v = synchronized(q) { if (q.isNotEmpty()) q.removeFirst() else null }
            ?: error(tr("нет уведомления за отведённое время", "no notification within the timeout"))
        return mapOf("uuid" to key, "hex" to HardwareArgs.formatHex(v), "text" to HardwareArgs.utf8(v), "bytes" to v.size)
    }

    fun disconnect(): Map<String, Any?> = synchronized(lock) {
        val addr = gattAddress
        disconnectGattLocked()
        mapOf("disconnected" to addr)
    }

    fun sppConnect(address: String, uuid: String?, timeoutMs: Int): Map<String, Any?> = synchronized(lock) {
        val ad = readyAdapter()
        runCatching { spp?.close() }
        spp = null
        val dev = ad.getRemoteDevice(address.trim())
        val u = uuid?.let { UUID.fromString(normalizeUuid(it)) } ?: SPP_UUID
        val sock = dev.createRfcommSocketToServiceRecord(u)
        // Discovery cancels or delays RFCOMM connects.
        runCatching { ad.cancelDiscovery() }
        val deadline = System.currentTimeMillis() + HardwareArgs.coerceTimeoutMs(timeoutMs, 10_000, 30_000)
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                sock.connect()
                last = null
                break
            } catch (e: IOException) {
                last = e
                Thread.sleep(200)
            }
        }
        if (!sock.isConnected) {
            runCatching { sock.close() }
            error(tr("SPP подключение не удалось: ${last?.message}", "SPP connect failed: ${last?.message}"))
        }
        spp = sock
        sppAddress = dev.address
        mapOf("connected" to dev.address, "name" to dev.name, "uuid" to u.toString(), "transport" to "bt-spp")
    }

    fun sppSend(data: ByteArray): Map<String, Any?> {
        val s = spp ?: error(tr("нет активного SPP-подключения", "no active SPP connection"))
        s.outputStream.write(data)
        s.outputStream.flush()
        return mapOf("written" to data.size)
    }

    fun sppRead(timeoutMs: Int): Map<String, Any?> {
        val s = spp ?: error(tr("нет активного SPP-подключения", "no active SPP connection"))
        val buf = ByteArray(4096)
        val n = readWithTimeout(s, buf, HardwareArgs.coerceTimeoutMs(timeoutMs, 1_000, 30_000))
        val slice = if (n > 0) buf.copyOf(n) else ByteArray(0)
        return mapOf("bytes" to n.coerceAtLeast(0), "hex" to HardwareArgs.formatHex(slice), "text" to HardwareArgs.utf8(slice))
    }

    fun sppClose(): Map<String, Any?> {
        val addr = sppAddress
        runCatching { spp?.close() }
        spp = null
        sppAddress = null
        return mapOf("closed" to addr)
    }

    fun sppTransport(): HardwareTransport {
        val s = spp ?: error(tr("нет активного SPP-подключения (сначала bluetooth spp-connect)", "no active SPP connection (run bluetooth spp-connect first)"))
        val target = sppAddress ?: "spp"
        return object : HardwareTransport {
            override val kind = "bt-spp"
            override val target: String = target
            override fun send(data: ByteArray) {
                s.outputStream.write(data)
                s.outputStream.flush()
            }
            override fun read(timeoutMs: Int): ByteArray {
                val buf = ByteArray(4096)
                val n = readWithTimeout(s, buf, timeoutMs)
                return if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
            override fun close() { /* session must not drop the shared socket */ }
        }
    }

    private fun onNotify(key: String, value: ByteArray) {
        val q = notifies.getOrPut(key) { ArrayDeque() }
        synchronized(q) {
            q.addLast(value)
            while (q.size > 32) q.removeFirst()
        }
        notifyWaiters.remove(key)?.countDown()
    }

    private fun readWithTimeout(s: BluetoothSocket, buf: ByteArray, timeoutMs: Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        val input = s.inputStream
        while (true) {
            val available = runCatching { input.available() }.getOrDefault(0)
            if (available > 0) return input.read(buf)
            if (System.currentTimeMillis() >= deadline) return 0
            Thread.sleep(15)
        }
    }

    private fun readyAdapter(): BluetoothAdapter {
        val ad = adapter ?: error(tr("Bluetooth на этом устройстве нет", "Bluetooth is not available on this device"))
        if (!ad.isEnabled) error(tr("Bluetooth выключен", "Bluetooth is turned off"))
        return ad
    }

    private fun serviceList(g: BluetoothGatt): List<Map<String, Any?>> =
        g.services.orEmpty().map { svc ->
            mapOf(
                "uuid" to svc.uuid.toString(),
                "characteristics" to svc.characteristics.map { ch ->
                    mapOf(
                        "uuid" to ch.uuid.toString(),
                        "properties" to ch.properties,
                        "notify" to (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0),
                        "read" to (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0),
                        "write" to (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                            ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0),
                    )
                },
            )
        }

    private fun findChar(g: BluetoothGatt, uuid: String): BluetoothGattCharacteristic {
        val want = normalizeUuid(uuid)
        g.services.orEmpty().forEach { svc ->
            svc.characteristics.forEach { ch ->
                if (ch.uuid.toString().equals(want, true)) return ch
            }
        }
        error(tr("характеристика не найдена: $uuid", "characteristic not found: $uuid"))
    }

    private fun beginOp() {
        opResult.set(null)
        opError.set(null)
        opLatch.set(CountDownLatch(1))
    }

    private fun finishOp(value: ByteArray?, status: Int, what: String) {
        if (status != BluetoothGatt.GATT_SUCCESS) opError.set("$what failed (status=$status)")
        else opResult.set(value ?: ByteArray(0))
        opLatch.get()?.countDown()
    }

    private fun awaitOp(what: String, timeoutMs: Int): ByteArray {
        val latch = opLatch.get() ?: error("internal: no GATT op latch")
        val ok = latch.await(HardwareArgs.coerceTimeoutMs(timeoutMs, 5_000, 30_000).toLong(), TimeUnit.MILLISECONDS)
        opError.getAndSet(null)?.let { error(it) }
        if (!ok) error(tr("таймаут BLE $what", "BLE $what timed out"))
        return opResult.get() ?: ByteArray(0)
    }

    private fun disconnectGattLocked() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        gattAddress = null
        notifies.clear()
        notifyWaiters.values.forEach { it.countDown() }
        notifyWaiters.clear()
        servicesReady.get()?.countDown()
        opLatch.get()?.countDown()
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun normalizeUuid(raw: String): String {
            val s = raw.trim()
            if (s.length == 4) return "0000${s.lowercase()}-0000-1000-8000-00805f9b34fb"
            return s.lowercase()
        }
    }
}
