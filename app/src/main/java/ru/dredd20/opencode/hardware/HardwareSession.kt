package ru.dredd20.opencode.hardware

import java.io.File
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface HardwareTransport {
    val kind: String
    val target: String
    fun send(data: ByteArray)
    fun read(timeoutMs: Int): ByteArray
    fun close()
}

data class ExpectResult(
    val matched: Boolean,
    val text: String,
    val hex: String,
    val elapsedMs: Long,
    val leftover: String,
)

/**
 * A named I/O session over USB serial, Bluetooth SPP or TCP.
 * Incoming bytes stay in a pending buffer so [expect] can match across reads.
 */
class HardwareSession(
    val id: String,
    val transport: HardwareTransport,
    private val logWriter: Writer? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val pending = StringBuilder()
    @Volatile var closed: Boolean = false
        private set

    fun send(data: ByteArray): Int = synchronized(lock) {
        checkOpen()
        transport.send(data)
        log("TX", data)
        data.size
    }

    fun read(timeoutMs: Int): ByteArray = synchronized(lock) {
        checkOpen()
        val first = drainPendingBytes()
        if (first.isNotEmpty()) {
            log("RX", first)
            return first
        }
        val got = transport.read(timeoutMs.coerceIn(0, 90_000))
        if (got.isNotEmpty()) log("RX", got)
        got
    }

    fun expect(pattern: Regex, timeoutMs: Int): ExpectResult {
        synchronized(lock) {
        checkOpen()
        val start = clock()
        val deadline = start + timeoutMs.coerceIn(1, 90_000)
        val buf = StringBuilder(pending)
        pending.setLength(0)
        while (true) {
            val m = pattern.find(buf)
            if (m != null) {
                val consumed = buf.substring(0, m.range.last + 1)
                val leftover = buf.substring(m.range.last + 1)
                pending.append(leftover)
                val bytes = consumed.toByteArray(Charsets.UTF_8)
                log("RX", bytes, extra = "expect matched ${pattern.pattern}")
                return ExpectResult(
                    matched = true,
                    text = consumed,
                    hex = HardwareArgs.formatHex(bytes),
                    elapsedMs = (clock() - start).coerceAtLeast(0),
                    leftover = leftover,
                )
            }
            val left = deadline - clock()
            if (left <= 0) {
                pending.append(buf)
                val bytes = buf.toString().toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty()) log("RX", bytes, extra = "expect timeout ${pattern.pattern}")
                return ExpectResult(
                    matched = false,
                    text = buf.toString(),
                    hex = HardwareArgs.formatHex(bytes),
                    elapsedMs = (clock() - start).coerceAtLeast(0),
                    leftover = "",
                )
            }
            val chunk = transport.read(left.toInt().coerceAtLeast(1))
            if (chunk.isNotEmpty()) buf.append(String(chunk, Charsets.UTF_8))
        }
        }
    }

    fun close(): Map<String, Any?> = synchronized(lock) {
        if (!closed) {
            closed = true
            runCatching { transport.close() }
            logLine("CLOSE ${transport.kind} ${transport.target}")
            runCatching { logWriter?.flush(); logWriter?.close() }
        }
        mapOf("closed" to id, "transport" to transport.kind, "target" to transport.target)
    }

    fun info(): Map<String, Any?> = mapOf(
        "id" to id,
        "transport" to transport.kind,
        "target" to transport.target,
        "closed" to closed,
        "pending" to pending.length,
    )

    private fun drainPendingBytes(): ByteArray {
        if (pending.isEmpty()) return ByteArray(0)
        val s = pending.toString()
        pending.setLength(0)
        return s.toByteArray(Charsets.UTF_8)
    }

    private fun checkOpen() {
        if (closed) error("session $id is closed")
    }

    private fun log(dir: String, data: ByteArray, extra: String? = null) {
        val text = HardwareArgs.utf8(data).replace("\r", "\\r").replace("\n", "\\n")
        val line = buildString {
            append(dir)
            append(" hex=")
            append(HardwareArgs.formatHex(data, 256))
            append(" text=")
            append(text.take(256))
            if (extra != null) {
                append(' ')
                append(extra)
            }
        }
        logLine(line)
    }

    private fun logLine(line: String) {
        val w = logWriter ?: return
        runCatching {
            synchronized(w) {
                w.write("${clock()} $line\n")
                w.flush()
            }
        }
    }
}

class HardwareSessionStore(
    private val logDir: File? = null,
    private val ids: AtomicInteger = AtomicInteger(0),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val sessions = ConcurrentHashMap<String, HardwareSession>()

    fun open(transport: HardwareTransport, id: String? = null, log: Boolean = true): HardwareSession {
        val sid = id?.takeIf { it.isNotBlank() } ?: "s${ids.incrementAndGet()}"
        sessions[sid]?.close()
        val writer = if (log && logDir != null) {
            logDir.mkdirs()
            val f = File(logDir, "$sid.log")
            f.writer(Charsets.UTF_8)
        } else null
        writer?.let {
            it.write("${clock()} OPEN ${transport.kind} ${transport.target}\n")
            it.flush()
        }
        val session = HardwareSession(sid, transport, writer, clock)
        sessions[sid] = session
        return session
    }

    fun get(id: String): HardwareSession =
        sessions[id] ?: error("session not found: $id")

    fun close(id: String): Map<String, Any?> {
        val s = sessions.remove(id) ?: error("session not found: $id")
        return s.close()
    }

    fun list(): List<Map<String, Any?>> = sessions.values.map { it.info() }

    fun logFile(id: String): File? = logDir?.let { File(it, "$id.log") }

    fun closeAll() {
        val ids = sessions.keys.toList()
        ids.forEach { runCatching { close(it) } }
    }
}
