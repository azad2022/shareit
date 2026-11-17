package com.peyvand.core.calling

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Strict admission policy for the only media network allowed by PEYVAND voice calls.
 * Accepts numeric IPv4 UDP host candidates only, bound to the authenticated P2P endpoints.
 */
object P2pIceCandidatePolicy {
    data class CandidateAddress(
        val address: Inet4Address,
        val port: Int,
        val protocol: String,
        val candidateType: String,
        val component: Int,
    )

    fun parseCandidateLine(sdpCandidate: String): CandidateAddress? {
        val fields = sdpCandidate.trim().split(WHITESPACE)
        if (fields.size < MIN_CANDIDATE_FIELDS) return null
        if (!fields[0].startsWith("candidate:")) return null
        if (fields[1] != RTP_COMPONENT) return null
        if (!fields[2].equals(UDP, ignoreCase = true)) return null
        if (!fields[6].equals("typ", ignoreCase = true)) return null
        if (!fields[7].equals(HOST, ignoreCase = true)) return null

        val priority = fields[3].toLongOrNull() ?: return null
        if (priority !in 1L..MAX_UNSIGNED_PRIORITY) return null
        val address = parseNumericIpv4(fields[4]) ?: return null
        val port = fields[5].toIntOrNull() ?: return null
        if (port !in MIN_PORT..MAX_PORT) return null

        return CandidateAddress(
            address = address,
            port = port,
            protocol = UDP,
            candidateType = HOST,
            component = RTP_COMPONENT.toInt(),
        )
    }

    fun isAllowedLocalCandidate(
        sdpCandidate: String,
        expectedP2pLocalAddress: Inet4Address,
    ): Boolean = parseCandidateLine(sdpCandidate)?.address?.address
        ?.contentEquals(expectedP2pLocalAddress.address) == true

    fun isAllowedRemoteCandidate(
        sdpCandidate: String,
        authenticatedP2pPeerAddress: Inet4Address,
    ): Boolean = parseCandidateLine(sdpCandidate)?.address?.address
        ?.contentEquals(authenticatedP2pPeerAddress.address) == true

    fun isAllowedSelectedCandidate(
        candidateType: String?,
        protocol: String?,
        address: String?,
        expectedP2pAddress: Inet4Address,
    ): Boolean {
        if (!candidateType.equals(HOST, ignoreCase = true)) return false
        if (!protocol.equals(UDP, ignoreCase = true)) return false
        val parsed = address?.let(::parseNumericIpv4) ?: return false
        return parsed.address.contentEquals(expectedP2pAddress.address)
    }

    private fun parseNumericIpv4(value: String): Inet4Address? {
        val octets = value.split('.')
        if (octets.size != IPV4_OCTETS) return null
        val bytes = ByteArray(IPV4_OCTETS)
        for (index in octets.indices) {
            val octet = octets[index]
            if (octet.isEmpty() || octet.any { !it.isDigit() }) return null
            if (octet.length > 1 && octet[0] == '0') return null
            val numeric = octet.toIntOrNull() ?: return null
            if (numeric !in 0..255) return null
            bytes[index] = numeric.toByte()
        }
        return runCatching { InetAddress.getByAddress(bytes) as? Inet4Address }.getOrNull()
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_CANDIDATE_FIELDS = 8
    private const val IPV4_OCTETS = 4
    private const val RTP_COMPONENT = "1"
    private const val UDP = "udp"
    private const val HOST = "host"
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private const val MAX_UNSIGNED_PRIORITY = 4_294_967_295L
}
