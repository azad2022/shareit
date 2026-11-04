package com.peyvand.core.domain.connection

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pTcpEndpointPolicyTest {
    @Test
    fun `selects only a usable private IPv4 address from the supplied P2P interface`() {
        val candidates = listOf(
            candidate("127.0.0.1", 8),
            candidate("169.254.10.2", 16),
            candidate("192.168.49.1", 24),
            candidate("10.0.0.1", 24),
        )

        assertEquals(
            candidate("192.168.49.1", 24),
            P2pTcpEndpointPolicy.selectLocalAddress(candidates),
        )
    }

    @Test
    fun `does not select public wildcard multicast or host-only addresses`() {
        val candidates = listOf(
            candidate("0.0.0.0", 24),
            candidate("224.0.0.1", 24),
            candidate("8.8.8.8", 24),
            candidate("192.168.49.1", 31),
            candidate("192.168.49.2", 32),
        )

        assertNull(P2pTcpEndpointPolicy.selectLocalAddress(candidates))
    }

    @Test
    fun `accepts remote group owner only when it shares the P2P subnet`() {
        val local = candidate("192.168.49.1", 24)

        assertTrue(P2pTcpEndpointPolicy.isOnSameSubnet(local, ipv4("192.168.49.2")))
        assertFalse(P2pTcpEndpointPolicy.isOnSameSubnet(local, ipv4("192.168.50.2")))
        assertFalse(P2pTcpEndpointPolicy.isOnSameSubnet(local, ipv4("192.168.49.1")))
        assertFalse(P2pTcpEndpointPolicy.isOnSameSubnet(local, ipv4("8.8.8.8")))
        assertFalse(P2pTcpEndpointPolicy.isOnSameSubnet(local, ipv4("169.254.1.10")))
    }

    @Test
    fun `rejects invalid local interface prefixes for subnet checks`() {
        assertFalse(
            P2pTcpEndpointPolicy.isOnSameSubnet(
                candidate("192.168.49.1", 0),
                ipv4("192.168.49.2"),
            ),
        )
        assertFalse(
            P2pTcpEndpointPolicy.isOnSameSubnet(
                candidate("192.168.49.1", 32),
                ipv4("192.168.49.2"),
            ),
        )
    }

    private fun candidate(address: String, prefix: Int) =
        P2pIpv4InterfaceAddress(ipv4(address), prefix)

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address
}
