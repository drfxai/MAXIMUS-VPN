package com.example.vpn.desync

import com.example.data.model.DesyncConfig
import com.example.data.model.DesyncMethod
import com.example.data.model.DesyncProfile
import com.example.xray.XrayLogManager
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Legacy configuration helpers. Real fake-SNI/retransmission is implemented by ByedpiSidecar.
 * A Java OutputStream cannot control TCP sequence numbers or replace retransmitted payloads.
 */
object DesyncEngine {

    private val random = SecureRandom()
    private val adaptiveEscalations = ConcurrentHashMap<String, DesyncMethod>()

    // Default high-reputation CDN & Cloudflare Workers domains for Fake SNI decoys
    val DEFAULT_FAKE_SNI_LIST = listOf(
        "www.cloudflare.com",
        "speed.cloudflare.com",
        "cdn.jsdelivr.net",
        "www.google.com",
        "www.bing.com",
        "www.microsoft.com",
        "cdnjs.cloudflare.com",
        "workers.dev"
    )

    /**
     * Selects a single random fake SNI domain from the configured pool for the lifetime of a connection.
     */
    fun selectRandomFakeSni(fakeSniPool: String?, customSni: String?): String {
        if (!customSni.isNullOrBlank()) {
            return customSni.trim()
        }
        val pool = fakeSniPool?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        if (!pool.isNullOrEmpty()) {
            return pool[Random.nextInt(pool.size)]
        }
        return DEFAULT_FAKE_SNI_LIST[Random.nextInt(DEFAULT_FAKE_SNI_LIST.size)]
    }

    /**
     * Resolves effective desync configuration based on server profile and global app settings.
     */
    fun resolveConfig(
        profileConfig: DesyncConfig?,
        globalConfig: DesyncConfig,
        hostKey: String
    ): DesyncConfig {
        val baseConfig = profileConfig ?: globalConfig
        if (!baseConfig.enabled || baseConfig.profile == DesyncProfile.OFF) {
            return baseConfig.copy(method = DesyncMethod.NONE)
        }

        val effectiveMethod = when (baseConfig.profile) {
            DesyncProfile.OFF -> DesyncMethod.NONE
            DesyncProfile.LIGHT -> DesyncMethod.SPLIT
            DesyncProfile.BALANCED -> DesyncMethod.FAKE_SNI
            DesyncProfile.SEVERE -> DesyncMethod.DISORDER_OOB
            DesyncProfile.ADAPTIVE -> adaptiveEscalations[hostKey] ?: DesyncMethod.SPLIT
            DesyncProfile.CUSTOM -> baseConfig.method
        }

        return baseConfig.copy(method = effectiveMethod)
    }

    /**
     * Records a connection failure/RST on a host to adaptively escalate DPI bypass strength.
     */
    fun recordHostFailure(hostKey: String) {
        val current = adaptiveEscalations[hostKey] ?: DesyncMethod.SPLIT
        val next = when (current) {
            DesyncMethod.NONE -> DesyncMethod.SPLIT
            DesyncMethod.SPLIT -> DesyncMethod.FAKE_SNI
            DesyncMethod.FAKE_SNI -> DesyncMethod.DISORDER
            DesyncMethod.DISORDER -> DesyncMethod.OOB
            DesyncMethod.OOB -> DesyncMethod.DISORDER_OOB
            DesyncMethod.DISORDER_OOB -> DesyncMethod.DISORDER_OOB
        }
        adaptiveEscalations[hostKey] = next
        XrayLogManager.w("DESYNC", "Adaptive Desync escalated method for $hostKey to ${next.displayName}")
    }

