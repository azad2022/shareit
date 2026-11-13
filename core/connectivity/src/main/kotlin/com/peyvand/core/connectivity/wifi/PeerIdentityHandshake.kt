package com.peyvand.core.connectivity.wifi

import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.chat.PeerIdentityKeyProvider
import com.peyvand.core.domain.chat.TrustedPeerIdentity
import com.peyvand.core.domain.chat.TrustedPeerIdentityConflictException
import com.peyvand.core.domain.chat.TrustedPeerRepository
import com.peyvand.core.transfer.protocol.TransferFrame
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import com.peyvand.core.transfer.protocol.TransferFrameType
import com.peyvand.core.transfer.security.PairingParticipantRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
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
 * Binds a long-lived Keystore identity to the already SAS-approved ephemeral mTLS session.
 * It is deliberately run only after the local and remote users have approved the SAS.
 */
internal class PeerIdentityHandshake(
    private val keyProvider: PeerIdentityKeyProvider,
    private val trustedPeers: TrustedPeerRepository,
    private val localDisplayName: String,
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
        val transcript = identityTranscript(
            sessionId = sessionId,
            groupOwnerFingerprint = groupOwnerCertificateFingerprint,
            groupClientFingerprint = groupClientCertificateFingerprint,
            role = localRole,
            publicKey = local.publicKeyEncoded,
        )
        val signature = keyProvider.signIdentityProof(transcript)
        val localHello = encodeHello(
            role = localRole,
            publicKey = local.publicKeyEncoded,
            displayName = sanitizeDisplayName(localDisplayName),
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
                displayName = remote.displayName,
                firstSeenAtEpochMillis = existing?.firstSeenAtEpochMillis ?: now,
                lastAuthenticatedAtEpochMillis = now,
            ),
        )
        return AuthenticatedPeerIdentity(
            identityId = trusted.identityId,
            displayName = trusted.displayName,
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
        if (publicKey.params.curve.field.fieldSize != 256 ||
            publicKey.params.order.bitLength() != 256
        ) {
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
    ): ByteArray {
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
        }
        return bytes.toByteArray()
    }

    private fun sanitizeDisplayName(value: String): String {
        val candidate = value.trim().take(32)
        val encoded = candidate.toByteArray(StandardCharsets.UTF_8)
        return if (
            candidate.isNotBlank() &&
            encoded.size <= MAX_NAME_BYTES &&
            candidate.none { Character.isISOControl(it) }
        ) {
            candidate
        } else {
            DEFAULT_DEVICE_NAME
        }
    }

    private fun encodeHello(
        role: PairingParticipantRole,
        publicKey: ByteArray,
        displayName: String,
        signature: ByteArray,
    ): ByteArray {
        require(publicKey.size in MIN_KEY_BYTES..MAX_KEY_BYTES)
        require(signature.size in 1..MAX_SIGNATURE_BYTES)
        val name = displayName.toByteArray(StandardCharsets.UTF_8)
        require(name.isNotEmpty() && name.size <= MAX_NAME_BYTES)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(IDENTITY_VERSION)
            output.writeByte(role.ordinal)
            output.writeShort(publicKey.size)
            output.write(publicKey)
            output.writeShort(name.size)
            output.write(name)
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
                    throw PeerIdentityHandshakeException("Unsupported peer identity version.")
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
                if (nameLength !in 1..MAX_NAME_BYTES) {
                    throw PeerIdentityHandshakeException("Invalid peer display-name length.")
                }
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(nameBytes))
                    .toString()
                if (name.isBlank() || name.any { Character.isISOControl(it) }) {
                    throw PeerIdentityHandshakeException("Invalid peer display name.")
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
                return PeerIdentityHello(role, publicKey, name, signature)
            }
        } catch (exception: PeerIdentityHandshakeException) {
            throw exception
        } catch (exception: Exception) {
            throw PeerIdentityHandshakeException("Malformed peer identity proof.", exception)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private data class PeerIdentityHello(
        val role: PairingParticipantRole,
        val publicKey: ByteArray,
        val displayName: String,
        val signature: ByteArray,
    )

    companion object {
        const val DEFAULT_DEVICE_NAME = "Android device"
        const val MAX_IDENTITY_HELLO_BYTES = 2 * 1024
        private const val MIN_IDENTITY_HELLO_BYTES = 80
        private const val MIN_KEY_BYTES = 64
        private const val MAX_KEY_BYTES = 512
        private const val MAX_NAME_BYTES = 128
        private const val MAX_SIGNATURE_BYTES = 128
        private const val SHA256_BYTES = 32
        private const val IDENTITY_VERSION = 1
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private val TRANSCRIPT_DOMAIN =
            "PEYVAND-CHAT-IDENTITY/1".toByteArray(StandardCharsets.US_ASCII)
    }
}

internal data class AuthenticatedPeerIdentity(
    val identityId: PeerIdentityId,
    val displayName: String,
    val isNewlyTrusted: Boolean,
)

internal class PeerIdentityHandshakeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
