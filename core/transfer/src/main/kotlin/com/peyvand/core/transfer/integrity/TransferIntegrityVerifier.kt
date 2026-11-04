package com.peyvand.core.transfer.integrity

import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.TransferCompletionRecord
import com.peyvand.core.transfer.stream.TransferStreamResult
import java.security.MessageDigest

data class VerifiedTransferReceipt(
    val itemId: java.util.UUID,
    val actualSizeBytes: Long,
    val sha256Hex: String,
)

class TransferIntegrityException(message: String) : IllegalStateException(message)

object TransferIntegrityVerifier {
    /**
     * Verifies the remote completion declaration against the local streaming digest
     * and the optional manifest size. Safe to use on the receiver before publication
     * and on the sender when validating the receiver's authenticated receipt.
     */
    fun verify(
        manifest: TransferManifestItem,
        localStreamResult: TransferStreamResult,
        remoteRecord: TransferCompletionRecord,
    ): VerifiedTransferReceipt {
        if (manifest.itemId != remoteRecord.itemId) {
            throw TransferIntegrityException("Completion record belongs to another item")
        }
        if (localStreamResult.bytesTransferred < 0L || remoteRecord.actualSizeBytes < 0L) {
            throw TransferIntegrityException("Invalid negative transfer byte count")
        }
        if (localStreamResult.bytesTransferred != remoteRecord.actualSizeBytes) {
            throw TransferIntegrityException("Peer-reported byte count does not match local bytes")
        }
        if (manifest.sizeBytes != null && manifest.sizeBytes != localStreamResult.bytesTransferred) {
            throw TransferIntegrityException("Actual byte count does not match declared manifest size")
        }

        val localDigest = decodeSha256(localStreamResult.sha256Hex)
        val remoteDigest = decodeSha256(remoteRecord.sha256Hex)
        if (!MessageDigest.isEqual(localDigest, remoteDigest)) {
            throw TransferIntegrityException("SHA-256 digest mismatch")
        }

        return VerifiedTransferReceipt(
            itemId = manifest.itemId,
            actualSizeBytes = localStreamResult.bytesTransferred,
            sha256Hex = localStreamResult.sha256Hex.lowercase(),
        )
    }

    private fun decodeSha256(value: String): ByteArray {
        if (value.length != 64 || value.any {
                it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'
            }
        ) {
            throw TransferIntegrityException("Malformed SHA-256 digest")
        }
        return ByteArray(32) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }
}