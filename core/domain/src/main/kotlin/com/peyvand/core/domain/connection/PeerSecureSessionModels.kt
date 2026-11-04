package com.peyvand.core.domain.connection

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
}

data class SecureSessionSnapshot(
    val status: SecureSessionStatus = SecureSessionStatus.IDLE,
    /** Short authentication string is present only while local approval is pending. */
    val sasCode: String? = null,
    val failure: SecureSessionFailure? = null,
)

interface PeerSecureSessionRepository {
    val secureSession: StateFlow<SecureSessionSnapshot>

    /** Approve only after comparing the displayed SAS with the other physical device. */
    fun approvePairing()

    /** Rejecting the SAS closes the current unauthenticated socket. */
    fun rejectPairing()
}
