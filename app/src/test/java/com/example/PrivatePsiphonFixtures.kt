package com.example

import org.json.JSONObject

/** Nonfunctional Psiphon OSSH server entry fixture; no real secrets or reachable server. */
object PrivatePsiphonFixtures {
    fun entry(ip: String = "203.0.113.7", port: Int = 8888): String {
        val json = JSONObject()
            .put("ipAddress", ip)
            .put("sshObfuscatedPort", port)
            .put("sshHostKey", "FAKE_TEST_SSH_KEY_NOT_USABLE")
            .put("sshUsername", "test-user")
            .put("sshPassword", "test-password-not-usable")
            .toString()
        val raw = "$ip 0 testSecret testCertificate $json"
        return raw.toByteArray(Charsets.UTF_8).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
    }
}
