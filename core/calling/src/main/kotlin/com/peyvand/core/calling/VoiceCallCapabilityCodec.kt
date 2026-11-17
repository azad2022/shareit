package com.peyvand.core.calling

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

enum class VoiceCallCapabilityMessageType(val wireCode: Int) {
    REQUEST(1),
    RESPONSE(2);

    companion object {
        fun fromWireCode(code: Int): VoiceCallCapabilityMessageType =
            entries.firstOrNull { it.wireCode == code }
                ?: throw IllegalArgumentException("Unknown voice capability message type.")
    }
}

data class VoiceCallCapabilityPacket(
    val type: VoiceCallCapabilityMessageType,
    val sessionId: UUID,
    val nonce: ByteArray,
    val callProtocolVersion: Int,
    val initiatorFingerprint: ByteArray,
    val responderFingerprint: ByteArray,
    val signature: ByteArray,
) {
    init {
        require(nonce.size == VoiceCallCapabilityCodec.NONCE_BYTES)
        require(callProtocolVersion in 1..255)
        require(initiatorFingerprint.size == VoiceCallCapabilityCodec.FINGERPRINT_BYTES)
        require(responderFingerprint.size == VoiceCallCapabilityCodec.FINGERPRINT_BYTES)
        require(signature.size in VoiceCallCapabilityCodec.MIN_SIGNATURE_BYTES..VoiceCallCapabilityCodec.MAX_SIGNATURE_BYTES)
    }
}

/**
 * Signed, bounded UDP capability exchange. It avoids the TLS multiplexer so an older
 * peer can ignore the datagram without disrupting existing chat or file transfer.
 */
object VoiceCallCapabilityCodec {
    const val PACKET_PROTOCOL_VERSION = 1
    const val CALL_PROTOCOL_VERSION = 1
    const val NONCE_BYTES = 32
    const val FINGERPRINT_BYTES = 32
    const val MIN_SIGNATURE_BYTES = 8
    const val MAX_SIGNATURE_BYTES = 128
    const val MAX_PACKET_BYTES = 256

    private const val MAGIC = 0x50594343 // PYCC
    private const val HEADER_BYTES =
        4 + 1 + 1 + 1 + 1 + 16 + NONCE_BYTES + FINGERPRINT_BYTES + FINGERPRINT_BYTES + 2
    private val REQUEST_DOMAIN =
        "PEYVAND-CALL-CAPABILITY-REQUEST/1".toByteArray(StandardCharsets.US_ASCII)
    private val RESPONSE_DOMAIN =
        "PEYVAND-CALL-CAPABILITY-RESPONSE/1".toByteArray(StandardCharsets.US_ASCII)

    fun fingerprintBytes(fingerprintHex: String): ByteArray {
        require(fingerprintHex.length == FINGERPRINT_BYTES * 2)
        require(fingerprintHex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' })
        return ByteArray(FINGERPRINT_BYTES) { index ->
            val start = index * 2
            fingerprintHex.substring(start, start + 2).toInt(16).toByte()
        }
    }

    fun signingPayload(
        type: VoiceCallCapabilityMessageType,
        sessionId: UUID,
        nonce: ByteArray,
        callProtocolVersion: Int,
        initiatorFingerprint: ByteArray,
        responderFingerprint: ByteArray,
    ): ByteArray {
        require(nonce.size == NONCE_BYTES)
        require(callProtocolVersion in 1..255)
        require(initiatorFingerprint.size == FINGERPRINT_BYTES)
        require(responderFingerprint.size == FINGERPRINT_BYTES)
        val domain = when (type) {
            VoiceCallCapabilityMessageType.REQUEST -> REQUEST_DOMAIN
            VoiceCallCapabilityMessageType.RESPONSE -> RESPONSE_DOMAIN
        }
        return ByteBuffer.allocate(
            domain.size + 1 + 1 + 16 + NONCE_BYTES + 1 +
                FINGERPRINT_BYTES + FINGERPRINT_BYTES,
        ).order(ByteOrder.BIG_ENDIAN)
            .put(domain)
            .put(PACKET_PROTOCOL_VERSION.toByte())
            .put(type.wireCode.toByte())
            .putLong(sessionId.mostSignificantBits)
            .putLong(sessionId.leastSignificantBits)
            .put(nonce)
            .put(callProtocolVersion.toByte())
            .put(initiatorFingerprint)
            .put(responderFingerprint)
            .array()
    }

    fun encode(packet: VoiceCallCapabilityPacket): ByteArray {
        val bytes = ByteBuffer.allocate(HEADER_BYTES + packet.signature.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(PACKET_PROTOCOL_VERSION.toByte())
            .put(packet.type.wireCode.toByte())
            .put(packet.callProtocolVersion.toByte())
            .put(0) // Reserved flags. Non-zero values are invalid in protocol v1.
            .putLong(packet.sessionId.mostSignificantBits)
            .putLong(packet.sessionId.leastSignificantBits)
            .put(packet.nonce)
            .put(packet.initiatorFingerprint)
            .put(packet.responderFingerprint)
            .putShort(packet.signature.size.toShort())
            .put(packet.signature)
            .array()
        require(bytes.size <= MAX_PACKET_BYTES)
        return bytes
    }

    fun decode(bytes: ByteArray): VoiceCallCapabilityPacket {
        if (bytes.size !in (HEADER_BYTES + MIN_SIGNATURE_BYTES)..MAX_PACKET_BYTES) {
            throw IllegalArgumentException("Voice capability packet length is invalid.")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC) throw IllegalArgumentException("Voice capability packet magic is invalid.")
        val version = buffer.get().toInt() and 0xff
        if (version != PACKET_PROTOCOL_VERSION) {
            throw IllegalArgumentException("Unsupported voice capability packet version.")
        }
        val type = VoiceCallCapabilityMessageType.fromWireCode(buffer.get().toInt() and 0xff)
        val callProtocolVersion = buffer.get().toInt() and 0xff
        if (buffer.get().toInt() != 0) throw IllegalArgumentException("Unknown voice capability flags.")
        val sessionId = UUID(buffer.long, buffer.long)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val initiator = ByteArray(FINGERPRINT_BYTES).also(buffer::get)
        val responder = ByteArray(FINGERPRINT_BYTES).also(buffer::get)
        val signatureLength = buffer.short.toInt() and 0xffff
        if (signatureLength !in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES ||
            signatureLength != buffer.remaining()
        ) {
            throw IllegalArgumentException("Voice capability signature length is invalid.")
        }
        val signature = ByteArray(signatureLength).also(buffer::get)
        if (buffer.hasRemaining()) throw IllegalArgumentException("Trailing bytes in voice capability packet.")
        return VoiceCallCapabilityPacket(
            type = type,
            sessionId = sessionId,
            nonce = nonce,
            callProtocolVersion = callProtocolVersion,
            initiatorFingerprint = initiator,
            responderFingerprint = responder,
            signature = signature,
        )
    }

    fun verifySignature(packet: VoiceCallCapabilityPacket, encodedPublicKey: ByteArray): Boolean {
        return try {
            val publicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(encodedPublicKey)) as? ECPublicKey
                ?: return false
            if (publicKey.params.curve.field.fieldSize != 256) return false
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(
                signingPayload(
                    packet.type,
                    packet.sessionId,
                    packet.nonce,
                    packet.callProtocolVersion,
                    packet.initiatorFingerprint,
                    packet.responderFingerprint,
                ),
            )
            verifier.verify(packet.signature)
        } catch (_: Exception) {
            false
        }
    }
}
