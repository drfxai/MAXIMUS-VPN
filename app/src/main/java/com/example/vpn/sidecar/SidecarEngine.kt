package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import java.io.File

/**
 * An engine that runs as its own program next to the app (Mihomo, Psiphon, Tor's transports, a DNS
 * tunnel). It is shipped in the APK as `lib<binary>.so` so Android installs it executable, and it
 * offers a SOCKS5 port on 127.0.0.1. Xray still owns the TUN, the DNS handling and the kill switch;
 * its only proxy becomes that port, so everything the app enforces still applies.
 *
 * Running as a separate program is also what keeps GPL engines (Mihomo, Psiphon) from making the
 * app itself GPL: the two only talk over a socket.
 */
interface SidecarEngine {
    /** Matches the EngineRegistry id. */
    val id: String
    /** Executable name without the lib prefix and .so suffix. */
    val binary: String

    fun handles(profile: VlessProfile): Boolean

    /** Why this profile cannot run even though [handles] is true (missing settings), or null. */
    fun problem(profile: VlessProfile): String? = null

    /** Writes whatever the engine needs into [SidecarContext.workDir] and says how to start it. */
    fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch
}

data class SidecarContext(
    val workDir: File,
    val executable: File,
    /** Loopback port the engine must offer SOCKS5 on. */
    val socksPort: Int,
    /** Random per-connection login for that port, so other apps on the phone cannot use it. */
    val socksUser: String,
    val socksPass: String,
    val mode: OperationalMode
)

data class SidecarLaunch(
    val command: List<String>,
    val environment: Map<String, String> = emptyMap(),
    /** Some engines (Psiphon, Tor) need their own network setup before the port answers. */
    val readyTimeoutMs: Long = 15_000,
    /** False when the engine cannot check a SOCKS login (Psiphon, Tor); Xray then sends none. */
    val socksAuth: Boolean = true
)
