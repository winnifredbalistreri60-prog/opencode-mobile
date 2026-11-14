package ru.dredd20.opencode.phone

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
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
 * The public android.jar stub may not expose [ServerSocketChannel.open] with a protocol
 * family, so we call it by reflection on API 33+.
 *
 * Fallback: abstract-namespace LocalServerSocket [ABSTRACT_NAME]. Same-UID processes
 * inside proot can connect (kernel namespace; no bind-mount required).
 */
class UnixSocketServer(private val path: File) : Closeable {
    private val running = AtomicBoolean(false)
    private var fs: ServerSocketChannel? = null
    private var abstract: LocalServerSocket? = null
    val usesFilesystem: Boolean get() = fs != null

    fun start() {
        path.parentFile?.mkdirs()
        if (path.exists()) path.delete()
        running.set(true)
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                fs = openFilesystem(path)
                if (fs != null) return
            } catch (_: Exception) {
                fs = null
                if (path.exists()) path.delete()
            }
        }
        abstract = LocalServerSocket(ABSTRACT_NAME)
    }

    fun accept(): ClientConn {
        fs?.let { ch ->
            val c = ch.accept()
            return ClientConn(Channels.newInputStream(c), Channels.newOutputStream(c))
        }
        val s: LocalSocket = abstract?.accept() ?: error("not started")
        return ClientConn(s.inputStream, s.outputStream)
    }

    fun isRunning(): Boolean = running.get()

    override fun close() {
        running.set(false)
        runCatching { fs?.close() }
        runCatching { abstract?.close() }
        fs = null
        abstract = null
        if (path.exists()) path.delete()
    }

    companion object {
        const val ABSTRACT_NAME = "ru.dredd20.opencode.phone"

        internal fun openFilesystem(path: File): ServerSocketChannel? {
            val open = ServerSocketChannel::class.java.methods.firstOrNull { m ->
                m.name == "open" && m.parameterTypes.size == 1
            } ?: return null
            val ch = open.invoke(null, StandardProtocolFamily.UNIX) as? ServerSocketChannel ?: return null
            val addrClass = Class.forName("java.net.UnixDomainSocketAddress")
            val of = addrClass.getMethod("of", String::class.java)
            val addr = of.invoke(null, path.absolutePath)
            ServerSocketChannel::class.java.getMethod("bind", java.net.SocketAddress::class.java).invoke(ch, addr)
            return ch
        }
    }
}
