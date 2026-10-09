package com.example.vpn.lab

import com.example.vpn.smart.NetworkCapabilityProfile

/**
 * Decides, from the classified network state, whether LAB should spend its probe budget at all and which
 * candidate strategies to try first. It only orders and gates; candidates still pass the mutation policy
 * and the security gate, and nothing here can add a strategy the generator would not produce.
 */
object ExperimentPlanner {

    /** States where no change to a config can help: automatic experiments would only spend the budget. */
    private val NO_EGRESS = setOf(
        NetworkState.FULL_ISOLATION, NetworkState.DOMESTIC_ONLY_NO_VERIFIED_EGRESS, NetworkState.NIN_WITH_DNS_EGRESS
    )

    /** Null when an automatic experiment may run; otherwise the reason it was not started. */
    fun automaticRefusal(reading: NetworkStateReading?): String? = when {
        reading == null -> null
        reading.primary in NO_EGRESS ->
            "${reading.primary.title}: no international site answers, so no config change can help. LAB is not spending tests."
        else -> null
    }

    /**
     * Puts the strategies the measurements point at first. SNI filtering or cut TLS: fragment and ECH before
     * the rest. Tampered DNS: a validated edge address first. Measured-unusable paths are dropped.
     */
    fun order(hypotheses: List<Hypothesis>, net: NetworkCapabilityProfile?): List<Hypothesis> {
        if (net == null) return hypotheses
        val tlsCut = net.sniFiltered == true || (net.internationalReachable == false && net.tcpAvailable == true)
        val first = buildList {
            if (tlsCut) addAll(listOf("fragment", "ech"))
            if (net.dnsManipulated == true) add("endpoint")
        }
        val kept = hypotheses.filterNot { it.strategy == "address-family" && net.ipv6Available == false }
        return kept.sortedBy { h -> first.indexOf(h.strategy).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}
