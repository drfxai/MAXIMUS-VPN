package com.example.vpn.subscription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class FakeDnsAnswerTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun tunnelPlaceholdersAreRecognised() {
        assertTrue(SubscriptionManager.isFakeDnsAddress(ip("198.18.0.7")))
        assertTrue(SubscriptionManager.isFakeDnsAddress(ip("198.19.255.1")))
        assertTrue(SubscriptionManager.isFakeDnsAddress(ip("fc00::12")))
    }

    @Test
    fun realAddressesAreNot() {
        assertFalse(SubscriptionManager.isFakeDnsAddress(ip("104.21.32.1")))
        assertFalse(SubscriptionManager.isFakeDnsAddress(ip("198.20.0.1")))
        assertFalse(SubscriptionManager.isFakeDnsAddress(ip("2606:4700::1")))
        assertFalse(SubscriptionManager.isFakeDnsAddress(ip("fd00::1")))
    }
}
