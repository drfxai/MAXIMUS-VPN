package com.example.vpn.smartsni

/**
 * Build 181 (Phase 5): the user's Smart SNI, Smart ECH and clean-IP scanner settings (Settings → Anti-censorship).
 * Kept in their own preferences file ("smart_sni"); RayApplication installs [read]/[write]. Unit tests leave the
 * defaults: everything smart is on, no manual value.
 *
 * A manual value always wins over the automatic choice until the user clears it. Invalid manual values are never
 * saved ([SmartSni.validName], [SmartEch.validConfigList]); the settings screen shows why.
 */
object SmartSniSettings {
    @Volatile var read: (String) -> String? = { null }
    @Volatile var write: (String, String?) -> Unit = { _, _ -> }

    const val SMART_SNI = "smartSni"
    const val MANUAL_SNI = "manualSni"
    const val CLEAN_IP_SCAN = "cleanIpScan"
    const val SMART_ECH = "smartEch"
    const val MANUAL_ECH_OUTER = "manualEchOuter"
    const val MANUAL_ECH_CONFIG = "manualEchConfig"

    private fun flag(key: String, default: Boolean): Boolean =
        runCatching { read(key) }.getOrNull()?.toBooleanStrictOrNull() ?: default

    private fun text(key: String): String =
        runCatching { read(key) }.getOrNull()?.trim().orEmpty()

    /** Smart SNI (on by default): probe and remember a TLS name the filter lets through, per network. */
    val smartSni: Boolean get() = flag(SMART_SNI, true)
    /** The user's own TLS name; blank: automatic. */
    val manualSni: String get() = text(MANUAL_SNI).lowercase().takeIf { SmartSni.validName(it) }.orEmpty()
    /** Smart clean-IP scanner (on by default). */
    val cleanIpScan: Boolean get() = flag(CLEAN_IP_SCAN, true)
    /** Smart ECH (on by default). */
    val smartEch: Boolean get() = flag(SMART_ECH, true)
    /** The user's ECH outer name (the name whose HTTPS record is looked up); blank: automatic. */
    val manualEchOuter: String get() = text(MANUAL_ECH_OUTER).lowercase().takeIf { SmartSni.validName(it) }.orEmpty()
    /** The user's ECHConfigList (base64); blank: automatic (DoH). */
    val manualEchConfig: String get() = text(MANUAL_ECH_CONFIG).takeIf { SmartEch.validConfigList(it) }.orEmpty()

    fun setFlag(key: String, value: Boolean) = runCatching { write(key, value.toString()) }

    /**
     * Saves a manual value: blank clears it; an invalid one is refused and its reason returned (nothing saved).
     * Returns null when saved.
     */
    fun setManual(key: String, value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) { runCatching { write(key, null) }; return null }
        val error = when (key) {
            MANUAL_SNI, MANUAL_ECH_OUTER -> if (SmartSni.validName(v.lowercase())) null else "not a valid domain name"
            MANUAL_ECH_CONFIG -> if (SmartEch.validConfigList(v)) null else "not a valid base64 ECHConfigList"
            else -> "unknown setting"
        }
        if (error == null) runCatching { write(key, if (key == MANUAL_ECH_CONFIG) v else v.lowercase()) }
        return error
    }
}
