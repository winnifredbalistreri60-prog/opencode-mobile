package ru.dredd20.opencode.hardware

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.phone.PermissionRequests
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WifiNetController(private val context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var networkCb: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    fun wifiScanPermissions(): Array<String> = PermissionRequests.wifiScan()

    @SuppressLint("MissingPermission")
    fun scan(timeoutMs: Int): Map<String, Any?> {
        val timeout = HardwareArgs.coerceTimeoutMs(timeoutMs, 8_000, 20_000)
        val latch = CountDownLatch(1)
        val updated = AtomicReference(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                updated.set(true)
                latch.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        val started = try {
            @Suppress("DEPRECATION")
            wifi.startScan()
        } catch (e: SecurityException) {
            runCatching { context.unregisterReceiver(receiver) }
            throw e
        }
        try {
            if (started) latch.await(timeout.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        val results = runCatching { wifi.scanResults }.getOrDefault(emptyList())
        val list = results.map { r ->
            mapOf(
                "ssid" to (r.SSID ?: ""),
                "bssid" to r.BSSID,
                "rssi" to r.level,
                "frequency" to r.frequency,
                "capabilities" to r.capabilities,
            )
        }
        val throttled = !started
        return mapOf(
            "count" to list.size,
            "networks" to list,
            "throttled" to throttled,
            "fresh" to updated.get(),
            "message" to if (throttled) tr(
                "Android ограничил частоту сканирования Wi-Fi (обычно не больше 4 раз за 2 минуты). Показаны последние известные сети.",
                "Android throttled Wi-Fi scans (usually at most 4 times per 2 minutes). Returning the last known results.",
            ) else null,
        )
    }

    @SuppressLint("MissingPermission", "Deprecated")
    fun connectionInfo(): Map<String, Any?> {
        val info = wifi.connectionInfo
        val ip = info?.ipAddress ?: 0
        val ipStr = if (ip != 0) {
            "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } else null
        return mapOf(
            "ssid" to info?.ssid?.trim('"'),
            "bssid" to info?.bssid,
            "rssi" to info?.rssi,
            "link_speed" to info?.linkSpeed,
            "ip" to ipStr,
            "ip_raw" to ip,
            "hidden" to info?.hiddenSSID,
            "supplicant" to info?.supplicantState?.name,
        )
    }

    fun connect(ssid: String, password: String?, timeoutMs: Int, suggestion: Boolean): Map<String, Any?> {
        val name = ssid.trim().trim('"')
        if (name.isEmpty()) error("ssid required")
        val timeout = HardwareArgs.coerceTimeoutMs(timeoutMs, 30_000, 60_000)
        if (Build.VERSION.SDK_INT < 29) {
            error(tr(
                "Подключение к Wi-Fi через приложение доступно с Android 10. На этой версии откройте системные настройки сети.",
                "In-app Wi-Fi connect needs Android 10+. On this version open system network settings.",
            ))
        }
        return if (suggestion) suggest(name, password) else specifier(name, password, timeout)
    }

    fun disconnect(): Map<String, Any?> = synchronized(lock) {
        val cb = networkCb
        networkCb = null
        val net = boundNetwork
        boundNetwork = null
        if (cb != null && Build.VERSION.SDK_INT >= 29) runCatching { cm.unregisterNetworkCallback(cb) }
        if (Build.VERSION.SDK_INT >= 23) runCatching { cm.bindProcessToNetwork(null) }
        mapOf("disconnected" to (net != null))
    }

    @SuppressLint("NewApi")
    private fun specifier(ssid: String, password: String?, timeoutMs: Int): Map<String, Any?> {
        val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (!password.isNullOrEmpty()) builder.setWpa2Passphrase(password)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(builder.build())
            .build()
        val latch = CountDownLatch(1)
        val available = AtomicReference<Network?>(null)
        val err = AtomicReference<String?>(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.set(network)
                if (Build.VERSION.SDK_INT >= 23) runCatching { cm.bindProcessToNetwork(network) }
                latch.countDown()
            }
            override fun onUnavailable() {
                err.set(tr("Пользователь отклонил сеть или сеть недоступна", "The user declined the network or it is unavailable"))
                latch.countDown()
            }
        }
        synchronized(lock) {
            networkCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            networkCb = cb
            boundNetwork = null
        }
        cm.requestNetwork(request, cb, Handler(Looper.getMainLooper()))
        val ok = latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        err.get()?.let { error(it) }
        if (!ok) error(tr("таймаут ожидания подтверждения Wi-Fi", "timed out waiting for Wi-Fi confirmation"))
        val net = available.get() ?: error(tr("сеть не появилась", "network did not become available"))
        synchronized(lock) { boundNetwork = net }
        return mapOf(
            "connected" to true,
            "ssid" to ssid,
            "method" to "specifier",
            "message" to tr(
                "Локальное подключение через системный запрос. Интернет по умолчанию может остаться на прежней сети.",
                "Local connection via the system prompt. Default internet may stay on the previous network.",
            ),
        )
    }

    @SuppressLint("NewApi")
    private fun suggest(ssid: String, password: String?): Map<String, Any?> {
        if (Build.VERSION.SDK_INT < 29) error("WifiNetworkSuggestion requires Android 10+")
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
        if (!password.isNullOrEmpty()) b.setWpa2Passphrase(password)
        val status = wifi.addNetworkSuggestions(listOf(b.build()))
        val ok = status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        if (!ok) error(tr("не удалось добавить Wi-Fi suggestion (код $status)", "failed to add a Wi-Fi suggestion (code $status)"))
        return mapOf(
            "suggested" to true,
            "ssid" to ssid,
            "method" to "suggestion",
            "message" to tr(
                "Сеть предложена системе. Android может показать уведомление или подключиться сам, когда сеть рядом.",
                "The network was suggested to the system. Android may show a notification or connect on its own when the network is nearby.",
            ),
        )
    }

    fun ping(host: String, count: Int, timeoutMs: Int): Map<String, Any?> {
        val n = count.coerceIn(1, 10)
        val timeoutSec = (HardwareArgs.coerceTimeoutMs(timeoutMs, 3_000, 15_000) + 999) / 1000
        val start = System.currentTimeMillis()
        val pb = ProcessBuilder("/system/bin/ping", "-c", n.toString(), "-W", timeoutSec.toString(), host)
            .redirectErrorStream(true)
        val proc = runCatching { pb.start() }.getOrNull()
        if (proc != null) {
            val finished = proc.waitFor((timeoutSec * n + 2).toLong(), TimeUnit.SECONDS)
            if (!finished) runCatching { proc.destroy() }
            val out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("")
            val code = if (finished) proc.exitValue() else -1
            if (out.isNotBlank() && (code == 0 || out.contains("bytes from") || out.contains("rtt"))) {
                return mapOf(
                    "host" to host,
                    "ok" to (code == 0),
                    "method" to "icmp",
                    "output" to out.take(2000),
                    "elapsed_ms" to System.currentTimeMillis() - start,
                )
            }
        }
        val addr = InetAddress.getByName(host)
        val ok = addr.isReachable(HardwareArgs.coerceTimeoutMs(timeoutMs, 3_000, 15_000))
        return mapOf(
            "host" to host,
            "ip" to addr.hostAddress,
            "ok" to ok,
            "method" to "reachable",
            "elapsed_ms" to System.currentTimeMillis() - start,
            "message" to tr(
                "ICMP ping недоступен, использован InetAddress.isReachable (ICMP или TCP 7).",
                "ICMP ping is unavailable; used InetAddress.isReachable (ICMP or TCP port 7).",
            ),
        )
    }

    fun dns(host: String): Map<String, Any?> {
        val start = System.currentTimeMillis()
        val addrs = InetAddress.getAllByName(host)
        return mapOf(
            "host" to host,
            "addresses" to addrs.map { it.hostAddress },
            "elapsed_ms" to System.currentTimeMillis() - start,
        )
    }

    fun port(host: String, port: Int, timeoutMs: Int): Map<String, Any?> {
        require(port in 1..65535) { "port out of range" }
        val timeout = HardwareArgs.coerceTimeoutMs(timeoutMs, 3_000, 15_000)
        val start = System.currentTimeMillis()
        val sock = Socket()
        return try {
            sock.connect(InetSocketAddress(host, port), timeout)
            mapOf(
                "host" to host,
                "port" to port,
                "open" to true,
                "elapsed_ms" to System.currentTimeMillis() - start,
            )
        } catch (e: Exception) {
            mapOf(
                "host" to host,
                "port" to port,
                "open" to false,
                "error" to (e.message ?: e.toString()),
                "elapsed_ms" to System.currentTimeMillis() - start,
            )
        } finally {
            runCatching { sock.close() }
        }
    }

    fun mdns(serviceType: String?, timeoutMs: Int): Map<String, Any?> {
        val type = serviceType?.takeIf { it.isNotBlank() } ?: "_services._dns-sd._udp"
        val timeout = HardwareArgs.coerceTimeoutMs(timeoutMs, 5_000, 20_000)
        val found = ConcurrentHashMap<String, Map<String, Any?>>()
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                latch.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) { latch.countDown() }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val key = "${serviceInfo.serviceName}|${serviceInfo.serviceType}"
                found[key] = mapOf(
                    "name" to serviceInfo.serviceName,
                    "type" to serviceInfo.serviceType,
                    "host" to serviceInfo.host?.hostAddress,
                    "port" to serviceInfo.port,
                )
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            found[key] = mapOf(
                                "name" to si.serviceName,
                                "type" to si.serviceType,
                                "host" to (si.host?.hostAddress ?: si.host?.hostName),
                                "port" to si.port,
                            )
                        }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove("${serviceInfo.serviceName}|${serviceInfo.serviceType}")
            }
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            latch.await(timeout.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            Thread.sleep(200)
        }
        return mapOf("type" to type, "count" to found.size, "services" to found.values.toList())
    }

    fun tcp(host: String, port: Int, data: ByteArray, timeoutMs: Int): Map<String, Any?> {
        require(port in 1..65535) { "port out of range" }
        val timeout = HardwareArgs.coerceTimeoutMs(timeoutMs, 5_000, 30_000)
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), timeout)
            if (data.isNotEmpty()) {
                sock.getOutputStream().write(data)
                sock.getOutputStream().flush()
            }
            sock.soTimeout = timeout
            val buf = ByteArray(8192)
            val n = try {
                sock.getInputStream().read(buf)
            } catch (_: SocketTimeoutException) {
                0
            }
            val slice = if (n > 0) buf.copyOf(n) else ByteArray(0)
            return mapOf(
                "host" to host,
                "port" to port,
                "written" to data.size,
                "bytes" to slice.size,
                "hex" to HardwareArgs.formatHex(slice),
                "text" to HardwareArgs.utf8(slice),
            )
        }
    }

    fun udp(host: String, port: Int, data: ByteArray, timeoutMs: Int, listenPort: Int?): Map<String, Any?> {
        require(port in 1..65535) { "port out of range" }
        val timeout = HardwareArgs.coerceTimeoutMs(timeoutMs, 3_000, 30_000)
        DatagramSocket(listenPort ?: 0).use { sock ->
            sock.soTimeout = timeout
            if (data.isNotEmpty()) {
                val addr = InetAddress.getByName(host)
                sock.send(DatagramPacket(data, data.size, addr, port))
            }
            val buf = ByteArray(8192)
            return try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val slice = packet.data.copyOf(packet.length)
                mapOf(
                    "host" to (packet.address?.hostAddress ?: host),
                    "port" to packet.port,
                    "written" to data.size,
                    "bytes" to slice.size,
                    "hex" to HardwareArgs.formatHex(slice),
                    "text" to HardwareArgs.utf8(slice),
                    "local_port" to sock.localPort,
                )
            } catch (_: SocketTimeoutException) {
                mapOf(
                    "host" to host,
                    "port" to port,
                    "written" to data.size,
                    "bytes" to 0,
                    "timed_out" to true,
                    "local_port" to sock.localPort,
                )
            }
        }
    }
}
