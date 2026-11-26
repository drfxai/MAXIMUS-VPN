package com.example.vpn.unlock

/**
 * Which Unlock servers one app question is asked of, in order, and what came of it. The current server first;
 * when its answer is unusable ([UnlockDnsAnswer.Result.Unusable]), the next usable servers of the last health
 * check, while the question's budget lasts. Pure logic, so the order and the log text are unit-tested.
 */
object UnlockDnsAnswerPlan {
    data class Outcome(
        val answer: ByteArray?,
        val server: String?,
        val ms: Long?,
        val note: String?,
        /** Servers whose answers could not be used, with the reason, in the order asked. */
        val problems: List<Pair<String, UnlockDnsAnswer.Problem>>
    )

    /** [current] first, then up to [extra] other usable servers in rank order. */
    fun order(current: String, checks: List<ServerCheck>, extra: Int): List<String> =
        listOf(current) + UnlockDnsHealth.rank(checks).filter { it.usable && it.ip != current }.map { it.ip }.take(extra)

    fun run(
        servers: List<String>,
        budgetMs: Long,
        elapsed: () -> Long,
        ask: (String) -> UnlockDnsWire.Exchange?,
        prepare: (UnlockDnsWire.Exchange) -> UnlockDnsAnswer.Result
    ): Outcome {
        val problems = mutableListOf<Pair<String, UnlockDnsAnswer.Problem>>()
        for ((i, ip) in servers.withIndex()) {
            if (i > 0 && elapsed() >= budgetMs) break
            val exchange = ask(ip)
            if (exchange == null) {
                problems += ip to UnlockDnsAnswer.Problem.NO_ANSWER
                continue
            }
            when (val result = prepare(exchange)) {
                is UnlockDnsAnswer.Result.Ok -> return Outcome(result.response, ip, exchange.ms, result.note, problems)
                is UnlockDnsAnswer.Result.Unusable -> problems += ip to result.problem
            }
        }
        return Outcome(null, null, null, null, problems)
    }

    /** One log line: "Unlock DNS: chatgpt.com A answered by 5.175.161.3 (193.233.112.67: no answer over UDP or TCP)." */
    fun describe(name: String, outcome: Outcome): String {
        val why = outcome.problems.joinToString("; ") { (ip, p) -> "$ip: ${p.text}" }
        return when {
            outcome.answer == null && outcome.problems.isEmpty() -> "Unlock DNS: $name failed (no server to ask); the app got SERVFAIL."
            outcome.answer == null -> "Unlock DNS: $name failed ($why); the app got SERVFAIL."
            outcome.problems.isEmpty() -> "Unlock DNS: $name answered by ${outcome.server}" + (outcome.note?.let { " ($it)." } ?: ".")
            else -> "Unlock DNS: $name answered by ${outcome.server} ($why)" + (outcome.note?.let { "; $it." } ?: ".")
        }
    }
}
