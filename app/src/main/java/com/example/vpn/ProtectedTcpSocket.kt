package com.example.vpn

import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A new Java Socket needs a bound file descriptor before Android can protect it.
 */
fun protectTcpSocket(socket: Socket, protect: (Socket) -> Boolean): Boolean {
    if (socket.isClosed || socket.isConnected) return false
    if (!socket.isBound) socket.bind(InetSocketAddress(0))
    return protect(socket)
}

/**
 * Executes a block of code with an Android VPN-protected [Socket], guaranteeing that
 * the socket and its underlying file descriptor are closed via [use] to prevent FD leaks.
 */
inline fun <R> useProtectedSocket(
    noinline protect: ((Socket) -> Boolean)?,
    block: (Socket) -> R
): R {
    val socket = Socket()
    return try {
        if (protect != null) {
            val protected = protectTcpSocket(socket, protect)
            check(protected) { "Failed to protect socket from VPN routing loop" }
        }
        socket.use(block)
    } finally {
        if (!socket.isClosed) {
            runCatching { socket.close() }
        }
    }
}

/**
 * Safe extension to close any [Closeable] resource silently without throwing exceptions in finally blocks.
 */
fun Closeable?.safeClose() {
    if (this == null) return
    runCatching { close() }
}

/**
 * Safe extension to disconnect any [HttpURLConnection] silently without throwing exceptions in finally blocks.
 */
fun HttpURLConnection?.safeDisconnect() {
    if (this == null) return
    runCatching { disconnect() }
}

/**
 * Safe executor for native JNI resources that requires explicit disconnection / closing in a finally block.
 */
inline fun <T : Any, R> T.useResource(
    cleanup: (T) -> Unit,
    block: (T) -> R
): R {
    try {
        return block(this)
    } finally {
        runCatching { cleanup(this) }
    }
}
