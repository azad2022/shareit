package com.peyvand.core.transfer.security

import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bounded plaintext bootstrap used only to exchange an ephemeral certificate,
 * nonce, role and session binding before the SAS comparison.
 * No filename, MIME type, size or file bytes belong in this record.
 */
class PairingBootstrapHello(
    val sessionId: UUID,
    val role: PairingParticipantRole,
    nonce: ByteArray,
    certificateDer: ByteArray,
) {
    private val nonceValue = nonce.copyOf()
    private val certificateValue = certificateDer.copyOf()

    init {
        require(nonceValue.size == NONCE_BYTES) { "Pairing nonce must be exactly 32 bytes" }
        require(certificateValue.size in 1..MAX_CERTIFICATE_BYTES) {
            "Ephemeral certificate size is outside the bootstrap limit"
        }
    }

    fun nonceBytes(): ByteArray = nonceValue.copyOf()

    fun certificateBytes(): ByteArray = certificateValue.copyOf()

    companion object {
        const val NONCE_BYTES = 32
        const val MAX_CERTIFICATE_BYTES = 4 * 1024
    }
}

object PairingBootstrapCodec {
    const val CURRENT_VERSION = 1
    const val HEADER_BYTES = 4 + 1 + 1 + 16 + PairingBootstrapHello.NONCE_BYTES + 2
    private const val MAGIC = 0x50595650

    fun encode(hello: PairingBootstrapHello): ByteArray {
        val output = ByteArrayOutputStream(HEADER_BYTES + hello.certificateBytes().size)
        write(hello, output)
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): PairingBootstrapHello {
        val input = ByteArrayInputStream(bytes)
        val hello = read(input)
        if (input.available() != 0) {
            throw TransferProtocolException("Unexpected trailing bootstrap bytes")
        }
        return hello
    }

    /** Does not close the caller-owned stream; flushes the bounded record. */
    @Throws(TransferProtocolException::class)
    fun write(hello: PairingBootstrapHello, output: OutputStream) {
        val nonce = hello.nonceBytes()
        val certificate = hello.certificateBytes()
        if (nonce.size != PairingBootstrapHello.NONCE_BYTES ||
            certificate.size !in 1..PairingBootstrapHello.MAX_CERTIFICATE_BYTES
        ) {
            throw TransferProtocolException("Invalid bootstrap field size")
        }
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeByte(CURRENT_VERSION)
        data.writeByte(hello.role.wireCode)
        data.writeLong(hello.sessionId.mostSignificantBits)
        data.writeLong(hello.sessionId.leastSignificantBits)
        data.write(nonce)
        data.writeShort(certificate.size)
        data.write(certificate)
        data.flush()
    }

    /**
     * Reads exactly one bounded record. The untrusted certificate length is validated
     * before allocation, and malformed or truncated fields fail closed.
     */
    @Throws(TransferProtocolException::class)
    fun read(input: InputStream): PairingBootstrapHello {
        try {
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) {
                throw TransferProtocolException("Invalid pairing bootstrap magic")
            }
            val version = data.readUnsignedByte()
            if (version != CURRENT_VERSION) {
                throw TransferProtocolException("Unsupported pairing bootstrap version")
            }
            val roleCode = data.readUnsignedByte()
            val role = PairingParticipantRole.entries.firstOrNull {
                it.wireCode == roleCode
            } ?: throw TransferProtocolException("Unknown pairing participant role")
            val sessionId = UUID(data.readLong(), data.readLong())
            val nonce = ByteArray(PairingBootstrapHello.NONCE_BYTES)
            data.readFully(nonce)
            val certificateLength = data.readUnsignedShort()
            if (certificateLength !in 1..PairingBootstrapHello.MAX_CERTIFICATE_BYTES) {
                throw TransferProtocolException("Invalid ephemeral certificate length")
            }
            val certificate = ByteArray(certificateLength)
            data.readFully(certificate)
            return PairingBootstrapHello(sessionId, role, nonce, certificate)
        } catch (exception: TransferProtocolException) {
            throw exception
        } catch (exception: EOFException) {
            throw TransferProtocolException("Truncated pairing bootstrap record", exception)
        } catch (exception: IOException) {
            throw TransferProtocolException("Unable to read pairing bootstrap record", exception)
        } catch (exception: IllegalArgumentException) {
            throw TransferProtocolException("Invalid pairing bootstrap record", exception)
        }
    }
}
