package com.peyvand.core.data.chat

import androidx.room.withTransaction
import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.chat.TrustedPeerIdentity
import com.peyvand.core.domain.chat.TrustedPeerIdentityConflictException
import com.peyvand.core.domain.chat.TrustedPeerRepository
import com.peyvand.core.domain.chat.TrustedPeerProfileConflictException
import com.peyvand.core.domain.profile.DeviceProfileRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class RoomTrustedPeerRepository(
    private val database: ChatDatabase,
) : TrustedPeerRepository {
    override fun observeTrustedPeers(): Flow<List<TrustedPeerIdentity>> =
        database.trustedPeers().observeAll().map { rows ->
            rows.map { it.toDomain() }
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
        require(DeviceProfileRules.normalizeDeviceName(peer.displayName) == peer.displayName)
        require(peer.profileRevision >= 0L)
        require(DeviceProfileRules.normalizeProfileName(peer.profileName) == peer.profileName)
        DeviceProfileRules.validateAvatar(peer.profileAvatarBytes)
        require((peer.profileAvatarBytes == null) == (peer.profileAvatarHash == null))
        peer.profileAvatarBytes?.let { bytes ->
            require(DeviceProfileRules.sha256Hex(bytes) == peer.profileAvatarHash)
        }
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
                        profileName = peer.profileName,
                        profileAvatarHash = peer.profileAvatarHash,
                        profileAvatarBytes = peer.profileAvatarBytes?.copyOf(),
                        profileRevision = peer.profileRevision,
                    ),
                )
            } else {
                val incomingIsStale = peer.profileRevision < existing.profileRevision
                if (!incomingIsStale && peer.profileRevision == existing.profileRevision) {
                    val sameProfile = existing.displayName == peer.displayName &&
                        existing.profileName == peer.profileName &&
                        existing.profileAvatarHash == peer.profileAvatarHash &&
                        nullableBytesEqual(existing.profileAvatarBytes, peer.profileAvatarBytes)
                    if (!sameProfile) throw TrustedPeerProfileConflictException()
                }
                val keepExistingProfile = incomingIsStale
                dao.updateAuthenticatedPeer(
                    identityFingerprint = peer.identityId.sha256Fingerprint,
                    deviceName = if (incomingIsStale) existing.displayName else peer.displayName,
                    lastAuthenticatedAtMillis = peer.lastAuthenticatedAtEpochMillis,
                    profileName = if (keepExistingProfile) existing.profileName else peer.profileName,
                    profileAvatarHash = if (keepExistingProfile) existing.profileAvatarHash else peer.profileAvatarHash,
                    profileAvatarBytes = if (keepExistingProfile) {
                        existing.profileAvatarBytes
                    } else {
                        peer.profileAvatarBytes?.copyOf()
                    },
                    profileRevision = if (keepExistingProfile) existing.profileRevision else peer.profileRevision,
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
        profileName = profileName,
        profileAvatarHash = profileAvatarHash,
        profileAvatarBytes = profileAvatarBytes?.copyOf(),
        profileRevision = profileRevision,
    )

    private fun nullableBytesEqual(first: ByteArray?, second: ByteArray?): Boolean =
        when {
            first == null -> second == null
            second == null -> false
            else -> first.contentEquals(second)
        }
}
