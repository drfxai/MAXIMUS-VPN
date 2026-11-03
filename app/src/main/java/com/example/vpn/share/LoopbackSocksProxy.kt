package com.example.vpn.share

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Bounded TCP-only SOCKS5 entrance. Names go to the engine, never to the system DNS resolver. */
class LoopbackSocksProxy(
    private val user: String,
    private val password: String,
    private val dial: (String, Int) -> Socket
) : Closeable {
    init { require(user.toByteArray().size in 1..255 && password.toByteArray().size in 1..255) }
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(16)
    private val workers = Executors.newFixedThreadPool(32) { task -> Thread(task, "local-proxy").apply { isDaemon = true } }
    @Volatile private var closed = false
    val port: Int get() = listener.localPort

    init {
        Thread({
            while (!closed) {
                val client = try { listener.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                clients.add(client)
                try { workers.execute { serve(client) } }
                catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close(); slots.release() }
            }
        }, "local-proxy-accept").apply { isDaemon = true; start() }
    }

    private fun serve(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 10_000
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            if (input.readUnsignedByte() != 5) return
            val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            if (2.toByte() !in methods) { output.write(byteArrayOf(5, -1)); return }
            output.write(byteArrayOf(5, 2))
            if (input.readUnsignedByte() != 1) return
            val u = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val p = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            val valid = MessageDigest.isEqual(u, user.toByteArray(Charsets.UTF_8)) and
                MessageDigest.isEqual(p, password.toByteArray(Charsets.UTF_8))
            output.write(byteArrayOf(1, if (valid) 0 else 1))
            if (!valid) return
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 1 || input.readUnsignedByte() != 0) {
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return
            }
            val host = when (input.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress!!
                4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress!!
                3 -> String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) }, Charsets.US_ASCII)
                else -> return
            }
            val port = input.readUnsignedShort()
            if (host.isBlank() || host.any { it.isISOControl() } || port == 0) return
            remote = dial(host, port)
            clients.add(remote)
            remote.soTimeout = 180_000
            client.soTimeout = 180_000
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            val upstream = remote
            // Each admitted client uses two workers; the 16 slots bound threads and sockets.
            workers.execute {
                try { client.getInputStream().copyTo(upstream.getOutputStream(), 16 * 1024); upstream.shutdownOutput() }
                catch (_: Exception) { runCatching { upstream.close() } }
            }
            remote.getInputStream().copyTo(output, 16 * 1024)
        } catch (_: Exception) {
            // No credential, target or upstream error is logged.
        } finally {
            remote?.let { clients.remove(it); runCatching { it.close() } }
            clients.remove(client)
            runCatching { client.close() }
            slots.release()
        }
    }

    override fun close() {
        closed = true
        runCatching { listener.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
