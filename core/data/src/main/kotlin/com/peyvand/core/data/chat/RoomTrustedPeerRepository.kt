package com.peyvand.core.data.chat

import androidx.room.withTransaction
import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.chat.TrustedPeerIdentity
import com.peyvand.core.domain.chat.TrustedPeerIdentityConflictException
import com.peyvand.core.domain.chat.TrustedPeerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class RoomTrustedPeerRepository(
    private val database: ChatDatabase,
) : TrustedPeerRepository {
    override fun observeTrustedPeers(): Flow<List<TrustedPeerIdentity>> =
        database.trustedPeers().observeAll().map { rows ->
            rows.map(ChatTrustedPeerEntity::toDomain)
        }

    override suspend fun findTrustedPeer(
        identityId: PeerIdentityId,
    ): TrustedPeerIdentity? = withContext(Dispatchers.IO) {
        database.trustedPeers().findByFingerprint(identityId.sha256Fingerprint)
            ?.toDomain()
    }

    override suspend fun recordAuthenticatedPeer(
        peer: TrustedPeerIdentity,
    ): TrustedPeerIdentity = withContext(Dispatchers.IO) {
        require(peer.displayName.isNotBlank() && peer.displayName.length <= MAX_DISPLAY_NAME_CHARS)
        database.withTransaction {
            val dao = database.trustedPeers()
            val existing = dao.findByFingerprint(peer.identityId.sha256Fingerprint)
            if (existing != null && !existing.publicKeyEncoded.contentEquals(peer.publicKeyEncoded)) {
                throw TrustedPeerIdentityConflictException()
            }
            if (existing == null) {
                dao.insert(
                    ChatTrustedPeerEntity(
                        identityFingerprint = peer.identityId.sha256Fingerprint,
                        publicKeyEncoded = peer.publicKeyEncoded.copyOf(),
                        displayName = peer.displayName,
                        firstSeenAtMillis = peer.firstSeenAtEpochMillis,
                        lastAuthenticatedAtMillis = peer.lastAuthenticatedAtEpochMillis,
                    ),
                )
            } else {
                dao.updateLastAuthenticated(
                    identityFingerprint = peer.identityId.sha256Fingerprint,
                    lastAuthenticatedAtMillis = peer.lastAuthenticatedAtEpochMillis,
                )
            }
            dao.findByFingerprint(peer.identityId.sha256Fingerprint)?.toDomain()
                ?: error("The trusted peer row was not available after its transaction.")
        }
    }

    private fun ChatTrustedPeerEntity.toDomain() = TrustedPeerIdentity(
        identityId = PeerIdentityId.fromSha256Fingerprint(identityFingerprint),
        publicKeyEncoded = publicKeyEncoded.copyOf(),
        displayName = displayName,
        firstSeenAtEpochMillis = firstSeenAtMillis,
        lastAuthenticatedAtEpochMillis = lastAuthenticatedAtMillis,
    )

    private companion object {
        const val MAX_DISPLAY_NAME_CHARS = 64
    }
}
