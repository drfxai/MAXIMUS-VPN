package com.example

import com.example.data.model.DesyncConfig
import com.example.data.model.DesyncMethod
import com.example.vpn.desync.DesyncEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket

class DesyncStreamSafetyTest {
    @Test fun legacyWriterCannotPrependFakeBytesOrReverseApplicationStream() {
        for (method in listOf(DesyncMethod.FAKE_SNI, DesyncMethod.DISORDER, DesyncMethod.DISORDER_OOB, DesyncMethod.OOB)) {
            val output = ByteArrayOutputStream()
            Socket().use { socket ->
                assertThrows(IllegalArgumentException::class.java) {
                    DesyncEngine.writeDesyncData(socket, output, ByteArray(32) { it.toByte() }, DesyncConfig(method = method), "decoy.example")
                }
            }
            assertEquals(0, output.size())
        }
    }
}
