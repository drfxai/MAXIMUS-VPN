package com.example.panels.servers

/**
 * The two DNS records a DNS tunnel needs at the user's domain, and a check of them through DoH.
 * With base domain example.com: `ns.example.com A <server IP>` and `t.example.com NS ns.example.com`.
 */
object DnsDelegation {
    data class Record(val name: String, val type: String, val value: String, val note: String)

    private val LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,20}[a-z0-9])?$")
    private val BASE = Regex("^(?=.{4,190}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    fun normalizeBase(input: String): String = input.trim().lowercase()
        .removePrefix("https://").removePrefix("http://").substringBefore('/').trimEnd('.')

    /** A user-facing problem with the domain input, or null. */
    fun problem(base: String, label: String): String? = when {
        !BASE.matches(normalizeBase(base)) -> "Enter a domain you own, like example.com"
        !LABEL.matches(label) -> "The tunnel name may use a-z, 0-9 and -"
        else -> null
    }

    /** The most backup domains a tunnel may have (the client's limit too). */
    const val MAX_BACKUPS = 3

    /** Backup domains typed as a list: one per line, or separated by commas or spaces. */
    fun parseBackups(input: String): List<String> =
        input.split(',', ' ', '\n', '\t').map { normalizeBase(it) }.filter { it.isNotEmpty() }.distinct()

    /** A user-facing problem with the backup domains, or null. Each is a base domain like the main one. */
    fun backupProblem(main: String, backups: List<String>): String? = when {
        backups.size > MAX_BACKUPS -> "Up to $MAX_BACKUPS backup domains"
        backups.any { !BASE.matches(it) } -> "Backup domains must be domains you own, like example.net"
        normalizeBase(main) in backups -> "A backup domain repeats your main domain"
        else -> null
    }

    fun tunnelDomain(base: String, label: String) = "$label.${normalizeBase(base)}"
    fun nameServer(base: String) = "ns.${normalizeBase(base)}"

    /** Two records per domain, the main domain first: the name server's address, then the delegation. */
    fun records(base: String, label: String, serverIp: String, backups: List<String> = emptyList()): List<Record> =
        (listOf(base) + backups).flatMapIndexed { i, b ->
            listOf(
                Record(nameServer(b), if (serverIp.contains(':')) "AAAA" else "A", serverIp,
                    if (i == 0) "Your server. On Cloudflare keep it \"DNS only\" (grey cloud)." else "Backup domain: your server again."),
                Record(tunnelDomain(b, label), "NS", nameServer(b),
                    if (i == 0) "Hands the tunnel name to your server." else "Used when the main name is blocked.")
            )
        }

    enum class Status { OK, WRONG, MISSING, UNKNOWN }
    data class Check(val record: Record, val status: Status, val detail: String)

    /**
     * Judges DoH JSON answers (application/dns-json): [aJson] for the name server's address and
     * [nsJson] for the tunnel name's NS. A delegation pointing at a server that answers only tunnel
     * traffic often makes resolvers fail the NS query (SERVFAIL), so that reads UNKNOWN, not wrong:
     * the real test through the tunnel decides.
     */
    fun judge(records: List<Record>, aJson: String?, nsJson: String?): List<Check> {
        val (aRec, nsRec) = records
        fun answers(json: String?, type: Int): Pair<Int, List<String>>? = runCatching {
            val o = org.json.JSONObject(requireNotNull(json))
            val arr = o.optJSONArray("Answer") ?: org.json.JSONArray()
            o.optInt("Status", -1) to (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optInt("type") == type }.map { it.optString("data").trimEnd('.').lowercase() }
        }.getOrNull()
        val a = answers(aJson, if (aRec.type == "AAAA") 28 else 1)
        val aCheck = when {
            a == null -> Check(aRec, Status.UNKNOWN, "Could not ask DNS")
            aRec.value.lowercase() in a.second -> Check(aRec, Status.OK, "Points to ${aRec.value}")
            a.second.isNotEmpty() -> Check(aRec, Status.WRONG, "Points to ${a.second.joinToString()} instead of ${aRec.value}")
            else -> Check(aRec, Status.MISSING, "Not found yet (new records can take a few minutes)")
        }
        val ns = answers(nsJson, 2)
        val nsCheck = when {
            ns == null -> Check(nsRec, Status.UNKNOWN, "Could not ask DNS")
            nsRec.value in ns.second -> Check(nsRec, Status.OK, "Delegated to ${nsRec.value}")
            ns.second.isNotEmpty() -> Check(nsRec, Status.WRONG, "Delegated to ${ns.second.joinToString()} instead of ${nsRec.value}")
            ns.first == 2 -> Check(nsRec, Status.UNKNOWN, "Resolvers reach your server but cannot list it; the real test decides")
            else -> Check(nsRec, Status.MISSING, "Not found yet (new records can take a few minutes)")
        }
        return listOf(aCheck, nsCheck)
    }
}
