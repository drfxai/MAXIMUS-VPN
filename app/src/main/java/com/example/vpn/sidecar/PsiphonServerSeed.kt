package com.example.vpn.sidecar

import java.io.File

/**
 * Gives Shir o Khorshid the Psiphon servers this phone already knows.
 *
 * Build 175 report (Irancell cell:43235): Shir o Khorshid printed only NetworkID, ListeningSocksProxyPort and
 * `AvailableEgressRegions []`, then nothing for 120 s, while Psiphon over Lantern had a tunnel in about 2 s. An empty
 * region list is the core saying its data store holds no server entry: the fork keeps its own data store
 * (engines/shirokhorshid/data), empty on first use, and the remote server list it would fetch is filtered on that link.
 * With no server entry there is nothing to dial, not even a CDN-fronted one (those reuse FRONTED-MEEK server entries).
 * Psiphon's own data store (filled by direct Psiphon or by Psiphon over Lantern, which fetches the list through
 * Lantern) has them.
 *
 * Both programs are the same tunnel core (the fork is upstream 4d6eb0e8 plus its additions, the commit our Psiphon is
 * built from), built without data-store tags, so both use the same bolt file `psiphon.boltdb` with the same buckets.
 * When Shir o Khorshid's file holds no server entry, the Psiphon file with the most entries is copied in before the
 * start. A file that holds entries is never replaced (it keeps the fork's own CDN-scan and replay state). The copy is
 * written beside the target and renamed into place; a damaged copy is reset by the core on open.
 */
object PsiphonServerSeed {
    const val CORE_DIR = "ca.psiphon.PsiphonTunnel.tunnel-core"
    const val DB_FILE = "psiphon.boltdb"
    /** Server entries are stored as JSON objects; every one carries this field. */
    private val ENTRY = "\"ipAddress\":\"".toByteArray(Charsets.US_ASCII)

    sealed class Result {
        object HasServers : Result()
        object NoSource : Result()
        data class Seeded(val from: File, val entries: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** The data-store file under a `-dataRootDirectory`. */
    fun datastore(dataRoot: File): File = File(dataRoot, "$CORE_DIR/datastore/$DB_FILE")

    /**
     * Psiphon data roots that may hold server entries for an engine whose work directory is [workDir]
     * (`engines/<id>` for a direct start, `engines/chain/<id>` for a chain hop): direct Psiphon's and the chain's.
     */
    fun psiphonRoots(workDir: File): List<File> {
        val parent = workDir.absoluteFile.parentFile ?: return emptyList()
        val engines = if (parent.name == "chain") parent.parentFile ?: return emptyList() else parent
        return listOf(
            File(engines, "${PsiphonSidecar.ID}/${PsiphonSidecar.DATA_DIR}"),
            File(engines, "chain/${PsiphonSidecar.ID}/${PsiphonSidecar.DATA_DIR}")
        )
    }

    /** Server entries in a bolt [file], counted up to [limit]; 0 for a missing or unreadable file. */
    fun countEntries(file: File, limit: Int = 64): Int {
        if (!file.isFile || file.length() == 0L) return 0
        return runCatching {
            var count = 0
            val keep = ENTRY.size - 1
            val buf = ByteArray(64 * 1024 + keep)
            var carried = 0
            file.inputStream().use { input ->
                while (count < limit) {
                    val n = input.read(buf, carried, buf.size - carried)
                    if (n <= 0) break
                    val end = carried + n
                    var i = 0
                    while (i <= end - ENTRY.size && count < limit) {
                        var j = 0
                        while (j < ENTRY.size && buf[i + j] == ENTRY[j]) j++
                        if (j == ENTRY.size) { count++; i += ENTRY.size } else i++
                    }
                    // Keep the tail so a pattern split across two reads is still found.
                    carried = minOf(keep, end)
                    System.arraycopy(buf, end - carried, buf, 0, carried)
                }
            }
            count
        }.getOrDefault(0)
    }

    /** Copies the best of [sources] (data roots) into [target]'s data store when it holds no server entry. */
    fun seed(target: File, sources: List<File>): Result {
        val targetDb = datastore(target)
        if (countEntries(targetDb, limit = 1) > 0) return Result.HasServers
        val best = sources.map { datastore(it) }
            .filter { it.absolutePath != targetDb.absolutePath }
            .map { it to countEntries(it) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<File, Int>> { it.second }.thenBy { it.first.lastModified() })
            ?: return Result.NoSource
        return try {
            targetDb.parentFile?.mkdirs()
            val tmp = File(targetDb.parentFile, "$DB_FILE.seed")
            best.first.copyTo(tmp, overwrite = true)
            if (targetDb.exists() && !targetDb.delete()) {
                tmp.delete()
                return Result.Failed("the old data store could not be replaced")
            }
            if (!tmp.renameTo(targetDb)) {
                tmp.delete()
                return Result.Failed("the copy could not be moved into place")
            }
            Result.Seeded(best.first, best.second)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName)
        }
    }
}
