package com.opentune.data.account

import org.junit.Assert.*
import org.junit.Test

class PairingCryptoTest {
    @Test fun authenticatedPacketsRejectTamperingAndWrongDirection() {
        val key = PairingCrypto.secret()
        val packet = PairingCrypto.seal(key, "test session".toByteArray(), "session")
        assertEquals("test session", String(PairingCrypto.open(key, packet, "session")))
        assertTrue(runCatching { PairingCrypto.open(key, packet, "ack") }.isFailure)
        packet[15] = (packet[15].toInt() xor 1).toByte()
        assertTrue(runCatching { PairingCrypto.open(key, packet, "session") }.isFailure)
    }
    @Test fun qrExpiresAndOnlyAllowsLocalLiteralAddresses() {
        val offer = PairingOffer.create("192.168.1.42", 40000, 1000, steady = 0)
        // The phone times the code from when it read it, by its own clock.
        val read = PairingOffer.parse(offer.payload, steady = 50_000)
        assertEquals(offer.host, read.host)
        assertTrue(read.valid(steady = 50_000 + 119_000))
        assertFalse(read.valid(steady = 50_000 + 120_000))
        assertFalse(offer.valid(steady = 120_000))
        listOf("127.0.0.1", "8.8.8.8", "192.168.1.001", "localhost", "10.0.0.1.example.com", "169.254.169.254", "172.32.0.1", "100.128.0.1").forEach {
            assertFalse(it, PairingCrypto.privateHost(it))
        }
        listOf("10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.3.7", "169.254.10.20").forEach { assertTrue(it, PairingCrypto.privateHost(it)) }
    }
}
