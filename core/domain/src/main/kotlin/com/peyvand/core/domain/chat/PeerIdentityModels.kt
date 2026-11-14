package com.peyvand.core.domain.chat

import kotlinx.coroutines.flow.Flow

/** Long-lived public-key identity. Network addresses and session certificates are never identities. */
data class LocalPeerIdentity(
    val publicKeyEncoded: ByteArray,
    val fingerprintHex: String,
)

class LocalPeerIdentityUnavailableException :
    IllegalStateException(
        "The persistent local identity key is missing while trusted peers exist; automatic identity rotation is blocked.",
    )

interface PeerIdentityKeyProvider {
    /** Creates the device key once, then returns the same public identity on subsequent launches. */
    suspend fun getOrCreateIdentity(): LocalPeerIdentity

    /** Signs a bounded, domain-separated session proof with the non-exportable Keystore key. */
    suspend fun signIdentityProof(payload: ByteArray): ByteArray
}

data class TrustedPeerIdentity(
    val identityId: PeerIdentityId,
    val publicKeyEncoded: ByteArray,
    val displayName: String,
    val firstSeenAtEpochMillis: Long,
    val lastAuthenticatedAtEpochMillis: Long,
)

class TrustedPeerIdentityConflictException :
    IllegalStateException("The trusted peer fingerprint is already bound to a different public key.")

interface TrustedPeerRepository {
    fun observeTrustedPeers(): Flow<List<TrustedPeerIdentity>>

    suspend fun findTrustedPeer(identityId: PeerIdentityId): TrustedPeerIdentity?

    /**
     * Trust may be recorded only after the current session's bilateral SAS approval and
     * cryptographic identity proof both succeed. Existing keys are never silently replaced.
     */
    suspend fun recordAuthenticatedPeer(peer: TrustedPeerIdentity): TrustedPeerIdentity
}
