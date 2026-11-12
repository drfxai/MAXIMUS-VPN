package com.example.update

import android.content.Context
import com.example.vpn.BundledResolvers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Only six validated text resolver lists may change, never executable files or untrusted DNS defaults.
 * Official APK assets remain the fallback. The previous verified pack is retained for rollback.
 */
object ResolverPackUpdater {
    private const val MAX_ZIP_BYTES = 2L * 1024 * 1024
    private const val MAX_PART_BYTES = 128 * 1024
    private val names = (1..6).map { "part_$it.txt" }.toSet()
    private val client by lazy { OkHttpClient.Builder().followRedirects(true).build() }

    private fun base(context: Context) = File(context.noBackupFilesDir, "resolver-pack")
    private fun current(context: Context) = File(base(context), "current")
    private fun previous(context: Context) = File(base(context), "previous")

    fun installed(context: Context): Boolean = names.all { File(current(context), it).isFile }

    /** Used only by BundledResolvers; no user-provided path escapes this whitelist. */
    fun open(context: Context, part: Int): InputStream? {
        if (part !in 1..6) return null
        val dir = current(context)
        val file = File(dir, "part_$part.txt")
        return file.takeIf { it.isFile && it.length() in 1..MAX_PART_BYTES.toLong() }?.inputStream()
    }

    suspend fun refresh(context: Context, pack: AppUpdateChecker.ResolverPack, progress: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            require(pack.size in 1..MAX_ZIP_BYTES) { "Invalid resolver pack size" }
            val directory = base(context)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare resolver pack folder" }
            val temp = File(directory, "incoming.zip")
            val staging = File(directory, "staging")
            try {
                UpdateNetworkPolicy.requireAllowed(context)
                temp.delete()
                val request = Request.Builder().url(pack.url).build()
                val digest = MessageDigest.getInstance("SHA-256")
                client.newCall(request).execute().use { reply ->
                    check(reply.isSuccessful) { "Resolver pack HTTP ${reply.code}" }
                    val body = reply.body ?: error("Empty resolver pack")
                    check(body.contentLength() <= pack.size) { "Resolver pack exceeds published size" }
                    var count = 0L
                    body.byteStream().use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            UpdateNetworkPolicy.requireAllowed(context)
                            val n = input.read(buffer)
                            if (n == -1) break
                            count += n
                            check(count <= pack.size && count <= MAX_ZIP_BYTES) { "Resolver pack too large" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            withContext(Dispatchers.Main) { progress((count * 100 / pack.size).toInt().coerceAtMost(100)) }
                        }
                    } }
                    check(count == pack.size) { "Truncated resolver pack" }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                check(hex.equals(pack.sha256, true)) { "Resolver pack checksum mismatch" }

                staging.deleteRecursively()
                check(staging.mkdir()) { "Cannot stage resolver pack" }
                val found = mutableSetOf<String>()
                ZipInputStream(temp.inputStream().buffered()).use { zip ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.nextEntry ?: break
                        check(!entry.isDirectory && entry.name in names && found.add(entry.name)) {
                            "Unexpected, repeated or unsafe resolver pack entry"
                        }
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            check(bytes.size() + n <= MAX_PART_BYTES) { "Resolver list too large" }
                            bytes.write(buffer, 0, n)
                        }
                        val text = bytes.toString(Charsets.UTF_8.name())
                        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                        check(lines.size in 50..10000 && lines.all(BundledResolvers::publicIpv4)) {
                            "Resolver list contains invalid or restricted addresses"
                        }
                        File(staging, entry.name).writeText(lines.distinct().joinToString("\n", postfix = "\n"))
                        zip.closeEntry()
                    }
                }
                check(found == names) { "Resolver pack must have all six lists" }
                synchronized(this@ResolverPackUpdater) {
                    val active = current(context)
                    val backup = previous(context)
                    backup.deleteRecursively()
                    if (active.exists()) check(active.renameTo(backup)) { "Could not back up current resolver pack" }
                    if (!staging.renameTo(active)) {
                        if (backup.exists()) backup.renameTo(active)
                        error("Could not activate resolver pack")
                    }
                    BundledResolvers.invalidate()
                }
                names.size
            } finally {
                temp.delete()
                staging.deleteRecursively()
            }
        }

    fun rollback(context: Context): Boolean = synchronized(this) {
        val old = previous(context)
        if (!old.isDirectory || names.any { !File(old, it).isFile }) return@synchronized false
        val active = current(context)
        val swap = File(base(context), "rollback-swap")
        swap.deleteRecursively()
        if (active.exists() && !active.renameTo(swap)) return@synchronized false
        if (!old.renameTo(active)) {
            swap.renameTo(active)
            return@synchronized false
        }
        if (swap.exists()) swap.renameTo(old)
        BundledResolvers.invalidate()
        true
    }
}
