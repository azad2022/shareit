package com.peyvand.core.transfer.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchTransferPayloadCodecTest {
    private val batchId = UUID.fromString("b88c5d56-e79b-4bd5-b82d-4e7a0ce2f013")
    private val itemId = UUID.fromString("d8e8b9f2-688e-48fb-b8a1-b5d67e017a34")
    private val identity = BatchTransferItemIdentity(batchId, itemId, 3)

    @Test
    fun batchOfferRoundTripsKnownAndUnknownAggregateSizes() {
        val known = BatchTransferOffer(batchId, 5, 1_024L)
        assertEquals(known, BatchTransferOfferCodec.decode(BatchTransferOfferCodec.encode(known)))

        val unknown = BatchTransferOffer(batchId, 2, null)
        assertEquals(unknown, BatchTransferOfferCodec.decode(BatchTransferOfferCodec.encode(unknown)))
    }

    @Test
    fun batchOfferRejectsCountsOutsideV2BoundsAndInvalidAggregateSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferOffer(batchId, 1, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferOffer(batchId, 101, 0L)
        }
        val encoded = BatchTransferOfferCodec.encode(BatchTransferOffer(batchId, 2, null))
        ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putLong(18, -2L)
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferOfferCodec.decode(encoded)
        }
    }

    @Test
    fun itemManifestRoundTripsUnicodeAndUnknownSizeWithoutTransmittingUri() {
        val manifest = BatchTransferItemManifest(
            identity = identity,
            displayName = "گزارش مالی – 東京.pdf",
            mimeType = "Application/PDF",
            sizeBytes = null,
        )
        val encoded = BatchTransferItemManifestCodec.encode(manifest)
        val decoded = BatchTransferItemManifestCodec.decode(encoded)

        assertEquals(identity, decoded.identity)
        assertEquals("گزارش مالی – 東京.pdf", decoded.displayName)
        assertEquals("application/pdf", decoded.mimeType)
        assertNull(decoded.sizeBytes)
    }

    @Test
    fun itemManifestSanitizesPathSeparatorsAndControlCharacters() {
        val manifest = BatchTransferItemManifest(
            identity = identity,
            displayName = "../folder\\unsafe\nname.txt",
            mimeType = "text/plain",
            sizeBytes = 7L,
        )
        val decoded = BatchTransferItemManifestCodec.decode(
            BatchTransferItemManifestCodec.encode(manifest),
        )

        assertEquals(".._folder_unsafe_name.txt", decoded.displayName)
    }

    @Test
    fun itemManifestRejectsMalformedUtf8AndInvalidOrdinal() {
        val encoded = BatchTransferItemManifestCodec.encode(
            BatchTransferItemManifest(identity, "valid.txt", "text/plain", 1L),
        )
        val malformed = encoded.copyOf()
        val nameLength = ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN).getShort(40).toInt() and 0xffff
        assertEquals(9, nameLength)
        malformed[42] = 0xff.toByte()
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemManifestCodec.decode(malformed)
        }

        val invalidOrdinal = encoded.copyOf()
        ByteBuffer.wrap(invalidOrdinal).order(ByteOrder.BIG_ENDIAN).putInt(16, 100)
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemManifestCodec.decode(invalidOrdinal)
        }
    }

    @Test
    fun itemAcceptanceBindsExactIdentityAndNonNegativeReceiverLimit() {
        val acceptance = BatchTransferItemAcceptance(identity, 987_654L)
        assertEquals(
            acceptance,
            BatchTransferItemAcceptanceCodec.decode(
                BatchTransferItemAcceptanceCodec.encode(acceptance),
            ),
        )

        val negative = BatchTransferItemAcceptanceCodec.encode(acceptance)
        ByteBuffer.wrap(negative).order(ByteOrder.BIG_ENDIAN).putLong(36, -1L)
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemAcceptanceCodec.decode(negative)
        }
    }

    @Test
    fun itemRejectionRejectsUnknownReasonCodesAndWrongPayloadLength() {
        val rejection = BatchTransferItemRejection(
            identity,
            BatchTransferReasonCode.USER_REJECTED,
        )
        assertEquals(
            rejection,
            BatchTransferItemRejectionCodec.decode(
                BatchTransferItemRejectionCodec.encode(rejection),
            ),
        )

        val unknown = BatchTransferItemRejectionCodec.encode(rejection)
        unknown[36] = 99
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemRejectionCodec.decode(unknown)
        }
        assertThrows(TransferProtocolException::class.java) {
            BatchTransferItemRejectionCodec.decode(byteArrayOf())
        }
    }

    @Test
    fun itemIdentityRejectsSameBatchAndItemIdAndOutOfRangeOrdinal() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferItemIdentity(batchId, batchId, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BatchTransferItemIdentity(batchId, itemId, 100)
        }
    }
}
