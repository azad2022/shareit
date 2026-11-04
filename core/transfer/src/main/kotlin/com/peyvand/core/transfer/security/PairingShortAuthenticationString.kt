package com.peyvand.core.transfer.security

import com.peyvand.core.transfer.protocol.TransferFrameCodec
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class PairingParticipantRole(val wireCode: Int) {
    SENDER(1),
    RECEIVER(2),
}

class PairingEndpointMaterial(
    certificateFingerprintSha256: ByteArray,
    nonce: ByteArray,
    val role: PairingParticipantRole,
) {
    private val fingerprint = certificateFingerprintSha256.copyOf()
    private val randomNonce = nonce.copyOf()

    init {
        require(fingerprint.size == SHA256_BYTES) { "Certificate fingerprint must be SHA-256" }
        require(randomNonce.size == NONCE_BYTES) { "Pairing nonce must contain 32 random bytes" }
    }

    fun fingerprintBytes(): ByteArray = fingerprint.copyOf()

    fun nonceBytes(): ByteArray = randomNonce.copyOf()

    internal fun fingerprintUnsafe(): ByteArray = fingerprint

    internal fun nonceUnsafe(): ByteArray = randomNonce

    companion object {
        const val SHA256_BYTES = 32
        const val NONCE_BYTES = 32
    }
}

object PairingShortAuthenticationString {
    private val domain = "PEYVAND-PAIRING-SAS-V1\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val decimalModulus = BigInteger.TEN.pow(10)

    /**
     * Derives a display code from the complete session transcript. Inputs are sorted
     * canonically so both peers produce the same code regardless of local/remote order.
     * This is a human comparison step, not a replacement for mutual TLS.
     */
    fun derive(
        sessionId: UUID,
        local: PairingEndpointMaterial,
        remote: PairingEndpointMaterial,
        protocolVersion: Int = TransferFrameCodec.CURRENT_PROTOCOL_VERSION,
    ): String {
        require(protocolVersion == TransferFrameCodec.CURRENT_PROTOCOL_VERSION) {
            "Unsupported pairing transcript version"
        }
        require(local.role != remote.role) { "Pairing endpoints must have opposite product roles" }
        require(!local.fingerprintUnsafe().contentEquals(remote.fingerprintUnsafe())) {
            "Pairing endpoints must use distinct ephemeral certificates"
        }

        val ordered = listOf(local, remote).sortedWith(::compareEndpoints)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(domain)
        val header = ByteBuffer.allocate(4 + 16).order(ByteOrder.BIG_ENDIAN)
            .putInt(protocolVersion)
            .putLong(sessionId.mostSignificantBits)
            .putLong(sessionId.leastSignificantBits)
            .array()
        digest.update(header)

        ordered.forEach { endpoint ->
            digest.update(endpoint.fingerprintUnsafe())
            digest.update(endpoint.nonceUnsafe())
            digest.update(endpoint.role.wireCode.toByte())
        }

        val numericCode = BigInteger(1, digest.digest()).mod(decimalModulus).toString()
        return numericCode.padStart(10, '0')
    }

    private fun compareEndpoints(
        left: PairingEndpointMaterial,
        right: PairingEndpointMaterial,
    ): Int {
        compareUnsigned(left.fingerprintUnsafe(), right.fingerprintUnsafe())
            .takeIf { it != 0 }?.let { return it }
        left.role.wireCode.compareTo(right.role.wireCode)
            .takeIf { it != 0 }?.let { return it }
        return compareUnsigned(left.nonceUnsafe(), right.nonceUnsafe())
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in left.indices) {
            val difference = (left[index].toInt() and 0xff) -
                (right[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return left.size.compareTo(right.size)
    }
}