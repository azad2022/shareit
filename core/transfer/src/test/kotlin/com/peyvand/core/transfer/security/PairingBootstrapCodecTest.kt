package com.peyvand.core.transfer.security

import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PairingBootstrapCodecTest {
    @Test
    fun roundTripPreservesSessionRoleNonceAndCertificate() {
        val hello = sampleHello()
        val decoded = PairingBootstrapCodec.decode(PairingBootstrapCodec.encode(hello))

        assertEquals(hello.sessionId, decoded.sessionId)
        assertEquals(hello.role, decoded.role)
        assertArrayEquals(hello.nonceBytes(), decoded.nonceBytes())
        assertArrayEquals(hello.certificateBytes(), decoded.certificateBytes())
    }

    @Test
    fun decoderRejectsUnknownMagicAndVersion() {
        val encoded = PairingBootstrapCodec.encode(sampleHello())
        val badMagic = encoded.copyOf().also { it[0] = 0 }
        val badVersion = encoded.copyOf().also { it[4] = 9 }

        expectProtocolError { PairingBootstrapCodec.decode(badMagic) }
        expectProtocolError { PairingBootstrapCodec.decode(badVersion) }
    }

    @Test
    fun decoderRejectsUnknownRoleAndCertificateLengthBeforeAllocation() {
        val encoded = PairingBootstrapCodec.encode(sampleHello())
        val badRole = encoded.copyOf().also { it[5] = 99 }
        val badLength = encoded.copyOf().also {
            ByteBuffer.wrap(it, PairingBootstrapCodec.HEADER_BYTES - 2, 2)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(0)
        }

        expectProtocolError { PairingBootstrapCodec.decode(badRole) }
        expectProtocolError { PairingBootstrapCodec.decode(badLength) }
    }

    @Test
    fun decoderRejectsTruncatedAndTrailingData() {
        val encoded = PairingBootstrapCodec.encode(sampleHello())

        expectProtocolError { PairingBootstrapCodec.decode(encoded.copyOf(encoded.size - 1)) }
        expectProtocolError { PairingBootstrapCodec.decode(encoded + byteArrayOf(1)) }
    }

    private fun sampleHello() = PairingBootstrapHello(
        sessionId = UUID.fromString("ba2ee3c0-ea56-4f04-bca5-43c2e9e94f90"),
        role = PairingParticipantRole.SENDER,
        nonce = ByteArray(PairingBootstrapHello.NONCE_BYTES) { it.toByte() },
        certificateDer = ByteArray(384) { (it * 7).toByte() },
    )

    private fun expectProtocolError(block: () -> Unit) {
        try {
            block()
            fail("Expected TransferProtocolException")
        } catch (_: TransferProtocolException) {
            // Expected.
        }
    }
}
