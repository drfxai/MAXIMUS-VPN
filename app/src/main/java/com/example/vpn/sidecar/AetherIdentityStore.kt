package com.example.vpn.sidecar

import java.io.File

/** A whole identity generation becomes visible with one same-filesystem pointer rename. */
object AetherIdentityStore {
    val files = listOf("wireguard.toml", "identity.toml", "wireguard-secondary.toml", "identity-secondary.toml", "identity-gool.toml")

    fun required(kind: String): List<String> = when (kind) {
        "wg" -> listOf(files[0])
        "masque" -> listOf(files[1])
        "gool-classic" -> listOf(files[0], files[2])
        "mim" -> listOf(files[1], files[3])
        "gool" -> listOf(files[1], files[4])
        "all" -> files
        else -> error("Unsupported key kind")
    }

    fun current(root: File): File {
        val name = runCatching { File(root, "active-generation").readText().trim() }.getOrDefault("")
        return if (name.matches(Regex("generation-[A-Za-z0-9_-]+")) && File(root, name).isDirectory) File(root, name) else root
    }

    fun retainUnchanged(root: File, staging: File, kind: String) {
        val replaced = required(kind).toSet()
        for (name in files - replaced) {
            val old = File(current(root), name)
            if (old.isFile) old.copyTo(File(staging, name))
        }
    }

    fun publish(root: File, staging: File, kind: String, token: String) {
        require(token.matches(Regex("[A-Za-z0-9_-]+")))
        required(kind).forEach { name ->
            check(File(staging, name).let { it.isFile && it.length() in 32..131072 }) { "Registration did not produce every requested identity" }
        }
        // Registration caches, CA bundles and auxiliary proxy configs are disposable.
        staging.listFiles()?.filter { it.name !in files }?.forEach {
            check(it.deleteRecursively()) { "Cannot remove registration working files" }
        }
        val generation = File(root, "generation-$token")
        check(!generation.exists() && staging.renameTo(generation)) { "Cannot stage the identity generation" }
        val pointer = File(root, "pending-pointer-$token")
        try {
            pointer.outputStream().use { stream ->
                stream.write(generation.name.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            check(pointer.renameTo(File(root, "active-generation"))) { "Cannot activate the identity generation" }
        } catch (e: Throwable) {
            generation.deleteRecursively()
            throw e
        } finally { pointer.delete() }
    }
}