    /**
     * Executes desynchronized write of initial handshake payload (e.g. TLS ClientHello or HTTP header).
     */
    fun writeDesyncData(
        socket: Socket,
        outStream: OutputStream,
        data: ByteArray,
        config: DesyncConfig,
        fakeSni: String
    ) {
        if (!config.enabled || config.method == DesyncMethod.NONE || data.size <= 5) {
            outStream.write(data)
            outStream.flush()
            return
        }

        require(config.method == DesyncMethod.SPLIT) {
            "This method requires the native ByeDPI carrier; refusing to corrupt the TLS stream"
        }
        val splitPos = config.splitPosition.coerceIn(1, data.size - 1)
        outStream.write(data, 0, splitPos)
        outStream.flush()
        outStream.write(data, splitPos, data.size - splitPos)
        outStream.flush()
    }

    /**
     * Constructs a minimal valid TLS 1.3/1.2 ClientHello byte sequence with a decoy Server Name Indication.
     */
    fun buildFakeClientHello(fakeSni: String): ByteArray {
        val sniBytes = fakeSni.toByteArray(Charsets.US_ASCII)
        val serverNameLength = sniBytes.size
        val serverNameListLength = serverNameLength + 3
        val sniExtensionLength = serverNameListLength + 2

        // SNI Extension: Type 0x0000, Length, NameType 0 (host_name), NameLength, NameBytes
        val sniExtension = java.io.ByteArrayOutputStream()
        sniExtension.write(0x00) // Extension Type: server_name (0)
        sniExtension.write(0x00)
        sniExtension.write((sniExtensionLength shr 8) and 0xFF)
        sniExtension.write(sniExtensionLength and 0xFF)
        sniExtension.write((serverNameListLength shr 8) and 0xFF)
        sniExtension.write(serverNameListLength and 0xFF)
        sniExtension.write(0x00) // NameType: host_name
        sniExtension.write((serverNameLength shr 8) and 0xFF)
        sniExtension.write(serverNameLength and 0xFF)
        sniExtension.write(sniBytes)

        val extensionsBytes = sniExtension.toByteArray()
        val extensionsLength = extensionsBytes.size

        // TLS ClientHello Body
        val clientHello = java.io.ByteArrayOutputStream()
        clientHello.write(0x03) // Version 3.3 (TLS 1.2 / 1.3 container)
        clientHello.write(0x03)

        // 32-byte Random
        val randomBytes = ByteArray(32)
        random.nextBytes(randomBytes)
        clientHello.write(randomBytes)

        // Session ID length: 0
        clientHello.write(0x00)

        // Cipher Suites: 2 suites (TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384)
        clientHello.write(0x00)
        clientHello.write(0x04)
        clientHello.write(0x13)
        clientHello.write(0x01)
        clientHello.write(0x13)
        clientHello.write(0x02)

        // Compression Methods: 1 (null)
        clientHello.write(0x01)
        clientHello.write(0x00)

        // Extensions Length & Extensions
        clientHello.write((extensionsLength shr 8) and 0xFF)
        clientHello.write(extensionsLength and 0xFF)
        clientHello.write(extensionsBytes)

        val handshakeBody = clientHello.toByteArray()
        val handshakeLength = handshakeBody.size

        // Handshake Header: Type 1 (ClientHello), Length (3 bytes)
        val handshake = java.io.ByteArrayOutputStream()
        handshake.write(0x01) // ClientHello
        handshake.write((handshakeLength shr 16) and 0xFF)
        handshake.write((handshakeLength shr 8) and 0xFF)
        handshake.write(handshakeLength and 0xFF)
        handshake.write(handshakeBody)

        val recordBody = handshake.toByteArray()
        val recordLength = recordBody.size

        // TLS Record Header: Type 22 (Handshake), Version 3.1 (TLS 1.0 container), Length
        val record = java.io.ByteArrayOutputStream()
        record.write(0x16) // ContentType: Handshake
        record.write(0x03) // Version 3.1
        record.write(0x01)
        record.write((recordLength shr 8) and 0xFF)
        record.write(recordLength and 0xFF)
        record.write(recordBody)

        return record.toByteArray()
    }
}
