package ru.dredd20.opencode.phone

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.atomic.AtomicBoolean

class ClientConn(val input: InputStream, val output: OutputStream) : Closeable {
    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/**
 * Phone API listen socket.
 *
 * Preferred: filesystem UNIX socket at [path] (bind-mounted into proot as /run/phone.sock).
 * Fallback: abstract-namespace LocalServerSocket [ABSTRACT_NAME].
 *
 * [close] unlinks the filesystem path and tries to wake a blocked [accept] so the
 * previous instance can exit before a new bind.
 */
class UnixSocketServer(private val path: File) : Closeable {
    private val running = AtomicBoolean(false)
    private var fs: ServerSocketChannel? = null
    private var abstract: LocalServerSocket? = null
    val usesFilesystem: Boolean get() = fs != null

    fun start() {
        unlinkFilesystem()
        running.set(true)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                fs = openFilesystem(path)
                if (fs != null) return
            } catch (_: Exception) {
                fs = null
                unlinkFilesystem()
            }
        }
        abstract = LocalServerSocket(ABSTRACT_NAME)
    }

    fun accept(): ClientConn {
        if (!running.get()) error("closed")
        fs?.let { ch ->
            val c = ch.accept() ?: error("accept closed")
            return ClientConn(Channels.newInputStream(c), Channels.newOutputStream(c))
        }
        val s: LocalSocket = abstract?.accept() ?: error("not started")
        return ClientConn(s.inputStream, s.outputStream)
    }

    fun isRunning(): Boolean = running.get()

    /** Close the listen socket, unlink the file, and poke accept() so it returns. */
    override fun close() {
        running.set(false)
        runCatching { fs?.close() }
        runCatching { abstract?.close() }
        wakeupBlockedAccept()
        fs = null
        abstract = null
        unlinkFilesystem()
    }

    private fun unlinkFilesystem() {
        if (path.exists()) path.delete()
    }

    private fun wakeupBlockedAccept() {
        // LocalServerSocket.close() does not always unblock accept() on Android.
        // A short connecting client forces the accept loop to return.
        runCatching {
            LocalSocket().use { c ->
                c.connect(
                    LocalSocketAddress(ABSTRACT_NAME, LocalSocketAddress.Namespace.ABSTRACT),
                    200,
                )
            }
        }
        runCatching {
            if (path.exists() || path.parentFile?.exists() == true) {
                val sock = java.net.Socket()
                // Filesystem UNIX sockets are not TCP; a dummy LocalSocket to the path is enough.
                LocalSocket().use { c ->
                    c.connect(LocalSocketAddress(path.absolutePath), 200)
                }
            }
        }
    }

    companion object {
        const val ABSTRACT_NAME = "ru.dredd20.opencode.phone"

        internal fun openFilesystem(path: File): ServerSocketChannel? {
            val unix = Class.forName("java.net.StandardProtocolFamily").enumConstants
                ?.firstOrNull { it is Enum<*> && it.name == "UNIX" } ?: return null
            val open = ServerSocketChannel::class.java.methods.firstOrNull { m ->
                m.name == "open" && m.parameterTypes.size == 1 && m.parameterTypes[0].isInstance(unix)
            } ?: return null
            val ch = open.invoke(null, unix) as? ServerSocketChannel ?: return null
            val addrClass = Class.forName("java.net.UnixDomainSocketAddress")
            val of = addrClass.getMethod("of", String::class.java)
            val addr = of.invoke(null, path.absolutePath)
            ServerSocketChannel::class.java.getMethod("bind", java.net.SocketAddress::class.java).invoke(ch, addr)
            return ch
        }
    }
}
