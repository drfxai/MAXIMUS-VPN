package com.example.vpn.lab.research

/**
 * Architecture only (spec section 37): a probe that would run on a machine inside another network, such as the
 * server inside Iran planned for later. Nothing implements it yet, and nothing sends this phone's data anywhere:
 * LAB measures only the network the phone is on. An implementation must authenticate the probe host, send no
 * config credentials, and label its results as remote evidence, never as this phone's measurement.
 */
interface RemoteProbe {
    data class Request(val probeId: String, val target: String, val transport: String)
    data class Result(val probeId: String, val networkLabel: String, val success: Boolean?, val latencyMs: Long?, val measuredAt: Long)

    /** Where the probe runs, shown on every result. */
    val label: String

    suspend fun run(request: Request): Result
}
