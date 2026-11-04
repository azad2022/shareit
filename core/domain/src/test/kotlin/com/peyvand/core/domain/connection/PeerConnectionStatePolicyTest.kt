package com.peyvand.core.domain.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerConnectionStatePolicyTest {
    @Test
    fun `only a currently listed candidate can be selected during active discovery`() {
        val candidates = listOf("02:11:22:33:44:55", "02:aa:bb:cc:dd:ee")

        assertTrue(
            PeerConnectionStatePolicy.mayInitiate(
                peerId = candidates.first(),
                currentlyDiscoveredPeerIds = candidates,
                isDiscoveryActive = true,
            ),
        )
        assertFalse(
            PeerConnectionStatePolicy.mayInitiate(
                peerId = "02:00:00:00:00:01",
                currentlyDiscoveredPeerIds = candidates,
                isDiscoveryActive = true,
            ),
        )
        assertFalse(
            PeerConnectionStatePolicy.mayInitiate(
                peerId = candidates.first(),
                currentlyDiscoveredPeerIds = candidates,
                isDiscoveryActive = false,
            ),
        )
        assertFalse(
            PeerConnectionStatePolicy.mayInitiate(
                peerId = " ",
                currentlyDiscoveredPeerIds = candidates,
                isDiscoveryActive = true,
            ),
        )
    }

    @Test
    fun `group owner can form without a usable local owner address`() {
        assertTrue(PeerConnectionStatePolicy.isGroupFormed(true, true, null))
        assertFalse(PeerConnectionStatePolicy.isGroupFormed(false, true, null))
    }

    @Test
    fun `group client requires a usable remote owner address`() {
        assertTrue(PeerConnectionStatePolicy.isGroupFormed(true, false, "192.168.49.1"))
        assertFalse(PeerConnectionStatePolicy.isGroupFormed(false, false, "192.168.49.1"))
        assertFalse(PeerConnectionStatePolicy.isGroupFormed(true, false, null))
        assertFalse(PeerConnectionStatePolicy.isGroupFormed(true, false, " "))
    }
}
