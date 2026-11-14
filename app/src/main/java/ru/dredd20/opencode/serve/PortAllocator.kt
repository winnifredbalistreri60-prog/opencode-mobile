package ru.dredd20.opencode.serve

import java.net.InetAddress
import java.net.ServerSocket

/**
 * Pick a free TCP port on a loopback address. Never hard-codes 4096.
 * [preferred] is tried first; if it is already taken (including by a process
 * that is not our server), another ephemeral port is bound.
 */
object PortAllocator {
    fun pickFreePort(host: String = "127.0.0.1", preferred: Int? = null): Int {
        if (preferred != null && preferred in 1..65535 && preferred != 0 && isFree(host, preferred)) {
            return preferred
        }
        ServerSocket(0, 1, InetAddress.getByName(host)).use { socket ->
            return socket.localPort
        }
    }

    fun isFree(host: String, port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName(host)).use { true }
    } catch (_: Exception) {
        false
    }
}
