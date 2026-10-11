package com.example.vpn.sidecar

import com.example.data.model.VlessProfile
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

/** The sidecar engines the app knows, and where their programs are on this phone. */
object Sidecars {
    /** Registered engines, strongest first. Each engine file adds itself here. */
    val ENGINES: List<SidecarEngine> by lazy {
        listOf(
            MihomoSidecar,
            PsiphonSidecar(PsiphonSidecar.assetProvider { name ->
                openAsset?.invoke(name) ?: throw java.io.FileNotFoundException(name)
            }),
            DnsttSidecar(),
            MasqueSidecar,
            TorSidecar { torrc, port -> (torStarter ?: error("Tor needs Android to run")).invoke(torrc, port) }
        )
    }

    /** Starts the Tor library; set once by the VPN service. */
    @Volatile var torStarter: ((String, Int) -> RunningEngine)? = null

    /** Directory Android extracted the APK's native libraries to; set once by the app. */
    @Volatile var nativeLibraryDir: String? = null

    /** Opens a file from the APK's assets; set once by the app. */
    @Volatile var openAsset: ((String) -> java.io.InputStream)? = null

    fun psiphon(): PsiphonSidecar = ENGINES.filterIsInstance<PsiphonSidecar>().single()

    fun executable(engine: SidecarEngine): File? =
        nativeLibraryDir?.let { File(it, "lib${engine.binary}.so") }?.takeIf { it.isFile && it.canExecute() }

    /** True when this build carries the engine's program for this phone's CPU. */
    fun isBundled(engine: SidecarEngine): Boolean = executable(engine) != null

    fun forProfile(profile: VlessProfile): SidecarEngine? = ENGINES.firstOrNull { it.handles(profile) }

    /** True when [profile] runs on a bundled engine: a lone sidecar, or a chain of them. */
    fun isEngineProfile(profile: VlessProfile): Boolean =
        forProfile(profile) != null || ChainRunner.isChain(profile)

    fun freeLoopbackPort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun randomToken(bytes: Int = 18): String {
        val raw = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}
