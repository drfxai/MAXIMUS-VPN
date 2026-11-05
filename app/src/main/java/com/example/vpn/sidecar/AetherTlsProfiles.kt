package com.example.vpn.sidecar

/** Wire parameters for the client profiles exposed by PattN's Aether editor. */
object AetherTlsProfiles {
    val names = listOf("chrome", "chrome-120", "firefox", "semi-python", "go")
    private const val chromeSignatures = "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256:rsa_pkcs1_sha256:ecdsa_secp384r1_sha384:rsa_pss_rsae_sha384:rsa_pkcs1_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha512"
    private const val hybridGroups = "X25519MLKEM768:X25519:P-256:P-384:P-521"
    private const val trustAnchors = "44947.2.1:44947.2.6:44947.2.13:44947.2.14:44947.2.15:44947.2.18:44947.2.19:44947.2.20:52580.200109.1.7:52580.200109.1.8:52580.200109.1.9:52580.200109.1.10:52580.200109.1.11:52580.200109.1.12:52580.200109.1.13:52580.200109.1.18:52580.200109.1.19:11129.9.1:11129.9.4:11129.9.5:11129.9.6:11129.9.7:11129.9.8:11129.9.10:11129.9.11:11129.9.12:11129.9.13:11129.9.15"

    fun arguments(name: String): List<String> {
        require(name in names)
        val ciphers = when (name) {
            "firefox" -> "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-SHA:ECDHE-RSA-AES256-SHA:AES128-GCM-SHA256:AES256-GCM-SHA384:AES128-SHA:AES256-SHA"
            "semi-python" -> "ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA:ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA256"
            "go" -> "ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES128-SHA:ECDHE-ECDSA-AES256-SHA:ECDHE-RSA-AES256-SHA"
            else -> "ALL:!aPSK:!ECDSA+SHA1:!3DES"
        }
        val signatures = when (name) {
            "firefox" -> "ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:rsa_pss_rsae_sha256:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512:ecdsa_sha1:rsa_pkcs1_sha1"
            "semi-python" -> "mldsa65:mldsa87:mldsa44:ecdsa_secp256r1_sha256:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512:ed25519:rsa_pss_rsae_sha256:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512"
            "go" -> "mldsa44:mldsa65:mldsa87:rsa_pss_rsae_sha256:ecdsa_secp256r1_sha256:ed25519:rsa_pss_rsae_sha384:rsa_pss_rsae_sha512:rsa_pkcs1_sha256:rsa_pkcs1_sha384:rsa_pkcs1_sha512:ecdsa_secp384r1_sha384:ecdsa_secp521r1_sha512"
            "chrome" -> "mldsa44:mldsa65:mldsa87:$chromeSignatures"
            else -> chromeSignatures
        }
        return buildList {
            addAll(listOf("--tls-ciphers", ciphers, "--tls-groups",
                when(name) { "chrome" -> "X25519MLKEM768:X25519:P-256:P-384"; "chrome-120" -> "X25519:P-256:P-384"; else -> hybridGroups },
                "--tls-sigalgs", signatures))
            when (name) {
                "chrome" -> addAll(listOf("--tls-cert-compression", "brotli", "--tls-alps", "new",
                    "--tls-trust-anchors", trustAnchors, "--tls-server-padding", "6000",
                    "--enable-grease-sigalgs", "--enable-ech-grease"))
                "chrome-120" -> addAll(listOf("--tls-cert-compression", "brotli", "--tls-alps", "old", "--enable-ech-grease"))
                "firefox" -> addAll(listOf("--tls13-ciphers", "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
                    "--tls-key-shares", "X25519MLKEM768:X25519:P-256", "--tls-cert-compression", "zlib:brotli:zstd", "--enable-ech-grease"))
                "semi-python" -> addAll(listOf("--tls13-ciphers", "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256",
                    "--disable-ocsp-stapling", "--disable-sct"))
            }
            if (name in listOf("firefox", "semi-python", "go")) {
                addAll(listOf("--disable-grease", "--disable-extension-permutation"))
                if (name != "semi-python") add("--disable-session-ticket")
            }
        }
    }
}
