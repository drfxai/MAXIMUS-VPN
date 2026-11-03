package com.example

import com.example.vpn.share.LoopbackSocksProxy
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.ServerSocket
import java.net.InetAddress
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicReference

class LoopbackProxyTest {
    @Test fun authenticationIsRequiredBeforeAnyUpstreamDial() {
        var calls = 0
        LoopbackSocksProxy("user", "secret") { _, _ -> calls++; error("must not dial") }.use { proxy ->
            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, socket.getInputStream().read())
                assertEquals(255, socket.getInputStream().read())
            }
            Socket("127.0.0.1", proxy.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(byteArrayOf(5, 1, 2))
                val input = DataInputStream(socket.getInputStream())
                assertEquals(0x0502, input.readUnsignedShort())
                socket.getOutputStream().write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(5) + "wrong".toByteArray())
                assertEquals(0x0101, input.readUnsignedShort())
            }
            assertEquals(0, calls)
        }
    }

    @Test fun domainIsForwardedUnresolvedAndPayloadTravelsBothWays() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { echo ->
            val target = AtomicReference<Pair<String, Int>>()
            val t = Thread {
                echo.accept().use { client ->
                    val data = ByteArray(65536)
                    DataInputStream(client.getInputStream()).readFully(data)
                    client.getOutputStream().write(data.reversedArray())
                }
            }.apply { isDaemon = true; start() }
            LoopbackSocksProxy("u", "p") { host, port ->
                target.set(host to port)
                Socket("127.0.0.1", echo.localPort)
            }.use { proxy ->
                Socket("127.0.0.1", proxy.port).use { socket ->
                    socket.soTimeout = 5000
                    val out = socket.getOutputStream()
                    val input = DataInputStream(socket.getInputStream())
                    out.write(byteArrayOf(5, 1, 2)); assertEquals(0x0502, input.readUnsignedShort())
                    out.write(byteArrayOf(1, 1, 'u'.code.toByte(), 1, 'p'.code.toByte()))
                    assertEquals(0x0100, input.readUnsignedShort())
                    val domain = "name-that-must-not-resolve.invalid".toByteArray()
                    out.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()) + domain + byteArrayOf(1, -69))
                    assertEquals(0x0500, input.readUnsignedShort())
                    input.readFully(ByteArray(8))
                    val data = ByteArray(65536) { (it % 251).toByte() }
                    out.write(data)
                    val reply = ByteArray(data.size); input.readFully(reply)
                    assertArrayEquals(data.reversedArray(), reply)
                    assertEquals("name-that-must-not-resolve.invalid" to 443, target.get())
                }
            }
            t.join(1000)
        }
    }
}
