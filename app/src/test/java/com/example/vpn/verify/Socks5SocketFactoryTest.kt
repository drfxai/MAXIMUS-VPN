package com.example.vpn.verify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Build 174: the upkeep's GeoFiles update failed while connected through an engine program (the app is left out of its
 * own VPN, so binding to the VPN network is refused). The download now goes through the engine's local SOCKS5 port,
 * asking for the host by name so nothing is looked up on the phone's network.
 */
class Socks5SocketFactoryTest {
    @Test fun theSocketAsksTheEngineForTheHostByNameWithItsCredential() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val seen = CompletableFuture<Triple<String, Int, String>>()
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    input.readUnsignedByte(); input.readFully(ByteArray(input.readUnsignedByte()))
                    out.write(byteArrayOf(5, 2)); out.flush()
                    input.readUnsignedByte()
                    val user = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    out.write(byteArrayOf(1, 0)); out.flush()
                    input.readFully(ByteArray(3))
                    check(input.readUnsignedByte() == 3) { "expected a domain name" }
                    val host = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                    val port = input.readUnsignedShort()
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                    seen.complete(Triple(host, port, user))
                    out.write("ok".toByteArray()); out.flush()
                    Thread.sleep(200)
                }
            }.onFailure { seen.completeExceptionally(it) }
        }
        val factory = Socks5SocketFactory(Socks5.Endpoint("127.0.0.1", server.localPort, "u1", "p1"), 5_000)
        val placeholder = Socks5SocketFactory.remoteDns("api.github.com").single()
        assertArrayEquals(ByteArray(4), placeholder.address)
        factory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(placeholder, 443), 5_000)
            assertEquals(Triple("api.github.com", 443, "u1"), seen.get(5, TimeUnit.SECONDS))
            val buf = ByteArray(2)
            DataInputStream(socket.getInputStream()).readFully(buf)
            assertEquals("ok", String(buf))
        }
        server.close()
    }

    @Test fun theTargetNameIsTheHostNameWhenThereIsOne() {
        assertEquals("example.com", Socks5SocketFactory.targetName(InetSocketAddress.createUnresolved("example.com", 443)))
        assertEquals("1.2.3.4", Socks5SocketFactory.targetName(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)), 443)))
    }
}
