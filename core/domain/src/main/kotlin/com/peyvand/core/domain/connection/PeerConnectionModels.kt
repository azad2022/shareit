package com.peyvand.core.domain.connection

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-neutral state for a Wi-Fi Direct group connection.
 *
 * A formed P2P group is transport readiness only. It is not peer authentication,
 * user consent, or a secure transfer session.
 */
enum class PeerConnectionStatus {
    IDLE,
    CONNECTING,
    GROUP_FORMED,
    DISCONNECTING,
    FAILED,
}

enum class PeerConnectionFailure {
    INVALID_DISCOVERY_CANDIDATE,
    PERMISSION_DENIED,
    BUSY,
    CONNECTION_TIMEOUT,
    UNSUPPORTED,
    PLATFORM_ERROR,
}

data class PeerConnectionSnapshot(
    val status: PeerConnectionStatus = PeerConnectionStatus.IDLE,
    /** Transient discovery address; never persist or display as a device identity. */
    val selectedPeerId: String? = null,
    /** True only after Android reports group formation through requestConnectionInfo. */
    val isGroupOwner: Boolean? = null,
    /** Transient group-owner endpoint. Available only while the group is formed. */
    val groupOwnerAddress: String? = null,
    /** False for an incoming OS-level connection initiated by the other device. */
    val locallyInitiated: Boolean = false,
    val failure: PeerConnectionFailure? = null,
)

interface PeerConnectionRepository {
    val connection: StateFlow<PeerConnectionSnapshot>

    /** Connect only to a candidate currently present in the active discovery snapshot. */
    fun connectToPeer(peerId: String)

    /** Remove the current P2P group and clear all session-scoped connection state. */
    fun disconnect()
}

/** Pure validation shared by the platform adapter and its tests. */
object PeerConnectionStatePolicy {
    fun isGroupFormed(
        groupFormed: Boolean,
        groupOwnerAddress: String?,
    ): Boolean = groupFormed && !groupOwnerAddress.isNullOrBlank()

    fun mayInitiate(
        peerId: String,
        currentlyDiscoveredPeerIds: Collection<String>,
        isDiscoveryActive: Boolean,
    ): Boolean =
        isDiscoveryActive &&
            peerId.isNotBlank() &&
            currentlyDiscoveredPeerIds.any { it == peerId }
}
