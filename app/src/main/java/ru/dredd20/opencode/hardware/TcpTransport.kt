package ru.dredd20.opencode.hardware

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class TcpTransport(
    private val host: String,
    private val port: Int,
    connectTimeoutMs: Int = 5_000,
) : HardwareTransport {
    override val kind: String = "tcp"
    override val target: String = "$host:$port"
    private val socket = Socket()

    init {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs.coerceIn(200, 30_000))
    }

    override fun send(data: ByteArray) {
        socket.getOutputStream().write(data)
        socket.getOutputStream().flush()
    }

    override fun read(timeoutMs: Int): ByteArray {
        socket.soTimeout = timeoutMs.coerceIn(1, 90_000)
        val buf = ByteArray(4096)
        return try {
            val n = socket.getInputStream().read(buf)
            if (n > 0) buf.copyOf(n) else ByteArray(0)
        } catch (_: SocketTimeoutException) {
            ByteArray(0)
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
