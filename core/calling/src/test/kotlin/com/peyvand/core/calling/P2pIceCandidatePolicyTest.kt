package com.peyvand.core.calling

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pIceCandidatePolicyTest {
    private val local = ipv4("192.168.49.1")
    private val remote = ipv4("192.168.49.2")

    @Test
    fun acceptsOnlyExpectedNumericUdpHostCandidate() {
        val localCandidate = "candidate:1 1 UDP 2122260223 192.168.49.1 50000 typ host generation 0"
        val remoteCandidate = "candidate:2 1 udp 2122260223 192.168.49.2 50001 typ host generation 0"
        assertNotNull(P2pIceCandidatePolicy.parseCandidateLine(localCandidate))
        assertTrue(P2pIceCandidatePolicy.isAllowedLocalCandidate(localCandidate, local))
        assertTrue(P2pIceCandidatePolicy.isAllowedRemoteCandidate(remoteCandidate, remote))
    }

    @Test
    fun rejectsCandidateOnAnyOtherInterfaceAddress() {
        val uplinkCandidate = "candidate:1 1 udp 2122260223 192.168.1.20 50000 typ host"
        assertFalse(P2pIceCandidatePolicy.isAllowedLocalCandidate(uplinkCandidate, local))
        assertFalse(P2pIceCandidatePolicy.isAllowedRemoteCandidate(uplinkCandidate, remote))
    }

    @Test
    fun rejectsServerReflexiveRelayTcpAndNonRtpCandidates() {
        assertFalse(P2pIceCandidatePolicy.isAllowedLocalCandidate(
            "candidate:1 1 udp 2122260223 192.168.49.1 50000 typ srflx raddr 1.2.3.4 rport 9",
            local,
        ))
        assertFalse(P2pIceCandidatePolicy.isAllowedLocalCandidate(
            "candidate:1 1 udp 2122260223 192.168.49.1 50000 typ relay",
            local,
        ))
        assertFalse(P2pIceCandidatePolicy.isAllowedLocalCandidate(
            "candidate:1 1 tcp 2122260223 192.168.49.1 50000 typ host tcptype passive",
            local,
        ))
        assertFalse(P2pIceCandidatePolicy.isAllowedLocalCandidate(
            "candidate:1 2 udp 2122260223 192.168.49.1 50000 typ host",
            local,
        ))
    }

    @Test
    fun rejectsMdnsIpv6MalformedAndOutOfRangeCandidates() {
        listOf(
            "candidate:1 1 udp 2122260223 peyvand.local 50000 typ host",
            "candidate:1 1 udp 2122260223 2001:db8::1 50000 typ host",
            "candidate:1 1 udp 2122260223 192.168.49.999 50000 typ host",
            "candidate:1 1 udp 2122260223 192.168.49.1 70000 typ host",
            "candidate:1 1 udp 2122260223 192.168.049.1 50000 typ host",
            "candidate:1 1 udp 2122260223 192.168.49.1 0 typ host",
            "candidate:1 1 udp 2122260223 192.168.49.1 50000 typ",
        ).forEach { candidate ->
            assertFalse(P2pIceCandidatePolicy.isAllowedLocalCandidate(candidate, local))
        }
    }

    @Test
    fun selectedStatsCandidateMustMatchExpectedHostUdpP2pEndpoint() {
        assertTrue(P2pIceCandidatePolicy.isAllowedSelectedCandidate("host", "udp", "192.168.49.1", local))
        assertFalse(P2pIceCandidatePolicy.isAllowedSelectedCandidate("srflx", "udp", "192.168.49.1", local))
        assertFalse(P2pIceCandidatePolicy.isAllowedSelectedCandidate("host", "tcp", "192.168.49.1", local))
        assertFalse(P2pIceCandidatePolicy.isAllowedSelectedCandidate("host", "udp", "192.168.1.20", local))
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address
}
