package com.peyvand.core.domain.connection

import java.net.Inet4Address
import kotlinx.coroutines.flow.StateFlow

enum class P2pTcpChannelStatus {
    IDLE,
    RESOLVING_INTERFACE,
    LISTENING,
    CONNECTING,
    CONNECTED,
    FAILED,
}

enum class P2pTcpChannelFailure {
    GROUP_INFO_UNAVAILABLE,
    GROUP_ROLE_MISMATCH,
    INTERFACE_UNAVAILABLE,
    INTERFACE_DOWN,
    LOCAL_IPV4_UNAVAILABLE,
    OWNER_ADDRESS_UNAVAILABLE,
    OWNER_OUTSIDE_P2P_SUBNET,
    LISTENER_BIND_FAILED,
    ACCEPT_TIMEOUT,
    CONNECT_TIMEOUT,
    CONNECTION_REJECTED,
    PERMISSION_DENIED,
    IO_ERROR,
}

/**
 * UI-safe state for the raw local TCP channel.
 *
 * Network addresses and interface names deliberately stay inside the platform adapter;
 * they are neither persisted nor copied into UI state, diagnostics, or logs.
 */
data class P2pTcpChannelSnapshot(
    val status: P2pTcpChannelStatus = P2pTcpChannelStatus.IDLE,
    val isGroupOwner: Boolean? = null,
    val failure: P2pTcpChannelFailure? = null,
)

interface P2pTcpChannelStateRepository {
    val tcpChannel: StateFlow<P2pTcpChannelSnapshot>
}

/** Combined application-facing repository for the P2P group and its local TCP channel. */
interface PeerConnectivityRepository :
    PeerConnectionRepository,
    P2pTcpChannelStateRepository,
    PeerSecureSessionRepository

data class P2pIpv4InterfaceAddress(
    val address: Inet4Address,
    val prefixLength: Int,
)

/**
 * Conservative IPv4 policy for the Wi-Fi Direct interface only.
 *
 * The transport currently supports the IPv4 addressing used by Android Wi-Fi Direct.
 * It intentionally rejects loopback, link-local, multicast, wildcard, public, and
 * non-contiguous host-only network prefixes rather than guessing another interface.
 */
object P2pTcpEndpointPolicy {
    fun selectLocalAddress(
        candidates: Collection<P2pIpv4InterfaceAddress>,
    ): P2pIpv4InterfaceAddress? = candidates.firstOrNull(::isUsableLocalAddress)

    fun isUsableLocalAddress(candidate: P2pIpv4InterfaceAddress): Boolean {
        val address = candidate.address
        return candidate.prefixLength in MIN_PREFIX_LENGTH..MAX_PREFIX_LENGTH &&
            address.isSiteLocalAddress &&
            !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isMulticastAddress
    }

    fun isOnSameSubnet(
        local: P2pIpv4InterfaceAddress,
        remote: Inet4Address,
    ): Boolean {
        if (!isUsableLocalAddress(local)) return false
        if (!remote.isSiteLocalAddress ||
            remote.isAnyLocalAddress ||
            remote.isLoopbackAddress ||
            remote.isLinkLocalAddress ||
            remote.isMulticastAddress ||
            remote.address.contentEquals(local.address.address)
        ) {
            return false
        }
        val mask = -1 shl (32 - local.prefixLength)
        return (toInt(local.address) and mask) == (toInt(remote) and mask)
    }

    private fun toInt(address: Inet4Address): Int {
        var value = 0
        address.address.forEach { byte ->
            value = (value shl 8) or (byte.toInt() and 0xff)
        }
        return value
    }

    private const val MIN_PREFIX_LENGTH = 1
    private const val MAX_PREFIX_LENGTH = 30
}
