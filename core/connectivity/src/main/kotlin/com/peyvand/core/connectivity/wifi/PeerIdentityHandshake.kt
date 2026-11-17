package com.peyvand.core.connectivity.wifi

import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.chat.PeerIdentityKeyProvider
import com.peyvand.core.domain.chat.TrustedPeerIdentity
import com.peyvand.core.domain.chat.TrustedPeerIdentityConflictException
import com.peyvand.core.domain.chat.TrustedPeerRepository
import com.peyvand.core.domain.profile.DeviceProfileRepository
import com.peyvand.core.domain.profile.DeviceProfileRules
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.security.PairingParticipantRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.net.ssl.SSLSocket

/**
 * Binds a long-lived Keystore identity and its optional presentation profile to the
 * already SAS-approved ephemeral mTLS session. This exchange runs only after bilateral
 * SAS approval and pinned mutual TLS; discovery metadata is never promoted to trust.
 */
internal class PeerIdentityHandshake(
    private val keyProvider: PeerIdentityKeyProvider,
    private val trustedPeers: TrustedPeerRepository,
    private val deviceProfileRepository: DeviceProfileRepository,
    private val fallbackDeviceName: String,
) {
    suspend fun exchange(
        socket: SSLSocket,
        sessionId: UUID,
        localRole: PairingParticipantRole,
        groupOwnerCertificateFingerprint: ByteArray,
        groupClientCertificateFingerprint: ByteArray,
    ): AuthenticatedPeerIdentity {
        require(groupOwnerCertificateFingerprint.size == SHA256_BYTES)
        require(groupClientCertificateFingerprint.size == SHA256_BYTES)

        val local = keyProvider.getOrCreateIdentity()
        val localProfile = deviceProfileRepository.getLocalProfile()
        val localDeviceName = DeviceProfileRules.normalizeDeviceName(
            localProfile.deviceNameOverride,
        ) ?: DeviceProfileRules.normalizeDeviceName(fallbackDeviceName)
            ?: DEFAULT_DEVICE_NAME
        val sharedProfileName = if (localProfile.shareWithTrustedPeers) {
            DeviceProfileRules.normalizeProfileName(localProfile.profileName)
        } else {
            null
        }
        val sharedAvatarBytes = if (localProfile.shareWithTrustedPeers) {
            localProfile.avatarBytes?.copyOf()
        } else {
            null
        }
        DeviceProfileRules.validateAvatar(sharedAvatarBytes)
        val avatarHash = sharedAvatarBytes?.let {
            MessageDigest.getInstance("SHA-256").digest(it)
        }
        require(localProfile.revision >= 0L)

        val transcript = identityTranscript(
            sessionId = sessionId,
            groupOwnerFingerprint = groupOwnerCertificateFingerprint,
            groupClientFingerprint = groupClientCertificateFingerprint,
            role = localRole,
            publicKey = local.publicKeyEncoded,
            deviceName = localDeviceName,
            profileRevision = localProfile.revision,
            profileName = sharedProfileName,
            avatarHash = avatarHash,
        )
        val signature = keyProvider.signIdentityProof(transcript)
        val localHello = encodeHello(
            role = localRole,
            publicKey = local.publicKeyEncoded,
            deviceName = localDeviceName,
            profileRevision = localProfile.revision,
            profileName = sharedProfileName,
            avatarBytes = sharedAvatarBytes,
            avatarHash = avatarHash,
            signature = signature,
        )

        TransferFrameCodec.write(
            TransferFrame(
                protocolVersion = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
                type = TransferFrameType.PEER_IDENTITY_HELLO,
                sessionId = sessionId,
                payload = localHello,
            ),
            socket.outputStream,
        )
        socket.outputStream.flush()

        val frame = TransferFrameCodec.read(socket.inputStream)
            ?: throw PeerIdentityHandshakeException("Peer closed before identity proof.")
        if (frame.protocolVersion != TransferFrameCodec.CURRENT_PROTOCOL_VERSION ||
            frame.type != TransferFrameType.PEER_IDENTITY_HELLO ||
            frame.sessionId != sessionId
        ) {
            throw PeerIdentityHandshakeException("Unexpected peer identity frame.")
        }

        val remote = decodeHello(frame.payloadBytes())
        if (remote.role == localRole) {
            throw PeerIdentityHandshakeException("Peer identity role does not match the session.")
        }

        val remoteTranscript = identityTranscript(
            sessionId = sessionId,
            groupOwnerFingerprint = groupOwnerCertificateFingerprint,
            groupClientFingerprint = groupClientCertificateFingerprint,
            role = remote.role,
            publicKey = remote.publicKey,
            deviceName = remote.deviceName,
            profileRevision = remote.profileRevision,
            profileName = remote.profileName,
            avatarHash = remote.avatarHash,
        )
        verifyProof(remote.publicKey, remoteTranscript, remote.signature)

        val fingerprint = sha256Hex(remote.publicKey)
        val identityId = PeerIdentityId.fromSha256Fingerprint(fingerprint)
        val now = System.currentTimeMillis()
        val existing = trustedPeers.findTrustedPeer(identityId)
        if (existing != null && !MessageDigest.isEqual(existing.publicKeyEncoded, remote.publicKey)) {
            throw TrustedPeerIdentityConflictException()
        }

        val trusted = trustedPeers.recordAuthenticatedPeer(
            TrustedPeerIdentity(
                identityId = identityId,
                publicKeyEncoded = remote.publicKey,
                displayName = remote.deviceName,
                firstSeenAtEpochMillis = existing?.firstSeenAtEpochMillis ?: now,
                lastAuthenticatedAtEpochMillis = now,
                profileName = remote.profileName,
                profileAvatarHash = remote.avatarHash?.let(::hex),
                profileAvatarBytes = remote.avatarBytes?.copyOf(),
                profileRevision = remote.profileRevision,
            ),
        )
        return AuthenticatedPeerIdentity(
            identityId = trusted.identityId,
            deviceName = trusted.displayName,
            profileName = trusted.profileName,
            profileAvatarBytes = trusted.profileAvatarBytes?.copyOf(),
            profileRevision = trusted.profileRevision,
            isNewlyTrusted = existing == null,
        )
    }

    private fun verifyProof(
        encodedPublicKey: ByteArray,
        transcript: ByteArray,
        proof: ByteArray,
    ) {
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(encodedPublicKey)) as? ECPublicKey
            ?: throw PeerIdentityHandshakeException("Peer identity key is not EC.")
        if (!PeerIdentityCurvePolicy.isP256(publicKey.params)) {
            throw PeerIdentityHandshakeException("Peer identity key is not P-256.")
        }
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(transcript)
        if (!verifier.verify(proof)) {
            throw PeerIdentityHandshakeException("Peer identity signature is invalid.")
        }
    }

    private fun identityTranscript(
        sessionId: UUID,
        groupOwnerFingerprint: ByteArray,
        groupClientFingerprint: ByteArray,
        role: PairingParticipantRole,
        publicKey: ByteArray,
        deviceName: String,
        profileRevision: Long,
        profileName: String?,
        avatarHash: ByteArray?,
    ): ByteArray {
        val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
        val profileNameBytes = profileName?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(TRANSCRIPT_DOMAIN)
            output.writeLong(sessionId.mostSignificantBits)
            output.writeLong(sessionId.leastSignificantBits)
            output.write(groupOwnerFingerprint)
            output.write(groupClientFingerprint)
            output.writeByte(role.ordinal)
            output.writeShort(publicKey.size)
            output.write(publicKey)
            output.writeShort(nameBytes.size)
            output.write(nameBytes)
            output.writeLong(profileRevision)
            output.writeShort(profileNameBytes.size)
            output.write(profileNameBytes)
            output.writeByte(avatarHash?.size ?: 0)
            if (avatarHash != null) output.write(avatarHash)
        }
        return bytes.toByteArray()
    }

    private fun encodeHello(
        role: PairingParticipantRole,
        publicKey: ByteArray,
        deviceName: String,
        profileRevision: Long,
        profileName: String?,
        avatarBytes: ByteArray?,
        avatarHash: ByteArray?,
        signature: ByteArray,
    ): ByteArray {
        require(publicKey.size in MIN_KEY_BYTES..MAX_KEY_BYTES)
        require(signature.size in 1..MAX_SIGNATURE_BYTES)
        require(profileRevision >= 0L)
        require((avatarBytes == null) == (avatarHash == null))
        avatarBytes?.let(DeviceProfileRules::validateAvatar)
        val name = deviceName.toByteArray(StandardCharsets.UTF_8)
        val profile = profileName?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
        require(name.isNotEmpty() && name.size <= DeviceProfileRules.MAX_NAME_UTF8_BYTES)
        require(profile.size <= DeviceProfileRules.MAX_NAME_UTF8_BYTES)
        require((avatarBytes?.size ?: 0) <= DeviceProfileRules.MAX_AVATAR_BYTES)

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(IDENTITY_VERSION)
            output.writeByte(role.ordinal)
            output.writeShort(publicKey.size)
            output.write(publicKey)
            output.writeShort(name.size)
            output.write(name)
            output.writeLong(profileRevision)
            output.writeShort(profile.size)
            output.write(profile)
            output.writeInt(avatarBytes?.size ?: 0)
            if (avatarBytes != null && avatarHash != null) {
                require(avatarHash.size == SHA256_BYTES)
                output.write(avatarHash)
                output.write(avatarBytes)
            }
            output.writeShort(signature.size)
            output.write(signature)
        }
        val payload = bytes.toByteArray()
        require(payload.size <= MAX_IDENTITY_HELLO_BYTES)
        return payload
    }

    private fun decodeHello(payload: ByteArray): PeerIdentityHello {
        if (payload.size !in MIN_IDENTITY_HELLO_BYTES..MAX_IDENTITY_HELLO_BYTES) {
            throw PeerIdentityHandshakeException("Invalid peer identity payload size.")
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readUnsignedByte() != IDENTITY_VERSION) {
                    throw PeerIdentityHandshakeException("Unsupported peer identity profile version.")
                }
                val roleIndex = input.readUnsignedByte()
                val role = PairingParticipantRole.entries.getOrNull(roleIndex)
                    ?: throw PeerIdentityHandshakeException("Invalid peer identity role.")
                val keyLength = input.readUnsignedShort()
                if (keyLength !in MIN_KEY_BYTES..MAX_KEY_BYTES) {
                    throw PeerIdentityHandshakeException("Invalid peer public-key length.")
                }
                val publicKey = ByteArray(keyLength)
                input.readFully(publicKey)

                val nameLength = input.readUnsignedShort()
                if (nameLength !in 1..DeviceProfileRules.MAX_NAME_UTF8_BYTES) {
                    throw PeerIdentityHandshakeException("Invalid device-name length.")
                }
                val deviceName = readUtf8(input, nameLength, allowEmpty = false)
                if (DeviceProfileRules.normalizeDeviceName(deviceName) != deviceName) {
                    throw PeerIdentityHandshakeException("Invalid or non-canonical device name.")
                }

                val profileRevision = input.readLong()
                if (profileRevision < 0L) {
                    throw PeerIdentityHandshakeException("Invalid peer profile revision.")
                }
                val profileNameLength = input.readUnsignedShort()
                if (profileNameLength > DeviceProfileRules.MAX_NAME_UTF8_BYTES) {
                    throw PeerIdentityHandshakeException("Invalid peer profile-name length.")
                }
                val profileName = if (profileNameLength == 0) {
                    null
                } else {
                    readUtf8(input, profileNameLength, allowEmpty = false).also { value ->
                        if (DeviceProfileRules.normalizeProfileName(value) != value) {
                            throw PeerIdentityHandshakeException("Invalid or non-canonical profile name.")
                        }
                    }
                }

                val avatarLength = input.readInt()
                if (avatarLength !in 0..DeviceProfileRules.MAX_AVATAR_BYTES) {
                    throw PeerIdentityHandshakeException("Invalid peer profile-avatar length.")
                }
                val avatarHash = if (avatarLength == 0) {
                    null
                } else {
                    ByteArray(SHA256_BYTES).also(input::readFully)
                }
                val avatarBytes = if (avatarLength == 0) {
                    null
                } else {
                    ByteArray(avatarLength).also(input::readFully).also { avatar ->
                        DeviceProfileRules.validateAvatar(avatar)
                        val calculatedHash = MessageDigest.getInstance("SHA-256").digest(avatar)
                        val expectedHash = requireNotNull(avatarHash)
                        if (!MessageDigest.isEqual(calculatedHash, expectedHash)) {
                            throw PeerIdentityHandshakeException("Peer profile-avatar digest mismatch.")
                        }
                    }
                }

                val signatureLength = input.readUnsignedShort()
                if (signatureLength !in 1..MAX_SIGNATURE_BYTES) {
                    throw PeerIdentityHandshakeException("Invalid peer identity signature length.")
                }
                val signature = ByteArray(signatureLength)
                input.readFully(signature)
                if (input.available() != 0) {
                    throw PeerIdentityHandshakeException("Trailing bytes in peer identity proof.")
                }
                return PeerIdentityHello(
                    role = role,
                    publicKey = publicKey,
                    deviceName = deviceName,
                    profileRevision = profileRevision,
                    profileName = profileName,
                    avatarBytes = avatarBytes,
                    avatarHash = avatarHash,
                    signature = signature,
                )
            }
        } catch (exception: PeerIdentityHandshakeException) {
            throw exception
        } catch (exception: Exception) {
            throw PeerIdentityHandshakeException("Malformed peer identity proof.", exception)
        }
    }

    private fun readUtf8(input: DataInputStream, length: Int, allowEmpty: Boolean): String {
        if (length == 0 && !allowEmpty) {
            throw PeerIdentityHandshakeException("An empty display label is invalid.")
        }
        val encoded = ByteArray(length)
        input.readFully(encoded)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(encoded))
            .toString()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private data class PeerIdentityHello(
        val role: PairingParticipantRole,
        val publicKey: ByteArray,
        val deviceName: String,
        val profileRevision: Long,
        val profileName: String?,
        val avatarBytes: ByteArray?,
        val avatarHash: ByteArray?,
        val signature: ByteArray,
    )

    companion object {
        const val DEFAULT_DEVICE_NAME = "Android device"
        const val MAX_IDENTITY_HELLO_BYTES = 40 * 1024
        private const val MIN_IDENTITY_HELLO_BYTES = 80
        private const val MIN_KEY_BYTES = 64
        private const val MAX_KEY_BYTES = 512
        private const val MAX_SIGNATURE_BYTES = 128
        private const val SHA256_BYTES = 32
        private const val IDENTITY_VERSION = 2
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private val TRANSCRIPT_DOMAIN =
            "PEYVAND-CHAT-IDENTITY/2".toByteArray(StandardCharsets.US_ASCII)
    }
}

internal data class AuthenticatedPeerIdentity(
    val identityId: PeerIdentityId,
    val deviceName: String,
    val profileName: String?,
    val profileAvatarBytes: ByteArray?,
    val profileRevision: Long,
    val isNewlyTrusted: Boolean,
)

internal class PeerIdentityHandshakeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
