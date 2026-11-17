package com.opentune.data

import org.junit.Assert.*
import org.junit.Test

class UnmeteredNetworkTest {
    private val wifi = NetworkFacts(vpn = false, unmetered = true, validated = true)
    private val mobile = NetworkFacts(vpn = false, unmetered = false, validated = true)
    private val meteredVpn = NetworkFacts(vpn = true, unmetered = false, validated = true)

    @Test fun plainNetworksAreTakenAtTheirWord() {
        assertTrue(unmeteredConnection(wifi, listOf(wifi)))
        assertFalse(unmeteredConnection(mobile, listOf(mobile)))
        assertFalse(unmeteredConnection(null, listOf(wifi)))
    }

    @Test fun aMeteredVpnOverWifiCountsAsWifi() {
        assertTrue(unmeteredConnection(meteredVpn, listOf(meteredVpn, wifi)))
        assertTrue(unmeteredConnection(meteredVpn, listOf(meteredVpn, mobile, wifi)))
    }

    @Test fun aVpnOverMobileDataStaysMetered() {
        assertFalse(unmeteredConnection(meteredVpn, listOf(meteredVpn, mobile)))
        assertFalse(unmeteredConnection(meteredVpn, listOf(meteredVpn)))
    }

    @Test fun wifiWithoutInternetDoesNotCount() {
        val captive = wifi.copy(validated = false)
        assertFalse(unmeteredConnection(meteredVpn, listOf(meteredVpn, captive, mobile)))
    }

    @Test fun anUnmeteredVpnNeedsNoLookUnderneath() {
        assertTrue(unmeteredConnection(meteredVpn.copy(unmetered = true), emptyList()))
    }
}
