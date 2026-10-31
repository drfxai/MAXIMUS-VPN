package com.example.panels.servers

import java.security.MessageDigest
import java.util.UUID

/**
 * Low-level, explicitly invoked Psiphon provisioning, intended for a later user-approved wizard.
 * Does not select/download a release: trust establishment (a reviewed pinned artifact manifest)
 * must be completed by the caller. No background tasks or automatic installs are initiated.
 */
class PrivatePsiphonProvisioner(
    private val connect: (ManagedServer) -> RemoteShell = { SshShell.open(it) }
) {
    data class Result(val server: ManagedServer, val serverEntry: String, val port: Int)

    fun installWithPinnedBinary(
        server: ManagedServer,
        publicIp: String,
        port: Int,
        executable: ByteArray,
        reviewedSha256: String,
        progress: ActionProgress
    ): Result {
        require(server.username == "root") { "Private Psiphon installation currently requires root SSH." }
        require(server.canSignIn) { "The VPS SSH identity and authentication must be confirmed." }
        require(executable.size in 1_000_000..RemoteShell.MAX_TRANSFER) { "Unexpected psiphond binary size." }
        require(reviewedSha256.matches(Regex("[0-9a-f]{64}"))) { "A reviewed, pinned SHA-256 is mandatory." }
        val actual = MessageDigest.getInstance("SHA-256").digest(executable).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
        require(actual == reviewedSha256) { "The downloaded executable does not match the reviewed SHA-256." }
        val staged = "/tmp/maximus-psiphond-${UUID.randomUUID().toString().replace("-", "")}.bin"
        val spec = PrivatePsiphonServerScripts.Request(publicIp, port, staged, reviewedSha256)
        connect(server).use { shell ->
            val inspected = shell.run(ServerFacts.SCRIPT, 60_000)
            check(inspected.exitCode == 0 && inspected.output.contains(ServerFacts.BEGIN)) {
                "SSH inventory could not be authenticated."
            }
            val facts = ServerFacts.parse(inspected.output)
            facts.blocker()?.let { error(it) }
            check(facts.root && facts.socketInspectionAvailable && !facts.psiphonInstalled) {
                "A fresh root-level inventory is required; an existing Psiphon service cannot be overwritten."
            }
            check(port !in facts.tcpPorts && port !in facts.udpPorts) {
                "The requested port is already occupied; no data was uploaded."
            }
            // Upload through pinned SSH; never print the executable or credentials in progress logs.
            shell.upload(executable, staged)
            try {
                val confirmed = shell.runTool(PrivatePsiphonServerScripts.install(spec), progress, 5 * 60_000L)
                check(confirmed["PSIPHON_UNIT_ACTIVE"] == "1") { "The managed systemd service did not confirm readiness." }
                // Raw bootstrap stays inside this method until encrypted on Android by PrivatePsiphonStore.
                val entry = shell.download("${PrivatePsiphonServerScripts.INSTALL_DIR}/server-entry.dat", 262_144)
                    .toString(Charsets.UTF_8).trim()
                check(entry.length in 64..262_144 && entry.length % 2 == 0 &&
                    entry.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
                    "Server entry is invalid; do not create a client profile."
                }
                val newTool = InstalledTool(PrivatePsiphonServerScripts.TOOL_ID, port = port)
                return Result(server.withTool(newTool), entry, port)
            } finally {
                // Deleting the staging file is safe even if the script already moved it to /opt.
                // It is limited to the randomized path created by this app.
                runCatching {
                    shell.run("rm -f '$staged'", 20_000)
                }
            }
        }
    }
}
