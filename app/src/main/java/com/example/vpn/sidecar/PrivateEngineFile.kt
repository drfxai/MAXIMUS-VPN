package com.example.vpn.sidecar

import java.io.File
import java.io.FileOutputStream

/** Replace a stopped engine's config without following a stale symlink or exposing partial secrets. */
internal object PrivateEngineFile {
    fun write(target: File, text: String) {
        val parent = target.parentFile.canonicalFile
        check(parent.isDirectory) { "Engine work directory is missing" }
        check(target.canonicalFile == File(parent, target.name)) { "Invalid engine work file" }
        val temp = File.createTempFile(".engine-", ".tmp", parent)
        try {
            check(temp.setReadable(false, false) && temp.setWritable(false, false) &&
                temp.setReadable(true, true) && temp.setWritable(true, true)) { "Cannot protect engine work file" }
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            check(temp.renameTo(target)) { "Cannot replace engine work file" }
        } finally {
            temp.delete()
        }
    }
}
