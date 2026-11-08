package com.peyvand.core.domain.transfer.batch

import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchTransferModelsTest {
    @Test
    fun `item snapshot rejects acknowledged bytes above receiver limit`() {
        assertThrows(IllegalArgumentException::class.java) {
            itemSnapshot(
                acknowledgedBytes = 11L,
                maximumAcceptedBytes = 10L,
            )
        }
    }

    @Test
    fun `item snapshot rejects acknowledged bytes above declared size`() {
        assertThrows(IllegalArgumentException::class.java) {
            itemSnapshot(
                acknowledgedBytes = 11L,
                declaredSizeBytes = 10L,
            )
        }
    }

    @Test
    fun `item snapshot rejects actual size beyond negotiated or declared bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            itemSnapshot(
                acknowledgedBytes = 11L,
                maximumAcceptedBytes = 10L,
                actualSizeBytes = 11L,
                sha256Hex = "a".repeat(64),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            itemSnapshot(
                acknowledgedBytes = 11L,
                declaredSizeBytes = 10L,
                actualSizeBytes = 11L,
                sha256Hex = "a".repeat(64),
            )
        }
    }

    @Test
    fun `completed snapshot requires matching receipt identity and terminal byte counts`() {
        val batchId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val hash = "b".repeat(64)

        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferItemSnapshot(
                batchId = batchId,
                itemId = itemId,
                ordinal = 0,
                displayName = "verified.bin",
                mimeType = "application/octet-stream",
                declaredSizeBytes = 4L,
                phase = BatchTransferItemPhase.COMPLETED,
                acknowledgedBytes = 4L,
                maximumAcceptedBytes = 4L,
                actualSizeBytes = 4L,
                sha256Hex = hash,
                receipt = BatchTransferItemReceipt(
                    batchId = UUID.randomUUID(),
                    itemId = itemId,
                    ordinal = 0,
                    actualSizeBytes = 4L,
                    sha256Hex = hash,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            itemSnapshot(
                phase = BatchTransferItemPhase.COMPLETED,
                acknowledgedBytes = 3L,
                actualSizeBytes = 4L,
                sha256Hex = hash,
                receipt = BatchTransferItemReceipt(
                    batchId = batchId,
                    itemId = itemId,
                    ordinal = 0,
                    actualSizeBytes = 4L,
                    sha256Hex = hash,
                ),
                batchId = batchId,
                itemId = itemId,
            )
        }
    }

    private fun itemSnapshot(
        acknowledgedBytes: Long = 0L,
        maximumAcceptedBytes: Long? = 10L,
        declaredSizeBytes: Long? = 10L,
        actualSizeBytes: Long? = null,
        sha256Hex: String? = null,
        phase: BatchTransferItemPhase = BatchTransferItemPhase.TRANSFERRING,
        receipt: BatchTransferItemReceipt? = null,
        batchId: UUID = UUID.randomUUID(),
        itemId: UUID = UUID.randomUUID(),
    ) = BatchTransferItemSnapshot(
        batchId = batchId,
        itemId = itemId,
        ordinal = 0,
        displayName = "file.bin",
        mimeType = "application/octet-stream",
        declaredSizeBytes = declaredSizeBytes,
        phase = phase,
        acknowledgedBytes = acknowledgedBytes,
        maximumAcceptedBytes = maximumAcceptedBytes,
        actualSizeBytes = actualSizeBytes,
        sha256Hex = sha256Hex,
        receipt = receipt,
    )
}
