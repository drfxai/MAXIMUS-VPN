package com.example.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Download an official signed release inside Maximus; never execute an arbitrary URL.
 * SHA-256 and signing certificate are verified before Android's installer is opened.
 * The platform installer (and any install-from-this-source permission) always requires user consent.
 */
object InAppUpdater {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private const val MIME = "application/vnd.android.package-archive"
    private const val MAX_BYTES = 700L * 1024L * 1024L

    suspend fun download(
        context: Context,
        asset: AppUpdateChecker.ApkAsset,
        onStatus: (String) -> Unit = {},
        onProgress: (Int) -> Unit
    ): File =
        withContext(Dispatchers.IO) {
            require(asset.size in 1..MAX_BYTES) { "APK size is invalid" }
            val dir = File(context.filesDir, "updates").apply { check(isDirectory || mkdirs()) { "Cannot create update folder" } }
            val ready = File(dir, "maximus-verified.apk")
            val temporary = File(dir, "maximus-downloading.part")
            try {
                UpdateNetworkPolicy.requireAllowed(context)
                temporary.delete()
                withContext(Dispatchers.Main) { onStatus("Connecting to official GitHub release…") }
                val request = Request.Builder().url(asset.url).header("Accept", "application/octet-stream").build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "APK download failed (HTTP ${response.code})" }
                    val body = response.body ?: error("Empty APK download")
                    if (body.contentLength() > MAX_BYTES) error("APK exceeds size limit")
                    withContext(Dispatchers.Main) { onStatus("Connected • receiving ${asset.size / (1024 * 1024)} MiB signed APK") }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var read = 0L
                    var lastProgress = -1
                    var lastBytesReported = 0L
                    body.byteStream().use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                UpdateNetworkPolicy.requireAllowed(context)
                                val n = input.read(buffer)
                                if (n < 0) break
                                read += n
                                check(read <= MAX_BYTES && read <= asset.size) { "APK size mismatch" }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                                val pct = (read * 100L / asset.size).toInt().coerceIn(0, 100)
                                if (pct != lastProgress) {
                                    lastProgress = pct
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                                if (read - lastBytesReported >= 512 * 1024 || read == asset.size) {
                                    lastBytesReported = read
                                    withContext(Dispatchers.Main) {
                                        onStatus("Received ${read / 1024} / ${asset.size / 1024} KiB")
                                    }
                                }
                            }
                        }
                    }
                    withContext(Dispatchers.Main) { onStatus("Checking SHA-256 digest…") }
                    check(read == asset.size) { "Incomplete APK download" }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actual.equals(asset.sha256, ignoreCase = true)) { "APK SHA-256 verification failed" }
                }
                withContext(Dispatchers.Main) { onStatus("Verifying publisher signature and Android package…") }
                verifyPackage(context, temporary)
                check(temporary.renameTo(ready)) { "Could not finalize verified APK" }
                withContext(Dispatchers.Main) { onProgress(100) }
                ready
            } finally {
                temporary.delete()
            }
        }

    @Suppress("DEPRECATION")
    private fun certificates(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            packageInfo.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, apk: File) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("Downloaded file is not a valid APK")
        check(archive.packageName == context.packageName) { "Update has a different package ID" }
        val installed = manager.getPackageInfo(context.packageName, flags)
        val expected = certificates(installed)
        check(expected.isNotEmpty() && certificates(archive) == expected) { "Update is not signed by the installed app publisher" }
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(newCode > oldCode) { "Update must increment Android versionCode" }
    }

    /**
     * Returns false if Android must first grant the per-source install permission.
     * In that case the verified file stays in private storage for the user's next tap.
     */
    fun install(context: Context, apk: File): Boolean {
        check(apk.isFile && apk.parentFile?.canonicalFile == File(context.filesDir, "updates").canonicalFile) {
            "Only a verified APK in the private updates folder may be installed"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
