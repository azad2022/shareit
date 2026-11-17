package com.peyvand.core.calling

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallCapabilityCodecTest {
    @Test
    fun signedRequestAndResponseRoundTripAndVerify() {
        val requester = keyPair()
        val responder = keyPair()
        val sessionId = UUID.randomUUID()
        val nonce = ByteArray(VoiceCallCapabilityCodec.NONCE_BYTES) { it.toByte() }
        val initiatorFingerprint = ByteArray(32) { (it + 1).toByte() }
        val responderFingerprint = ByteArray(32) { (it + 33).toByte() }

        listOf(
            VoiceCallCapabilityMessageType.REQUEST to requester,
            VoiceCallCapabilityMessageType.RESPONSE to responder,
        ).forEach { (type, signer) ->
            val payload = VoiceCallCapabilityCodec.signingPayload(
                type, sessionId, nonce, VoiceCallCapabilityCodec.CALL_PROTOCOL_VERSION,
                initiatorFingerprint, responderFingerprint,
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(signer.private)
                update(payload)
                sign()
            }
            val packet = VoiceCallCapabilityPacket(
                type, sessionId, nonce, VoiceCallCapabilityCodec.CALL_PROTOCOL_VERSION,
                initiatorFingerprint, responderFingerprint, signature,
            )
            val decoded = VoiceCallCapabilityCodec.decode(VoiceCallCapabilityCodec.encode(packet))
            assertEquals(type, decoded.type)
            assertEquals(sessionId, decoded.sessionId)
            assertArrayEquals(nonce, decoded.nonce)
            assertArrayEquals(initiatorFingerprint, decoded.initiatorFingerprint)
            assertArrayEquals(responderFingerprint, decoded.responderFingerprint)
            assertTrue(VoiceCallCapabilityCodec.verifySignature(decoded, signer.public.encoded))
        }
    }

    @Test
    fun rejectsTamperedNonceSessionAndIdentity() {
        val signer = keyPair()
        val sessionId = UUID.randomUUID()
        val nonce = ByteArray(32) { it.toByte() }
        val initiator = ByteArray(32) { (it + 1).toByte() }
        val responder = ByteArray(32) { (it + 33).toByte() }
        val payload = VoiceCallCapabilityCodec.signingPayload(
            VoiceCallCapabilityMessageType.REQUEST, sessionId, nonce, 1, initiator, responder,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private)
            update(payload)
            sign()
        }
        val original = VoiceCallCapabilityPacket(
            VoiceCallCapabilityMessageType.REQUEST, sessionId, nonce, 1, initiator, responder, signature,
        )
        assertTrue(VoiceCallCapabilityCodec.verifySignature(original, signer.public.encoded))
        assertFalse(
            VoiceCallCapabilityCodec.verifySignature(
                original.copy(nonce = nonce.copyOf().also { it[0] = 99 }),
                signer.public.encoded,
            ),
        )
        assertFalse(
            VoiceCallCapabilityCodec.verifySignature(
                original.copy(sessionId = UUID.randomUUID()),
                signer.public.encoded,
            ),
        )
        assertFalse(
            VoiceCallCapabilityCodec.verifySignature(
                original.copy(responderFingerprint = responder.copyOf().also { it[3] = 88 }),
                signer.public.encoded,
            ),
        )
    }

    @Test
    fun rejectsMalformedLengthAndUnsupportedFlags() {
        val signer = keyPair()
        val encoded = VoiceCallCapabilityCodec.encode(signedPacket(signer))
        assertThrows(IllegalArgumentException::class.java) {
            VoiceCallCapabilityCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        val nonZeroFlags = encoded.copyOf().also { it[7] = 1 }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceCallCapabilityCodec.decode(nonZeroFlags)
        }
    }

    @Test
    fun fingerprintParsingRequiresExactlySha256Hex() {
        val value = ByteArray(32) { it.toByte() }
        val encoded = value.joinToString("") { "%02x".format(it) }
        assertArrayEquals(value, VoiceCallCapabilityCodec.fingerprintBytes(encoded))
        assertThrows(IllegalArgumentException::class.java) {
            VoiceCallCapabilityCodec.fingerprintBytes("ab12")
        }
    }

    private fun signedPacket(keyPair: KeyPair): VoiceCallCapabilityPacket {
        val session = UUID.randomUUID()
        val nonce = ByteArray(32) { it.toByte() }
        val initiator = ByteArray(32) { (it + 1).toByte() }
        val responder = ByteArray(32) { (it + 33).toByte() }
        val payload = VoiceCallCapabilityCodec.signingPayload(
            VoiceCallCapabilityMessageType.REQUEST, session, nonce, 1, initiator, responder,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
        return VoiceCallCapabilityPacket(
            VoiceCallCapabilityMessageType.REQUEST, session, nonce, 1, initiator, responder, signature,
        )
    }

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
}
