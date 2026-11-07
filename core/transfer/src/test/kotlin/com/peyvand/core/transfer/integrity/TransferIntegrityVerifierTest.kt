package com.peyvand.core.transfer.integrity

import com.peyvand.core.transfer.manifest.TransferManifestItem
import com.peyvand.core.transfer.protocol.TransferCompletionRecord
import com.peyvand.core.transfer.stream.TransferStreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class TransferIntegrityVerifierTest {
    private val itemId = UUID.randomUUID()
    private val digest = "ab".repeat(32)

    private fun manifest(size: Long? = 5L) = TransferManifestItem(
        itemId, "test.bin", "application/octet-stream", size,
    )

    private fun localResult(size: Long = 5L, sha: String = digest) =
        TransferStreamResult(size, sha)

    private fun remoteRecord(size: Long = 5L, sha: String = digest, id: UUID = itemId) =
        TransferCompletionRecord(id, size, sha)

    @Test
    fun `matching stream and authenticated completion produce verified receipt`() {
        val receipt = TransferIntegrityVerifier.verify(
            manifest(), localResult(), remoteRecord(),
        )

        assertEquals(itemId, receipt.itemId)
        assertEquals(5L, receipt.actualSizeBytes)
        assertEquals(digest, receipt.sha256Hex)
    }

    @Test
    fun `unknown manifest length accepts actual size after exact digest match`() {
        val receipt = TransferIntegrityVerifier.verify(
            manifest(size = null), localResult(), remoteRecord(),
        )
        assertEquals(5L, receipt.actualSizeBytes)
    }

    @Test
    fun `mismatched item byte count declared size or digest is rejected`() {
        assertThrows(TransferIntegrityException::class.java) {
            TransferIntegrityVerifier.verify(manifest(), localResult(), remoteRecord(id = UUID.randomUUID()))
        }
        assertThrows(TransferIntegrityException::class.java) {
            TransferIntegrityVerifier.verify(manifest(), localResult(), remoteRecord(size = 4L))
        }
        assertThrows(TransferIntegrityException::class.java) {
            TransferIntegrityVerifier.verify(manifest(6L), localResult(), remoteRecord())
        }
        assertThrows(TransferIntegrityException::class.java) {
            TransferIntegrityVerifier.verify(manifest(), localResult(), remoteRecord(sha = "cd".repeat(32)))
        }
    }

    @Test
    fun `malformed local or remote digest is rejected`() {
        assertThrows(TransferIntegrityException::class.java) {
            TransferIntegrityVerifier.verify(manifest(), localResult(sha = "bad"), remoteRecord())
        }
        assertThrows(TransferIntegrityException::class.java) {
            TransferIntegrityVerifier.verify(manifest(), localResult(), remoteRecord(sha = "bad"))
        }
    }
}