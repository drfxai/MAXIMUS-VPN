package com.example.vpn.hub

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * The signed list the aggregator publishes (`tools/aggregator`). The manifest names each file's
 * SHA-256; its signature is checked against the public key built into the app, so a changed or
 * unsigned list is refused whatever the address served it. Before the key and the published list
 * exist, [PUBLIC_KEY_DER_BASE64] is empty and every list is refused as unsigned.
 */
object HubManifest {
    /**
     * The aggregator's ECDSA P-256 public key, DER, base64. The release build derives it from the
     * HUB_SIGNING_KEY secret; it is empty in builds made without that secret.
     */
    val PUBLIC_KEY_DER_BASE64: String = com.example.BuildConfig.HUB_PUBLIC_KEY

    private const val ALGORITHM = "SHA256withECDSA"

    data class Manifest(
        val version: Int, val created: String, val count: Int, val sha256: Map<String, String>,
        val verificationPolicy: String? = null, val validUntilSeconds: Long = 0,
        val minimumNetworks: Int = 0
    )

    class Refused(message: String) : IllegalArgumentException(message)

    /**
     * Checks [signatureBase64] over [manifestJson] and returns what the manifest says. Throws
     * [Refused] when there is no key, the signature does not match, or the manifest is unreadable.
     */
    fun verify(manifestJson: ByteArray, signatureBase64: String, publicKeyBase64: String = PUBLIC_KEY_DER_BASE64): Manifest {
        if (publicKeyBase64.isBlank()) throw Refused("This build has no key for the configuration list")
        val signature = runCatching { Base64.decode(signatureBase64.trim(), Base64.DEFAULT) }.getOrNull()
            ?: throw Refused("The configuration list is not signed")
        if (signature.isEmpty()) throw Refused("The configuration list is not signed")
        val ok = runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(manifestJson)
                verify(signature)
            }
        }.getOrDefault(false)
        if (!ok) throw Refused("The configuration list's signature does not match")
        val root = runCatching { JSONObject(String(manifestJson, Charsets.UTF_8)) }.getOrNull()
            ?: throw Refused("The configuration list's manifest is unreadable")
        val files = root.optJSONObject("files") ?: throw Refused("The manifest lists no files")
        val sha = files.keys().asSequence().associateWith { files.getJSONObject(it).optString("sha256") }
        val verification = root.optJSONObject("verification")
        return Manifest(root.optInt("version", 0), root.optString("created"), root.optInt("count"), sha,
            verification?.optString("policy"), verification?.optLong("valid_until") ?: 0,
            verification?.optInt("minimum_networks") ?: 0)
    }

    /** True when [content] is the file [name] as the manifest describes it. */
    fun matches(manifest: Manifest, name: String, content: ByteArray): Boolean {
        val expected = manifest.sha256[name] ?: return false
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(content)
        return expected.equals(digest.joinToString("") { "%02x".format(it) }, ignoreCase = true)
    }
}
