package com.peyvand.core.transfer.manifest

import com.peyvand.core.transfer.protocol.TransferProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class TransferManifestCodecTest {
    private val digest = "ab".repeat(32)

    @Test
    fun `manifest round trips arbitrary filename mime size and digest`() {
        val manifest = TransferManifestItem(
            itemId = UUID.fromString("1db050d1-e850-44d5-9e3a-cc81c246c67f"),
            displayName = "گزارش پروژه 2026.pdf",
            mimeType = "application/pdf",
            sizeBytes = 12_345L,
            sha256Hex = digest,
        )

        val restored = TransferManifestCodec.decode(TransferManifestCodec.encode(manifest))

        assertEquals(manifest.itemId, restored.itemId)
        assertEquals(manifest.displayName, restored.displayName)
        assertEquals(manifest.mimeType, restored.mimeType)
        assertEquals(manifest.sizeBytes, restored.sizeBytes)
        assertEquals(digest, restored.sha256Hex)
    }

    @Test
    fun `unknown mime type is retained as generic binary rather than rejected`() {
        val manifest = TransferManifestItem(
            UUID.randomUUID(), "unknown.bin", "not a valid mime", 4L, digest,
        )
        val restored = TransferManifestCodec.decode(TransferManifestCodec.encode(manifest))

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
                TransferManifestItem(UUID.randomUUID(), unsafeName, "application/pdf", 1L, digest),
            ),
        ).displayName)
    }

    @Test
    fun `unicode filename is bounded by utf8 bytes not character count`() {
        val safeName = SafeTransferFileName.sanitize("ش".repeat(500))
        assertTrue(safeName.toByteArray(Charsets.UTF_8).size <= TransferManifestCodec.MAX_NAME_BYTES)
    }

    @Test
    fun `empty and traversal-only names receive a safe fallback`() {
        assertEquals("received-file", SafeTransferFileName.sanitize("../.."))
        assertEquals("received-file", SafeTransferFileName.sanitize("   "))
    }

    @Test
    fun `negative file size is rejected by encoder`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferManifestCodec.encode(
                TransferManifestItem(UUID.randomUUID(), "x", "application/octet-stream", -1L, digest),
            )
        }
    }

    @Test
    fun `malformed sha256 is rejected by encoder`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferManifestCodec.encode(
                TransferManifestItem(UUID.randomUUID(), "x", "application/octet-stream", 1L, "1234"),
            )
        }
    }

    @Test
    fun `manifest decoder rejects trailing bytes`() {
        val valid = TransferManifestCodec.encode(
            TransferManifestItem(UUID.randomUUID(), "x.bin", "application/octet-stream", 0L, digest),
        )
        val invalid = valid + byteArrayOf(1)
        assertThrows(TransferProtocolException::class.java) {
            TransferManifestCodec.decode(invalid)
        }
    }

    @Test
    fun `manifest decoder rejects negative length field`() {
        val encoded = TransferManifestCodec.encode(
            TransferManifestItem(UUID.randomUUID(), "x.bin", "application/octet-stream", 0L, digest),
        )
        // The manifest's size is the eight bytes immediately before the SHA-256 digest.
        ByteBuffer.wrap(encoded).putLong(encoded.size - 40, -1L)

        assertThrows(TransferProtocolException::class.java) {
            TransferManifestCodec.decode(encoded)
        }
    }
}