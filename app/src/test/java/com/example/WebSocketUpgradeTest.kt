package com.example

import com.example.vpn.experimental.WebSocketUpgrade
import org.junit.Assert.*
import org.junit.Test

class WebSocketUpgradeTest {
    private val key = "dGhlIHNhbXBsZSBub25jZQ=="
    private val valid = "Upgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
    @Test fun checksRfc6455Challenge() {
        assertTrue(WebSocketUpgrade.valid(valid.byteInputStream(), key))
        assertFalse(WebSocketUpgrade.valid(valid.byteInputStream(), "wrong-key"))
        assertFalse(WebSocketUpgrade.valid("\r\n".byteInputStream(), key))
        assertFalse(WebSocketUpgrade.valid(valid.replace("websocket", "h2c").byteInputStream(), key))
    }
    @Test fun rejectsAmbiguousOversizedAndUnsolicitedHeaders() {
        for (prefix in listOf("Sec-WebSocket-Accept: bogus\r\n", "Sec-WebSocket-Extensions: permessage-deflate\r\n", "Sec-WebSocket-Protocol: chat\r\n", "X: " + "a".repeat(17000) + "\r\n")) {
            assertFalse(WebSocketUpgrade.valid((prefix+valid).byteInputStream(), key))
        }
        assertFalse(WebSocketUpgrade.valid(valid.replace("\r\n", "\n").byteInputStream(), key))
    }
}
