package com.peyvand.core.data.chat

import android.graphics.BitmapFactory
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
            validateCanonicalRemoteAvatar(bytes)
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

    /**
     * Validate the encoded image dimensions and MIME type before decoding or persisting
     * untrusted peer bytes. This prevents a small compressed payload from expanding into
     * an unexpectedly large bitmap and prevents non-canonical avatar payloads in Room.
     */
    private fun validateCanonicalRemoteAvatar(bytes: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(
            bounds.outWidth == 256 &&
                bounds.outHeight == 256 &&
                bounds.outMimeType == "image/jpeg",
        ) {
            "A trusted peer avatar must be a canonical 256x256 JPEG."
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("The trusted peer avatar JPEG is invalid.")
        decoded.recycle()
    }

    private fun nullableBytesEqual(first: ByteArray?, second: ByteArray?): Boolean =
        when {
            first == null -> second == null
            second == null -> false
            else -> first.contentEquals(second)
        }
}
