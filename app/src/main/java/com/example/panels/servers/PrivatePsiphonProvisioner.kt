package com.example.panels.servers

import com.example.vpn.sidecar.PrivatePsiphonEntry
import java.security.MessageDigest
import java.util.UUID

/**
 * User-confirmed private Psiphon server installation. The caller downloads the fixed
 * PsiphonArtifact manifest, validates SHA-256, records operation intent, then invokes this
 * backend. No unattended SSH installs or background mutations are performed.
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
        // Deliberately stricter than generic ServerFacts.blocker(): this dormant
        // provisioner does not yet safely translate every command to passwordless sudo.
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
            if (inspected.exitCode != 0 ||
                inspected.output.lineSequence().none { it.trim() == ServerFacts.BEGIN }) {
                throw InstallPreflightException("Fresh authenticated SSH inventory is required.")
            }
            val facts = ServerFacts.parse(inspected.output)
            facts.blocker()?.let { throw InstallPreflightException(it) }
            if (!PrivatePsiphonArtifact.supported(facts))
                throw InstallPreflightException("Private Psiphon install currently supports root SSH on amd64 Linux/systemd with working socket inventory only.")
            if (facts.psiphonInstalled || facts.psiphonDataRetained)
                throw InstallPreflightException("Existing Psiphon service or retained keys must be inspected/purged explicitly before reinstall.")
            if (port in facts.tcpPorts || port in facts.udpPorts)
                throw InstallPreflightException("Selected port is occupied; no files were uploaded.")
            // Upload through pinned SSH; never print the executable or credentials in progress logs.
            // The upload itself can fail halfway: the same finally cleanup covers that case.
            var installed = false
            try {
                shell.upload(executable, staged)
                val confirmed = shell.runTool(PrivatePsiphonServerScripts.install(spec), progress, 5 * 60_000L)
                installed = confirmed["PSIPHON_UNIT_ACTIVE"] == "1"
                check(installed) { "The managed systemd service did not confirm readiness." }
                // Raw bootstrap stays inside this method until encrypted on Android by PrivatePsiphonStore.
                val entry = shell.download("${PrivatePsiphonServerScripts.INSTALL_DIR}/server-entry.dat", 262_144)
                    .toString(Charsets.UTF_8).trim()
                val description = PrivatePsiphonEntry.inspect(entry)
                check(description != null && description.ipAddress == publicIp &&
                    description.sshObfuscatedPort == port) {
                    "Server entry is invalid or does not describe the reviewed VPS and port."
                }
                val newTool = InstalledTool(PrivatePsiphonServerScripts.TOOL_ID, port = port)
                return Result(server.withTool(newTool), entry, port)
            } catch (e: Exception) {
                // install() rolls back failures before its success marker. Once running,
                // a broken or mismatched bootstrap must not leave an orphan service.
                if (installed) {
                    val rolledBack = runCatching {
                        shell.runTool(PrivatePsiphonServerScripts.uninstall(purge = true), progress, 60_000L)
                    }.isSuccess
                    if (!rolledBack) throw IllegalStateException(
                        "Psiphon bootstrap verification failed and remote rollback is unconfirmed; inspect the VPS before retrying.", e)
                }
                throw e
            } finally {
                // Deleting the staging file is safe even if the script already moved it to /opt.
                // It is limited to the randomized path created by this app.
                runCatching {
                    shell.run("rm -f '$staged' '$staged.part'", 20_000)
                }
            }
        }
    }
}
