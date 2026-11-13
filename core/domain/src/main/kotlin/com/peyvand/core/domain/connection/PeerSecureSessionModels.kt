package com.peyvand.core.domain.connection

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

enum class PeerTransferRole {
    SENDER,
    RECEIVER,
}

enum class SecureSessionStatus {
    IDLE,
    BOOTSTRAPPING,
    WAITING_FOR_SAS_APPROVAL,
    TLS_HANDSHAKING,
    AUTHENTICATED,
    REJECTED,
    FAILED,
}

enum class SecureSessionFailure {
    MISSING_TRANSFER_ROLE,
    PARTICIPANT_ROLE_MISMATCH,
    INVALID_PEER_CERTIFICATE,
    BOOTSTRAP_TIMEOUT,
    USER_APPROVAL_TIMEOUT,
    TLS_UNAVAILABLE,
    TLS_HANDSHAKE_FAILED,
    PROTOCOL_ERROR,
    KEYSTORE_ERROR,
    IO_ERROR,
    PEER_IDENTITY_PROOF_INVALID,
    PEER_IDENTITY_MISMATCH,
}

data class SecureSessionSnapshot(
    val status: SecureSessionStatus = SecureSessionStatus.IDLE,
    /** Short authentication string is present only while local approval is pending. */
    val sasCode: String? = null,
    val failure: SecureSessionFailure? = null,
    /** Persistent public-key identity established inside the SAS-approved mTLS session. */
    val peerIdentityId: String? = null,
    val peerDisplayName: String? = null,
    val isNewlyTrustedPeer: Boolean = false,
)

enum class AuthenticatedPeerChannelPurpose {
    CHAT,
    FILE_TRANSFER,
}

/**
 * Session-bound access to one independently routed protocol stream over the authenticated TLS session.
 * The owner must close the channel and terminate the peer connection on cancellation.
 */
interface AuthenticatedPeerChannel : Closeable {
    val sessionId: UUID
    val inputStream: InputStream
    val outputStream: OutputStream
}

interface PeerSecureSessionRepository {
    val secureSession: StateFlow<SecureSessionSnapshot>

    /** Approve only after comparing the displayed SAS with the other physical device. */
    fun approvePairing()

    /** Rejecting the SAS closes the current unauthenticated socket. */
    fun rejectPairing()

    /**
     * Opens a single-consumer logical channel. The session multiplexer owns the TLS socket
     * and guarantees that CHAT and FILE_TRANSFER frames never share competing readers.
     */
    fun claimAuthenticatedChannel(
        purpose: AuthenticatedPeerChannelPurpose,
    ): AuthenticatedPeerChannel?
}
