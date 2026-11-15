package ru.dredd20.opencode.hardware

import android.content.Context
import org.json.JSONObject
import ru.dredd20.opencode.i18n.tr
import ru.dredd20.opencode.phone.PhoneConfirmer
import ru.dredd20.opencode.phone.PhoneRequest
import ru.dredd20.opencode.phone.PhoneResponse
import ru.dredd20.opencode.runtime.EmbeddedRuntime
import ru.dredd20.opencode.usb.UsbController
import java.io.File
import java.net.URI

class HardwareHandlers(
    private val context: Context,
    private val usb: UsbController,
    private val confirm: PhoneConfirmer,
    private val sessions: HardwareSessionStore,
    private val bluetooth: BluetoothController,
    private val wifi: WifiNetController,
) {
    fun usb(req: PhoneRequest): PhoneResponse {
        val argv = argv(req)
        val cmd = (req.argString("sub") ?: argv.getOrNull(0) ?: "list").lowercase()
        val device = req.argString("device") ?: argv.getOrNull(1)
        return when (cmd) {
            "list", "l" -> PhoneResponse.ok(req.id, usb.list())
            "permission", "request", "request-permission" -> {
                val dev = device ?: return PhoneResponse.error(req.id, "device required")
                PhoneResponse.ok(req.id, usb.requestPermission(dev))
            }
            "open" -> {
                val dev = device ?: return PhoneResponse.error(req.id, "device required")
                PhoneResponse.ok(req.id, usb.open(dev, req.argInt("baud", 9600)))
            }
            "write" -> PhoneResponse.ok(req.id, usb.write(req.text(), req.argString("hex")))
            "read" -> PhoneResponse.ok(req.id, usb.read(req.argInt("timeout_ms", 1000)))
            "close" -> PhoneResponse.ok(req.id, usb.close())
            "hid-read", "hid_read" -> PhoneResponse.ok(
                req.id,
                usb.hidRead(
                    device,
                    req.argInt("length", 64),
                    req.argInt("report_id", 0),
                    req.argInt("timeout_ms", 1000),
                ),
            )
            "hid-write", "hid_write" -> {
                val data = HardwareArgs.payload(req.text().ifBlank { argv.drop(2).joinToString(" ").ifBlank { null } }, req.argString("hex"))
                PhoneResponse.ok(
                    req.id,
                    usb.hidWrite(device, data, req.argInt("report_id", 0), req.argInt("timeout_ms", 1000)),
                )
            }
            "transfer" -> {
                val data = runCatching {
                    HardwareArgs.payload(req.argString("text") ?: req.text().ifBlank { null }, req.argString("hex"), required = false)
                }.getOrDefault(ByteArray(0))
                PhoneResponse.ok(
                    req.id,
                    usb.transfer(
                        deviceKey = device,
                        type = req.argString("type") ?: "bulk",
                        direction = req.argString("direction") ?: "in",
                        endpointAddr = req.args.optIntOrNull("endpoint"),
                        interfaceId = req.args.optIntOrNull("interface"),
                        data = data,
                        length = req.argInt("length", if (data.isNotEmpty()) data.size else 64),
                        timeoutMs = req.argInt("timeout_ms", 1000),
                        requestType = req.args.optIntOrNull("request_type"),
                        request = req.args.optIntOrNull("request"),
                        value = req.args.optIntOrNull("value"),
                        index = req.args.optIntOrNull("index"),
                    ),
                )
            }
            else -> PhoneResponse.error(req.id, "unknown usb subcommand $cmd")
        }
    }

    fun session(req: PhoneRequest): PhoneResponse {
        val argv = argv(req)
        val sub = (req.argString("sub") ?: argv.getOrNull(0) ?: "list").lowercase()
        val id = req.argString("session") ?: req.argString("id") ?: argv.getOrNull(1)
        return when (sub) {
            "list" -> PhoneResponse.ok(req.id, sessions.list())
            "open" -> {
                val transportName = req.argString("transport") ?: argv.getOrNull(1) ?: "usb-serial"
                val target = req.argString("target") ?: req.argString("device") ?: argv.getOrNull(2)
                val transport = openTransport(transportName, target, req)
                val session = sessions.open(transport, req.argString("session") ?: req.argString("name"), log = req.argBool("log", true))
                val log = sessions.logFile(session.id)
                PhoneResponse.ok(
                    req.id,
                    session.info() + mapOf(
                        "log" to log?.let { guestPath(it) },
                    ),
                )
            }
            "send" -> {
                val sid = id ?: return PhoneResponse.error(req.id, "session required")
                val data = HardwareArgs.payload(req.argString("text") ?: req.text().ifBlank { argv.drop(2).joinToString(" ").ifBlank { null } }, req.argString("hex"))
                val n = sessions.get(sid).send(data)
                PhoneResponse.ok(req.id, mapOf("written" to n, "session" to sid))
            }
            "read" -> {
                val sid = id ?: return PhoneResponse.error(req.id, "session required")
                val got = sessions.get(sid).read(req.argInt("timeout_ms", 1000))
                PhoneResponse.ok(req.id, mapOf("bytes" to got.size, "hex" to HardwareArgs.formatHex(got), "text" to HardwareArgs.utf8(got), "session" to sid))
            }
            "expect" -> {
                val sid = id ?: return PhoneResponse.error(req.id, "session required")
                val pat = req.argString("pattern") ?: argv.getOrNull(2) ?: return PhoneResponse.error(req.id, "pattern required")
                val r = sessions.get(sid).expect(
                    HardwareArgs.pattern(pat, req.argBool("literal")),
                    req.argInt("timeout_ms", 5_000),
                )
                PhoneResponse.ok(
                    req.id,
                    mapOf(
                        "matched" to r.matched,
                        "text" to r.text,
                        "hex" to r.hex,
                        "elapsed_ms" to r.elapsedMs,
                        "leftover" to r.leftover,
                        "session" to sid,
                    ),
                )
            }
            "log" -> {
                val sid = id ?: return PhoneResponse.error(req.id, "session required")
                val f = sessions.logFile(sid) ?: return PhoneResponse.error(req.id, "no log file")
                if (!f.isFile) return PhoneResponse.error(req.id, "log not found")
                PhoneResponse.ok(req.id, mapOf("file" to guestPath(f), "text" to f.readText().take(32_000), "bytes" to f.length()))
            }
            "close" -> {
                val sid = id ?: return PhoneResponse.error(req.id, "session required")
                PhoneResponse.ok(req.id, sessions.close(sid))
            }
            else -> PhoneResponse.error(req.id, "unknown hw-session subcommand $sub")
        }
    }

    fun bluetooth(req: PhoneRequest): PhoneResponse {
        val argv = argv(req)
        val sub = (req.argString("sub") ?: argv.getOrNull(0) ?: "scan").lowercase()
        return when (sub) {
            "scan" -> PhoneResponse.ok(req.id, bluetooth.scan(req.argInt("timeout_ms", 8_000), req.argBool("classic")))
            "connect" -> {
                val addr = req.argString("address") ?: argv.getOrNull(1) ?: return PhoneResponse.error(req.id, "address required")
                PhoneResponse.ok(req.id, bluetooth.connect(addr, req.argInt("timeout_ms", 12_000)))
            }
            "services", "chars", "characteristics" -> PhoneResponse.ok(req.id, bluetooth.services())
            "read" -> {
                val uuid = req.argString("uuid") ?: argv.getOrNull(1) ?: return PhoneResponse.error(req.id, "uuid required")
                PhoneResponse.ok(req.id, bluetooth.readChar(uuid, req.argInt("timeout_ms", 5_000)))
            }
            "write" -> {
                val uuid = req.argString("uuid") ?: argv.getOrNull(1) ?: return PhoneResponse.error(req.id, "uuid required")
                val data = HardwareArgs.payload(req.argString("text") ?: argv.drop(2).joinToString(" ").ifBlank { null }, req.argString("hex"))
                PhoneResponse.ok(req.id, bluetooth.writeChar(uuid, data, req.argInt("timeout_ms", 5_000)))
            }
            "notify" -> {
                val action = (req.argString("action") ?: argv.getOrNull(1) ?: "start").lowercase()
                val uuid = req.argString("uuid") ?: argv.getOrNull(2) ?: argv.getOrNull(1)
                    ?: return PhoneResponse.error(req.id, "uuid required")
                when (action) {
                    "wait" -> PhoneResponse.ok(req.id, bluetooth.notifyWait(uuid, req.argInt("timeout_ms", 5_000)))
                    "stop" -> PhoneResponse.ok(req.id, bluetooth.notify(uuid, false))
                    else -> PhoneResponse.ok(req.id, bluetooth.notify(uuid, true))
                }
            }
            "disconnect" -> PhoneResponse.ok(req.id, bluetooth.disconnect())
            "spp-connect", "spp_connect" -> {
                val addr = req.argString("address") ?: argv.getOrNull(1) ?: return PhoneResponse.error(req.id, "address required")
                PhoneResponse.ok(req.id, bluetooth.sppConnect(addr, req.argString("uuid"), req.argInt("timeout_ms", 10_000)))
            }
            "spp-send", "spp_send" -> {
                val data = HardwareArgs.payload(req.argString("text") ?: req.text().ifBlank { argv.drop(1).joinToString(" ").ifBlank { null } }, req.argString("hex"))
                PhoneResponse.ok(req.id, bluetooth.sppSend(data))
            }
            "spp-read", "spp_read" -> PhoneResponse.ok(req.id, bluetooth.sppRead(req.argInt("timeout_ms", 1000)))
            "spp-close", "spp_close" -> PhoneResponse.ok(req.id, bluetooth.sppClose())
            else -> PhoneResponse.error(req.id, "unknown bluetooth subcommand $sub")
        }
    }

    fun wifiScan(req: PhoneRequest): PhoneResponse =
        PhoneResponse.ok(req.id, wifi.scan(req.argInt("timeout_ms", 8_000)))

    fun wifiConnect(req: PhoneRequest): PhoneResponse {
        val ssid = req.argString("ssid") ?: argv(req).getOrNull(0) ?: return PhoneResponse.error(req.id, "ssid required")
        val password = req.argString("password")
        return PhoneResponse.ok(
            req.id,
            wifi.connect(ssid, password, req.argInt("timeout_ms", 30_000), req.argBool("suggestion")),
        )
    }

    fun net(req: PhoneRequest): PhoneResponse {
        val argv = argv(req)
        val sub = (req.argString("sub") ?: argv.getOrNull(0) ?: "ping").lowercase()
        val host = req.argString("host") ?: argv.getOrNull(1)
        return when (sub) {
            "ping" -> {
                val h = host ?: return PhoneResponse.error(req.id, "host required")
                PhoneResponse.ok(req.id, wifi.ping(h, req.argInt("count", 3), req.argInt("timeout_ms", 3_000)))
            }
            "dns" -> {
                val h = host ?: return PhoneResponse.error(req.id, "host required")
                PhoneResponse.ok(req.id, wifi.dns(h))
            }
            "port" -> {
                val h = host ?: return PhoneResponse.error(req.id, "host required")
                val port = req.argInt("port", argv.getOrNull(2)?.toIntOrNull() ?: 0)
                if (port !in 1..65535) return PhoneResponse.error(req.id, "port required")
                PhoneResponse.ok(req.id, wifi.port(h, port, req.argInt("timeout_ms", 3_000)))
            }
            "mdns" -> PhoneResponse.ok(req.id, wifi.mdns(req.argString("type") ?: argv.getOrNull(1), req.argInt("timeout_ms", 5_000)))
            "tcp", "tcp-send", "tcp_send" -> {
                val h = host ?: return PhoneResponse.error(req.id, "host required")
                val port = req.argInt("port", argv.getOrNull(2)?.toIntOrNull() ?: 0)
                if (port !in 1..65535) return PhoneResponse.error(req.id, "port required")
                val data = HardwareArgs.payload(req.argString("text") ?: argv.drop(3).joinToString(" ").ifBlank { null }, req.argString("hex"), required = false)
                PhoneResponse.ok(req.id, wifi.tcp(h, port, data, req.argInt("timeout_ms", 5_000)))
            }
            "udp", "udp-send", "udp_send" -> {
                val h = host ?: return PhoneResponse.error(req.id, "host required")
                val port = req.argInt("port", argv.getOrNull(2)?.toIntOrNull() ?: 0)
                if (port !in 1..65535) return PhoneResponse.error(req.id, "port required")
                val data = HardwareArgs.payload(req.argString("text") ?: argv.drop(3).joinToString(" ").ifBlank { null }, req.argString("hex"), required = false)
                PhoneResponse.ok(req.id, wifi.udp(h, port, data, req.argInt("timeout_ms", 3_000), req.args.optIntOrNull("listen_port")))
            }
            else -> PhoneResponse.error(req.id, "unknown net subcommand $sub")
        }
    }

    fun nfc(req: PhoneRequest): PhoneResponse {
        val argv = argv(req)
        val sub = (req.argString("sub") ?: argv.getOrNull(0) ?: "read").lowercase()
        val timeout = HardwareArgs.coerceTimeoutMs(req.argInt("timeout_ms", 15_000), 15_000, 90_000)
        val op = when (sub) {
            "info", "wait" -> NfcBridge.Op.INFO
            "read" -> NfcBridge.Op.READ
            "write" -> NfcBridge.Op.WRITE
            "transceive" -> NfcBridge.Op.TRANSCEIVE
            else -> return PhoneResponse.error(req.id, "unknown nfc subcommand $sub")
        }
        if (op == NfcBridge.Op.WRITE) {
            if (!confirm.await(req, tr("Записать NDEF на NFC-метку?", "Write NDEF to the NFC tag?"))) {
                return PhoneResponse.error(req.id, tr("отменено пользователем", "cancelled by the user"), needsConfirm = true)
            }
        }
        val writeMsg = if (op == NfcBridge.Op.WRITE) {
            NfcBridge.messageFrom(req.argString("text") ?: req.text().ifBlank { null }, req.argString("uri"), req.argString("hex"))
        } else null
        val payload = if (op == NfcBridge.Op.TRANSCEIVE) {
            HardwareArgs.payload(req.argString("text")?.takeIf { it.isNotBlank() }, req.argString("hex"))
        } else null
        val data = NfcBridge.await(
            context,
            NfcBridge.Request(op, timeout, writeMsg, payload, req.argString("tech")),
        )
        return PhoneResponse.ok(req.id, data)
    }

    fun cameraStart(req: PhoneRequest): PhoneResponse {
        if (!confirm.await(req, tr("Запустить захват кадров с камеры? Появится постоянное уведомление.", "Start camera frame capture? A persistent notification will be shown."))) {
            return PhoneResponse.error(req.id, tr("отменено пользователем", "cancelled by the user"), needsConfirm = true)
        }
        val res = HardwareArgs.parseResolution(req.argString("resolution"))
        val dir = cameraDir(req)
        val result = CameraCaptureService.start(
            context,
            HardwareArgs.coerceIntervalMs(req.argInt("interval_ms", 500)),
            HardwareArgs.coerceRing(req.argInt("ring", 8)),
            res?.first,
            res?.second,
            dir,
        )
        return PhoneResponse.ok(req.id, result)
    }

    fun cameraStop(req: PhoneRequest): PhoneResponse =
        PhoneResponse.ok(req.id, CameraCaptureService.stop(context))

    fun cameraLatest(req: PhoneRequest): PhoneResponse =
        PhoneResponse.ok(req.id, CameraCaptureService.latest(context))

    fun cameraCapture(req: PhoneRequest): PhoneResponse {
        val already = CameraCaptureService.running.get()
        if (!already) {
            if (!confirm.await(req, tr("Сделать кадр с камеры (CameraX)?", "Capture a camera frame (CameraX)?"))) {
                return PhoneResponse.error(req.id, tr("отменено пользователем", "cancelled by the user"), needsConfirm = true)
            }
        }
        val res = HardwareArgs.parseResolution(req.argString("resolution"))
        val file = if (already) CameraCaptureService.snapWait(context)
        else CameraCaptureService.startAndSnap(context, res?.first, res?.second, cameraDir(req))
        return PhoneResponse.ok(
            req.id,
            mapOf(
                "file" to CameraCaptureService.guestPath(context, file),
                "bytes" to file.length(),
                "running" to CameraCaptureService.running.get(),
            ),
        )
    }

    private fun cameraDir(req: PhoneRequest): File? {
        val raw = req.argString("dir") ?: req.argString("file")?.let { File(it).parent }
        if (raw.isNullOrBlank()) return null
        val f = resolveGuest(raw)
        f.mkdirs()
        return f
    }

    private fun openTransport(name: String, target: String?, req: PhoneRequest): HardwareTransport {
        return when (name.lowercase()) {
            "usb", "usb-serial", "serial" -> {
                if (!target.isNullOrBlank() && runCatching { usb.serialTransport() }.isFailure) {
                    usb.open(target, req.argInt("baud", 9600))
                }
                usb.serialTransport()
            }
            "bt-spp", "spp", "bluetooth", "bt" -> {
                if (!target.isNullOrBlank()) {
                    runCatching { bluetooth.sppTransport() }.onFailure {
                        bluetooth.sppConnect(target, req.argString("uuid"), req.argInt("timeout_ms", 10_000))
                    }
                }
                bluetooth.sppTransport()
            }
            "tcp" -> {
                val spec = target ?: error("target required (host:port)")
                val (host, port) = parseHostPort(spec)
                TcpTransport(host, port, req.argInt("timeout_ms", 5_000))
            }
            else -> error(tr("транспорт $name не поддержан (usb-serial, bt-spp, tcp)", "unsupported transport $name (usb-serial, bt-spp, tcp)"))
        }
    }

    private fun parseHostPort(spec: String): Pair<String, Int> {
        val trimmed = spec.trim()
        if (trimmed.contains("://")) {
            val u = URI(trimmed)
            val port = if (u.port > 0) u.port else 80
            return (u.host ?: error("host required")) to port
        }
        val idx = trimmed.lastIndexOf(':')
        require(idx > 0) { "target must look like host:port" }
        val host = trimmed.substring(0, idx)
        val port = trimmed.substring(idx + 1).toInt()
        require(port in 1..65535) { "port out of range" }
        return host to port
    }

    private fun argv(req: PhoneRequest): List<String> {
        val arr = req.args.optJSONArray("argv") ?: return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun guestPath(file: File): String {
        val root = EmbeddedRuntime.hostDir(context)
        val rel = file.relativeToOrNull(root)?.invariantSeparatorsPath
        return if (rel != null) "/opencode-host/$rel" else file.absolutePath
    }

    private fun resolveGuest(path: String): File {
        if (path.startsWith("/opencode-host/")) return File(EmbeddedRuntime.hostDir(context), path.removePrefix("/opencode-host/"))
        if (path.startsWith("/")) return File(path)
        return File(EmbeddedRuntime.hostDir(context), path)
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
