package com.example.vpn.smart

/**
 * Readable names for network keys ([NetworkKey], [NetworkIdentity]). Mobile carriers are named from their
 * MCC+MNC code; a Wi-Fi or Ethernet key is a salted fingerprint and is never turned into a name, so no
 * SSID is read, shown or stored. Pure.
 */
object NetworkNames {
    private val CARRIERS = mapOf(
        "43211" to "Hamrah-e Aval (MCI)",
        "43235" to "Irancell",
        "43220" to "Rightel",
        "43232" to "Taliya",
        "43270" to "TCI mobile"
    )

    /** The carrier for an MCC+MNC code, or null when it is not a known one. */
    fun carrier(code: String): String? = CARRIERS[code]

    /** A short name for the network the phone is on ("Irancell", "Wi-Fi"), or null when nothing is known. */
    fun short(key: String?): String? {
        val k = key ?: return null
        return when {
            k.startsWith("cell:") -> carrier(k.removePrefix("cell:")) ?: "Mobile data"
            k == "cell" -> "Mobile data"
            k == "wifi" || k.startsWith("wifi:") -> "Wi-Fi"
            k == "ethernet" || k.startsWith("ethernet:") -> "Ethernet"
            else -> null
        }
    }

    /** The name a remembered network is listed under in Settings. */
    fun learned(key: String): String = when {
        key.startsWith("cell:") -> key.removePrefix("cell:").let { code -> carrier(code) ?: "Mobile carrier $code" }
        key == "cell" -> "Mobile network"
        key == "wifi" || key.startsWith("wifi:") -> WIFI
        key == "ethernet" || key.startsWith("ethernet:") -> "Ethernet network"
        else -> "Other network"
    }

    const val WIFI = "Wi-Fi network (name not stored)"
}
