package com.example.vpn.lab

/**
 * The gate between an AI checkpoint and the planner: AI proposal → schema (known family names only) →
 * capability (families with a saved config that the plan does not skip) → order only. Nothing the AI says
 * can start a connection, change a config or credential, touch security settings or mark a path verified;
 * the planner's experiments stay deterministic. Pure.
 */
object AiCheckpoint {
    const val MAX_FAMILIES = 3

    fun validate(answer: List<String>?, offered: Set<PathFamily>): List<PathFamily> =
        answer.orEmpty().asSequence()
            .map { it.trim().uppercase().replace(' ', '_').replace('-', '_') }
            .mapNotNull { name -> PathFamily.entries.firstOrNull { it.name == name } }
            .filter { it in offered }
            .distinct()
            .take(MAX_FAMILIES)
            .toList()
}
