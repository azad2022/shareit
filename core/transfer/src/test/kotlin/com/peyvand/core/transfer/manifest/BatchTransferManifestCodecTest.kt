package com.peyvand.core.transfer.manifest

import com.peyvand.core.transfer.protocol.TransferProtocolException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTransferManifestCodecTest {
    @Test
    fun roundTripsThirtyTwoItemsAndPreservesUnicodeAndOrder() {
        val items = (0 until 32).map { index ->
            TransferManifestItem(
                itemId = UUID.randomUUID(),
                displayName = when (index) {
                    0 -> "گزارش مالی نهایی.pdf"
                    1 -> "没有扩展名"
                    else -> "file-$index",
                },
                mimeType = if (index % 2 == 0) "application/pdf" else "application/octet-stream",
                sizeBytes = index.toLong(),
            )
        }
        val manifest = BatchTransferManifest(UUID.randomUUID(), items)
        val encoded = BatchTransferManifestCodec.encode(manifest)
        val decoded = BatchTransferManifestCodec.decode(encoded)

        assertEquals(manifest, decoded)
        assertTrue(encoded.size <= 16 * 1024)
    }

    @Test
    fun rejectsManifestWithDuplicateItemIds() {
        val id = UUID.randomUUID()
        val duplicatePayload = java.io.ByteArrayOutputStream().apply {
            val data = java.io.DataOutputStream(this)
            data.writeLong(1L)
            data.writeLong(2L)
            data.writeShort(2)
            repeat(2) {
                val item = TransferManifestCodec.encode(
                    TransferManifestItem(id, "same.bin", "application/octet-stream", 1L),
                )
                data.writeShort(item.size)
                data.write(item)
            }
        }.toByteArray()

        val failure = runCatching {
            BatchTransferManifestCodec.decode(duplicatePayload)
        }.exceptionOrNull()
        assertTrue(failure is TransferProtocolException)
    }

    @Test
    fun rejectsThirtyThreeItemsBeforeEncoding() {
        val items = (0 until 33).map {
            TransferManifestItem(UUID.randomUUID(), "file-$it", "application/octet-stream", 0L)
        }
        assertTrue(runCatching {
            BatchTransferManifestCodec.encode(BatchTransferManifest(UUID.randomUUID(), items))
        }.isFailure)
    }

    @Test
    fun decisionIsBoundToExactBatchAndManifestAndRejectsUnknownBits() {
        val manifest = BatchTransferManifest(
            UUID.randomUUID(),
            listOf(
                TransferManifestItem(UUID.randomUUID(), "one", "text/plain", 1L),
                TransferManifestItem(UUID.randomUUID(), "two", "application/pdf", 2L),
            ),
        )
        val encodedManifest = BatchTransferManifestCodec.encode(manifest)
        val digest = BatchTransferManifestCodec.digest(encodedManifest)
        val decision = BatchConsentDecision(
            batchId = manifest.batchId,
            manifestDigestHex = digest,
            acceptedMask = 0b01,
        )
        val decoded = BatchConsentDecisionCodec.decode(
            BatchConsentDecisionCodec.encode(decision),
            manifest,
        )

        assertTrue(decoded.accepts(0))
        assertFalse(decoded.accepts(1))
        assertEquals(digest, decoded.manifestDigestHex)

        val unknownBits = decision.copy(acceptedMask = 0b100)
        assertTrue(runCatching {
            BatchConsentDecisionCodec.decode(
                BatchConsentDecisionCodec.encode(unknownBits),
                manifest,
            )
        }.exceptionOrNull() is TransferProtocolException)
    }

    @Test
    fun rejectsTrailingBytesAndTruncatedEntries() {
        val manifest = BatchTransferManifest(
            UUID.randomUUID(),
            listOf(
                TransferManifestItem(UUID.randomUUID(), "one", "text/plain", 1L),
                TransferManifestItem(UUID.randomUUID(), "two", "text/plain", 2L),
            ),
        )
        val valid = BatchTransferManifestCodec.encode(manifest)
        assertTrue(runCatching {
            BatchTransferManifestCodec.decode(valid + byteArrayOf(0x00))
        }.exceptionOrNull() is TransferProtocolException)
        assertTrue(runCatching {
            BatchTransferManifestCodec.decode(valid.copyOf(valid.size - 1))
        }.exceptionOrNull() is TransferProtocolException)
    }
}
