package com.example.panels.servers

import com.example.panels.PanelProvisioner
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable

/** The result of one remote script. */
data class ShellResult(val exitCode: Int, val output: String)

/** A signed-in connection to a server that runs scripts. */
interface RemoteShell : Closeable {
    /** Runs [script] with `sh -s`, streaming each output line to [onLine]. */
    fun run(script: String, timeoutMs: Long = 10 * 60_000L, onLine: (String) -> Unit = {}): ShellResult

    /** Writes [data] to [remotePath] (created by the signed-in account, mode 600). */
    fun upload(data: ByteArray, remotePath: String): Unit = throw UnsupportedOperationException("Upload is not supported")

    /** Reads up to [maxBytes] of [remotePath]. */
    fun download(remotePath: String, maxBytes: Int = MAX_TRANSFER): ByteArray = throw UnsupportedOperationException("Download is not supported")

    companion object {
        const val MAX_TRANSFER = 64 * 1024 * 1024
        private val SAFE_PATH = Regex("^/[A-Za-z0-9._/-]{1,200}$")
        fun checkPath(path: String): String = path.also { require(SAFE_PATH.matches(it) && !it.contains("..")) { "Invalid remote path" } }
    }
}

/**
 * SSH sessions for managed servers. The host key must match the one the user confirmed
 * ([ManagedServer.hostKeySha256]); a server presenting another key is refused before any password or
 * key is sent. Key login is used when the server has a phone key, else the password.
 */
object SshShell {
    fun open(server: ManagedServer, connectTimeoutMs: Int = 30_000): RemoteShell {
        require(server.hostKeySha256.isNotBlank()) { "Confirm this server's fingerprint first" }
        require(server.canSignIn) { "Add the server's password or key first" }
        val pin = PanelProvisioner.PinningHostKeyRepository(server.hostKeySha256)
        val jsch = JSch().apply { hostKeyRepository = pin }
        if (server.usesKey) jsch.addIdentity("maximus-phone", server.privateKey.toByteArray(), null, null)
        val session = jsch.getSession(server.username.trim(), server.host.trim(), server.sshPort).apply {
            if (!server.usesKey) setPassword(server.password)
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", if (server.usesKey) "publickey" else "password,keyboard-interactive")
            setServerAliveInterval(15_000)
            setServerAliveCountMax(8)
            timeout = connectTimeoutMs
        }
        try {
            session.connect(connectTimeoutMs)
            runCatching { session.timeout = 0 }
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            val msg = e.message.orEmpty()
            throw IllegalStateException(
                when {
                    msg.contains("HostKey", true) || (msg.contains("reject", true) && msg.contains("key", true)) ->
                        "This server's SSH key changed since you added it. Nothing was sent. If you rebuilt the server, remove it and add it again."
                    msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
                        if (server.usesKey) "The server refused this phone's key for '${server.username}'."
                        else "SSH login failed: wrong user name or password, or password login is off."
                    msg.contains("timeout", true) || msg.contains("timed out", true) ->
                        "${server.host}:${server.sshPort} did not answer. Check the address, the SSH port and the cloud firewall."
                    else -> "Could not connect to ${server.host}:${server.sshPort} (${msg.take(80)})"
                }
            )
        }
        check(pin.observedFingerprint.isNotBlank()) { "The server did not present an SSH host key" }
        return JschShell(session)
    }

    private class JschShell(private val session: Session) : RemoteShell {
        override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("sh -s")
            channel.setInputStream(ByteArrayInputStream(script.toByteArray(Charsets.UTF_8)))
            val stdout = channel.inputStream
            val stderr = channel.errStream
            val output = ByteArrayOutputStream()
            val pending = StringBuilder()
            fun feed(chunk: String) {
                pending.append(chunk)
                while (true) {
                    val nl = pending.indexOf("\n")
                    if (nl < 0) break
                    onLine(pending.substring(0, nl))
                    pending.delete(0, nl + 1)
                }
            }
            try {
                channel.connect(15_000)
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                val buffer = ByteArray(8192)
                while (true) {
                    for (stream in listOf(stdout, stderr)) {
                        while (stream.available() > 0) {
                            val count = stream.read(buffer, 0, minOf(buffer.size, stream.available()))
                            if (count > 0) {
                                check(output.size() + count < 4 * 1024 * 1024) { "The server sent too much output" }
                                output.write(buffer, 0, count)
                                feed(String(buffer, 0, count, Charsets.UTF_8))
                            }
                        }
                    }
                    if (channel.isClosed && stdout.available() == 0 && stderr.available() == 0) break
                    check(System.nanoTime() < deadline) { "The server did not finish in time; check it before retrying" }
                    Thread.sleep(50)
                }
                if (pending.isNotEmpty()) onLine(pending.toString())
                return ShellResult(channel.exitStatus, output.toString("UTF-8"))
            } finally {
                channel.disconnect()
            }
        }

        override fun upload(data: ByteArray, remotePath: String) {
            val path = RemoteShell.checkPath(remotePath)
            val dir = path.substringBeforeLast('/')
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("umask 077; mkdir -p '$dir' && cat > '$path.part' && mv -f '$path.part' '$path'")
            channel.setInputStream(ByteArrayInputStream(data))
            try {
                channel.connect(15_000)
                val deadline = System.nanoTime() + 10 * 60_000L * 1_000_000
                while (!channel.isClosed) {
                    check(System.nanoTime() < deadline) { "Sending the file took too long" }
                    Thread.sleep(50)
                }
                check(channel.exitStatus == 0) { "The server did not accept the file (exit ${channel.exitStatus})" }
            } finally {
                channel.disconnect()
            }
        }

        override fun download(remotePath: String, maxBytes: Int): ByteArray {
            val path = RemoteShell.checkPath(remotePath)
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("cat '$path'")
            val stdout = channel.inputStream
            val out = ByteArrayOutputStream()
            try {
                channel.connect(15_000)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stdout.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= maxBytes) { "The file is larger than expected" }
                    out.write(buffer, 0, n)
                }
                while (!channel.isClosed) Thread.sleep(20)
                check(channel.exitStatus == 0) { "Could not read $path on the server" }
                return out.toByteArray()
            } finally {
                channel.disconnect()
            }
        }

        override fun close() {
            runCatching { session.disconnect() }
        }
    }
}

/** Key pairs for key login, created on the phone. The private key never leaves the device. */
object ServerKeys {
    data class Pair(val privateKeyPem: String, val publicKeyLine: String)

    /** An ECDSA P-256 key: supported by every OpenSSH server and by Android's own crypto. */
    fun generate(comment: String = "maximus-vpn-phone"): Pair {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        try {
            val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toString("US-ASCII")
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString("US-ASCII").trim()
            return Pair(priv, pub)
        } finally {
            kp.dispose()
        }
    }
}
