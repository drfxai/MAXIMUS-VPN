package com.example

import com.example.vpn.lab.ResolverIntelligence
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolverBurstPortTest {
    @Test fun truncatedUdpFallsBackToTcpOnTheSameCustomResolverPort() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { tcp ->
            DatagramSocket(InetSocketAddress(loopback, tcp.localPort)).use { udp ->
                tcp.soTimeout = 5000
                udp.soTimeout = 5000
                val failure = AtomicReference<Throwable?>()
                val udpQuery = AtomicReference<ByteArray?>()
                val tcpQuery = AtomicReference<ByteArray?>()
                val udpWorker = thread(isDaemon = true) {
                    try {
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        udp.receive(packet)
                        val query = packet.data.copyOf(packet.length)
                        udpQuery.set(query.copyOf())
                        // A valid truncated response requires a TCP retry for the same question.
                        query[2] = 0x83.toByte()
                        query[3] = 0x80.toByte()
                        udp.send(DatagramPacket(query, query.size, packet.socketAddress))
                    } catch (e: Exception) { failure.compareAndSet(null, e) }
                }
                val tcpWorker = thread(isDaemon = true) {
                    try {
                        tcp.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = DataInputStream(socket.getInputStream())
                            val query = ByteArray(input.readUnsignedShort()).also(input::readFully)
                            tcpQuery.set(query.copyOf())
                            query[2] = 0x81.toByte()
                            query[3] = 0x80.toByte()
                            query[7] = 1 // One A answer, with the original transaction and question.
                            val bytes = ByteArrayOutputStream()
                            DataOutputStream(bytes).use { answer ->
                                answer.write(query)
                                answer.writeShort(0xc00c)
                                answer.writeShort(1)
                                answer.writeShort(1)
                                answer.writeInt(60)
                                answer.writeShort(4)
                                answer.write(byteArrayOf(8, 8, 8, 8))
                            }
                            val output = DataOutputStream(socket.getOutputStream())
                            output.writeShort(bytes.size())
                            output.write(bytes.toByteArray())
                            output.flush()
                        }
                    } catch (e: Exception) { failure.compareAndSet(null, e) }
                }
                try {
                    val now = System.currentTimeMillis()
                    val resolver = ResolverIntelligence.Result("Fixture", "127.0.0.1", "fixture", "fixture",
                        udp = true, tcp = true, rttMs = null, blockPage = false, nxdomainHijack = false,
                        ednsSize = null, measuredAt = now, expiresAt = now + 60_000, port = tcp.localPort)
                    val result = ResolverIntelligence.burst(null, resolver, count = 1, gapMs = 0)
                    assertEquals(1, result.answered)
                    assertEquals("UDP/TCP", result.transport)
                    udpWorker.join(1000)
                    tcpWorker.join(1000)
                    assertNull(failure.get())
                    org.junit.Assert.assertArrayEquals(udpQuery.get(), tcpQuery.get())
                } finally {
                    udp.close()
                    tcp.close()
                    udpWorker.join(1000)
                    tcpWorker.join(1000)
                }
            }
        }
    }
}
