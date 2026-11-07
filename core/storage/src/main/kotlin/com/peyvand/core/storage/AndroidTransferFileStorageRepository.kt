package com.peyvand.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import com.peyvand.core.domain.transfer.TransferFileSource
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.domain.transfer.TransferSourceMetadata
import com.peyvand.core.domain.transfer.TransferStagingSink
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * URI-only Android storage adapter. Remote filenames never become filesystem paths.
 * Receiving bytes remain in noBackupFilesDir until the receiver selects a SAF destination
 * and the published document passes a second length/hash verification.
 */
class AndroidTransferFileStorageRepository(
    context: Context,
) : TransferFileStorageRepository {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val stagingDirectory = File(appContext.noBackupFilesDir, STAGING_DIRECTORY_NAME)

    override suspend fun openSource(item: ShareItem): TransferFileSource = withContext(Dispatchers.IO) {
        val source = item.source as? ShareSource.ContentUri
            ?: throw UnsupportedOperationException("Only user-authorized document/media URIs are supported")
        val uri = parseContentUri(source.value)
        val metadata = readMetadata(uri, item)
        ContentUriTransferFileSource(uri, metadata)
    }

    override suspend fun maximumReceiveSizeBytes(): Long = withContext(Dispatchers.IO) {
        ensureStagingDirectory()
        cleanupExpiredStages()
        availableBytesAfterReserve()
    }

    override suspend fun createPrivateStaging(maximumSizeBytes: Long): TransferStagingSink =
        withContext(Dispatchers.IO) {
            require(maximumSizeBytes >= 0L) { "Staging limit cannot be negative" }
            ensureStagingDirectory()
            cleanupExpiredStages()
            val available = availableBytesAfterReserve()
            if (maximumSizeBytes > available) {
                throw IOException("Insufficient private storage for the requested transfer limit")
            }

            val file = File(
                stagingDirectory,
                "incoming-" + UUID.randomUUID().toString() + ".part",
            )
            if (!file.createNewFile()) {
                throw IOException("Could not allocate a private incoming-file stage")
            }
            try {
                PrivateFileStagingSink(file, maximumSizeBytes)
            } catch (exception: Exception) {
                file.delete()
                throw exception
            }
        }

    override suspend fun publishVerified(
        staging: TransferStagingSink,
        destinationUri: String,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        expectedSha256Hex: String,
    ): Boolean = withContext(Dispatchers.IO) {
        require(displayName.isNotBlank())
        require(mimeType.isNotBlank())
        require(expectedSizeBytes >= 0L)
        val target = parseContentUri(destinationUri)
        val expectedDigest = decodeSha256(expectedSha256Hex)

        try {
            val copied = staging.openVerifiedInputStream().use { source ->
                val output = resolver.openOutputStream(target, "wt")
                    ?: throw IOException("The selected document provider did not open a writable stream")
                output.use { destination ->
                    copyAndDigest(source, destination, expectedSizeBytes)
                }
            }
            if (copied.first != expectedSizeBytes ||
                !MessageDigest.isEqual(copied.second, expectedDigest)
            ) {
                throw IOException("Published destination does not match the verified transfer receipt")
            }

            val readBack = resolver.openInputStream(target)
                ?: throw IOException("The selected document provider cannot read back the published file")
            val verified = readBack.use { calculateDigest(it, expectedSizeBytes) }
            if (verified.first != expectedSizeBytes ||
                !MessageDigest.isEqual(verified.second, expectedDigest)
            ) {
                throw IOException("Published destination failed read-back integrity verification")
            }

            // The destination is already verified. A failed private-stage cleanup must not\n            // turn a valid published file into a reported failure; the bounded stale-stage\n            // cleanup removes any leftover private artifact on a later storage operation.\n            runCatching { staging.delete() }\n            true\n        } catch (exception: Exception) {\n            val destinationRemoved = deletePartialDestination(target)\n            runCatching { staging.delete() }\n            if (exception is CancellationException) throw exception\n            if (!destinationRemoved) {\n                throw IOException(\n                    "Publication failed and the document provider did not confirm partial-file removal",\n                    exception,\n                )\n            }\n            throw exception\n        } finally {
            runCatching { staging.close() }
        }
    }

    private fun readMetadata(uri: Uri, fallback: ShareItem): TransferSourceMetadata {
        var displayName = fallback.displayName
        var sizeBytes = fallback.sizeBytes

        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    cursor.getString(nameColumn)?.takeIf(String::isNotBlank)?.let {
                        displayName = it
                    }
                }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    cursor.getLong(sizeColumn).takeIf { it >= 0L }?.let {
                        sizeBytes = it
                    }
                }
            }
        }

        val mimeType = resolver.getType(uri)
            ?.takeIf(String::isNotBlank)
            ?: fallback.mimeType.takeIf(String::isNotBlank)
            ?: DEFAULT_MIME_TYPE

        return TransferSourceMetadata(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
        )
    }

    private fun ensureStagingDirectory() {
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw IOException("Could not create private receiving storage")
        }
        if (!stagingDirectory.isDirectory) {
            throw IOException("Private receiving storage is not a directory")
        }
    }

    private fun availableBytesAfterReserve(): Long {
        val available = StatFs(appContext.noBackupFilesDir.absolutePath).availableBytes
        return (available - MINIMUM_FREE_SPACE_RESERVE_BYTES).coerceAtLeast(0L)
    }

    private fun cleanupExpiredStages() {
        val expiry = System.currentTimeMillis() - STAGING_RETENTION_MILLIS
        stagingDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith("incoming-") && it.name.endsWith(".part") }
            ?.filter { it.lastModified() in 1L..expiry }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun parseContentUri(value: String): Uri {
        val uri = Uri.parse(value)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw IllegalArgumentException("Transfer source and destination must be content URIs")
        }
        return uri
    }

    private fun deletePartialDestination(uri: Uri): Boolean {
        val documentDeleted = runCatching {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                false
            }
        }.getOrDefault(false)

        if (documentDeleted) return true
        return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    private suspend fun copyAndDigest(\n        source: InputStream,\n        destination: OutputStream,\n        expectedSizeBytes: Long,\n    ): Pair<Long, ByteArray> {\n        val context = currentCoroutineContext()\n        val digest = MessageDigest.getInstance("SHA-256")\n        val buffer = ByteArray(COPY_BUFFER_BYTES)\n        var total = 0L\n        while (true) {\n            context.ensureActive()\n            var count = source.read(buffer, 0, buffer.size)\n            if (count < 0) break\n            if (count == 0) {\n                val single = source.read()\n                if (single < 0) break\n                buffer[0] = single.toByte()\n                count = 1\n            }\n            if (count.toLong() > expectedSizeBytes - total) {\n                throw IOException("Staged file exceeds the verified byte count")\n            }\n            context.ensureActive()\n            destination.write(buffer, 0, count)\n            digest.update(buffer, 0, count)\n            total += count.toLong()\n        }\n        destination.flush()\n        return total to digest.digest()\n    }\n\n    private suspend fun calculateDigest(\n        input: InputStream,\n        expectedSizeBytes: Long,\n    ): Pair<Long, ByteArray> {\n        val context = currentCoroutineContext()\n        val digest = MessageDigest.getInstance("SHA-256")\n        val buffer = ByteArray(COPY_BUFFER_BYTES)\n        var total = 0L\n        while (true) {\n            context.ensureActive()\n            var count = input.read(buffer, 0, buffer.size)\n            if (count < 0) break\n            if (count == 0) {\n                val single = input.read()\n                if (single < 0) break\n                buffer[0] = single.toByte()\n                count = 1\n            }\n            if (count.toLong() > expectedSizeBytes - total) {\n                throw IOException("Published document exceeds the verified byte count")\n            }\n            digest.update(buffer, 0, count)\n            total += count.toLong()\n        }\n        return total to digest.digest()\n    }\n
    private fun decodeSha256(value: String): ByteArray {
        if (value.length != SHA256_HEX_LENGTH || value.any {
                it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'
            }
        ) {
            throw IllegalArgumentException("Expected a 32-byte SHA-256 hex digest")
        }
        return ByteArray(SHA256_BYTES) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }

    private inner class ContentUriTransferFileSource(
        private val uri: Uri,
        override val metadata: TransferSourceMetadata,
    ) : TransferFileSource {
        private var openedStream: InputStream? = null

        override fun openStream(): InputStream {
            check(openedStream == null) { "A transfer source can be opened only once" }
            return (resolver.openInputStream(uri)
                ?: throw FileNotFoundException("The selected source URI could not be opened"))
                .also { openedStream = it }
        }

        override fun close() {
            openedStream?.close()
            openedStream = null
        }
    }

    private class PrivateFileStagingSink(
        private val file: File,
        private val maximumSizeBytes: Long,
    ) : TransferStagingSink {
        private var output: FileOutputStream? = FileOutputStream(file)
        private var sealed = false
        private var deleted = false
        private var writtenBytes = 0L

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!sealed && !deleted) { "The private stage is not writable" }
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length.toLong() > maximumSizeBytes - writtenBytes) {
                throw IOException("Incoming file exceeds the available private storage limit")
            }
            (output ?: throw IOException("Private stage output is closed")).write(bytes, offset, length)
            writtenBytes += length.toLong()
        }

        override fun sync() {
            check(!sealed && !deleted) { "The private stage is not writable" }
            val stream = output ?: throw IOException("Private stage output is closed")
            stream.flush()
            stream.fd.sync()
        }

        override fun seal() {
            if (sealed) return
            sync()
            output?.close()
            output = null
            sealed = true
        }

        override fun openVerifiedInputStream(): InputStream {
            check(sealed && !deleted) { "Only a sealed private stage can be read" }
            return FileInputStream(file)
        }

        override fun delete() {
            if (deleted) return
            output?.close()
            output = null
            if (file.exists() && !file.delete()) {
                throw IOException("Could not remove private incoming-file stage")
            }
            deleted = true
        }

        override fun close() {
            output?.close()
            output = null
        }
    }

    private companion object {
        const val STAGING_DIRECTORY_NAME = "peyvand-transfer-staging"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MINIMUM_FREE_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L
        const val STAGING_RETENTION_MILLIS = 24L * 60L * 60L * 1000L
        const val SHA256_HEX_LENGTH = 64
        const val SHA256_BYTES = 32
    }
}
