package com.peyvand.core.transfer.manifest

import com.peyvand.core.transfer.protocol.TransferProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class TransferManifestCodecTest {
    @Test
    fun `manifest round trips unicode name mime and declared size`() {
        val manifest = TransferManifestItem(
            itemId = UUID.fromString("1db050d1-e850-44d5-9e3a-cc81c246c67f"),
            displayName = "گزارش پروژه 2026.pdf",
            mimeType = "application/pdf",
            sizeBytes = 12_345L,
        )

        val restored = TransferManifestCodec.decode(TransferManifestCodec.encode(manifest))

        assertEquals(manifest.itemId, restored.itemId)
        assertEquals(manifest.displayName, restored.displayName)
        assertEquals(manifest.mimeType, restored.mimeType)
        assertEquals(manifest.sizeBytes, restored.sizeBytes)
    }

    @Test
    fun `unknown size is represented explicitly and survives round trip`() {
        val item = TransferManifestItem(
            UUID.randomUUID(), "stream.bin", "application/octet-stream", null,
        )

        val restored = TransferManifestCodec.decode(TransferManifestCodec.encode(item))

        assertNull(restored.sizeBytes)
    }

    @Test
    fun `unknown mime type is normalized to generic binary rather than rejected`() {
        val item = TransferManifestItem(
            UUID.randomUUID(), "unknown.bin", "not a valid mime", 4L,
        )
        val restored = TransferManifestCodec.decode(TransferManifestCodec.encode(item))

        assertEquals("application/octet-stream", restored.mimeType)
    }

    @Test
    fun `untrusted path separators and bidi overrides are removed from destination name`() {
        val unsafeName = "../../report\u202Efdp.exe"

        val safeName = SafeTransferFileName.sanitize(unsafeName)

        assertFalse(safeName.contains('/'))
        assertFalse(safeName.contains('\\'))
        assertFalse(safeName.contains('\u202E'))
        assertEquals(safeName, TransferManifestCodec.decode(
            TransferManifestCodec.encode(
                TransferManifestItem(UUID.randomUUID(), unsafeName, "application/pdf", 1L),
            ),
        ).displayName)
    }

    @Test
    fun `unicode filename is bounded by utf8 bytes not character count`() {
        val safeName = SafeTransferFileName.sanitize("ش".repeat(500))
        assertTrue(safeName.toByteArray(Charsets.UTF_8).size <= TransferManifestCodec.MAX_NAME_BYTES)
    }

    @Test
    fun `empty and dot-only names receive a safe fallback`() {
        assertEquals("received-file", SafeTransferFileName.sanitize(".."))
        assertEquals("received-file", SafeTransferFileName.sanitize("   "))
    }

    @Test
    fun `negative size other than unknown sentinel is rejected by encoder`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferManifestCodec.encode(
                TransferManifestItem(UUID.randomUUID(), "x", "application/octet-stream", -2L),
            )
        }
    }

    @Test
    fun `manifest decoder rejects trailing bytes`() {
        val valid = TransferManifestCodec.encode(
            TransferManifestItem(UUID.randomUUID(), "x.bin", "application/octet-stream", 0L),
        )
        val invalid = valid + byteArrayOf(1)
        assertThrows(TransferProtocolException::class.java) {
            TransferManifestCodec.decode(invalid)
        }
    }

    @Test
    fun `manifest decoder rejects invalid negative declared size`() {
        val encoded = TransferManifestCodec.encode(
            TransferManifestItem(UUID.randomUUID(), "x.bin", "application/octet-stream", 0L),
        )
        ByteBuffer.wrap(encoded).putLong(encoded.size - 8, -2L)

        assertThrows(TransferProtocolException::class.java) {
            TransferManifestCodec.decode(encoded)
        }
    }
}