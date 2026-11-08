package com.peyvand.core.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import com.peyvand.core.domain.transfer.TransferStagingSink
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Publishes completed, hash-verified files into PEYVAND directories rooted in Android's
 * standard public media locations. MediaStore RELATIVE_PATH stays under the canonical
 * Pictures, Movies, Music or Download root for its collection.
 */
internal class AndroidPeyvandDestinationPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun publishVerified(
        staging: TransferStagingSink,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        expectedSha256Hex: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            require(displayName.isNotBlank())
            require(mimeType.isNotBlank())
            require(expectedSizeBytes >= 0L)
            val expectedDigest = decodeSha256(expectedSha256Hex)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishWithMediaStore(staging, displayName, mimeType, expectedSizeBytes, expectedDigest)
            } else {
                publishWithLegacyDownloads(staging, displayName, mimeType, expectedSizeBytes, expectedDigest)
            }
        } catch (exception: Exception) {
            runCatching { staging.delete() }
            if (exception is CancellationException) throw exception
            throw exception
        } finally {
            runCatching { staging.close() }
        }
    }

    @Suppress("DEPRECATION")
    fun availableBytes(): Long =
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes

    private suspend fun publishWithMediaStore(
        staging: TransferStagingSink,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        expectedDigest: ByteArray,
    ): Boolean {
        val category = PeyvandDestinationCategoryResolver.resolve(displayName, mimeType)
        val relativePath = category.relativeDirectory + "/"
        val collection = mediaCollectionUri(category.collection)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizeDisplayName(displayName))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(collection, values)
            ?: throw IOException("MediaStore could not allocate a PEYVAND destination")

        try {
            val descriptor = resolver.openFileDescriptor(destination, "w")
                ?: throw IOException("MediaStore did not provide a writable destination")
            val copied = ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                staging.openVerifiedInputStream().use { source ->
                    copyAndDigest(source, output, expectedSizeBytes).also {
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
            requireExpectedDigest(copied, expectedSizeBytes, expectedDigest)

            val readBack = resolver.openInputStream(destination)
                ?: throw IOException("MediaStore destination could not be read back")
            val verified = readBack.use { calculateDigest(it, expectedSizeBytes) }
            requireExpectedDigest(verified, expectedSizeBytes, expectedDigest)

            val published = resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            if (published != 1) {
                throw IOException("MediaStore did not confirm publication of the verified file")
            }
            // Cleanup failure leaves only a private staging orphan; the startup/periodic
            // staging sweeper owns its bounded retention cleanup. Public data is verified.
            runCatching { staging.delete() }
            return true
        } catch (exception: Exception) {
            val deleted = runCatching { resolver.delete(destination, null, null) > 0 }
                .getOrDefault(false)
            runCatching { staging.delete() }
            if (!deleted) {
                val cleanupFailure = IOException(
                    "PEYVAND publication failed and MediaStore did not confirm pending-item cleanup",
                    exception,
                )
                if (exception is CancellationException) {
                    exception.addSuppressed(cleanupFailure)
                    throw exception
                }
                throw cleanupFailure
            }
            if (exception is CancellationException) throw exception
            throw exception
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun publishWithLegacyDownloads(
        staging: TransferStagingSink,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        expectedDigest: ByteArray,
    ): Boolean {
        if (appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Legacy shared-storage write permission has not been granted")
        }

        val category = PeyvandDestinationCategoryResolver.resolve(displayName, mimeType)
        val directory = File(Environment.getExternalStorageDirectory(), category.relativeDirectory)
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("Could not create the PEYVAND destination directory")
        }

        val temporary = File(directory, ".peyvand-" + UUID.randomUUID() + ".part")
        if (!temporary.createNewFile()) {
            throw IOException("Could not allocate a unique temporary destination file")
        }
        try {
            val copied = staging.openVerifiedInputStream().use { source ->
                FileOutputStream(temporary, false).use { output ->
                    copyAndDigest(source, output, expectedSizeBytes).also {
                        output.flush()
                        output.fd.sync()
                    }
                }
            }
            requireExpectedDigest(copied, expectedSizeBytes, expectedDigest)

            val finalFile = moveToUnusedName(temporary, directory, sanitizeDisplayName(displayName))
            val verified = FileInputStream(finalFile).use { calculateDigest(it, expectedSizeBytes) }
            try {
                requireExpectedDigest(verified, expectedSizeBytes, expectedDigest)
            } catch (exception: Exception) {
                runCatching { finalFile.delete() }
                throw exception
            }

            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(finalFile.absolutePath),
                arrayOf(mimeType),
                null,
            )
            runCatching { staging.delete() }
            return true
        } catch (exception: Exception) {
            runCatching { temporary.delete() }
            runCatching { staging.delete() }
            if (exception is CancellationException) throw exception
            throw exception
        }
    }

    private fun mediaCollectionUri(collection: PeyvandMediaCollection): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when (collection) {
            PeyvandMediaCollection.IMAGES -> MediaStore.Images.Media.getContentUri(volume)
            PeyvandMediaCollection.VIDEOS -> MediaStore.Video.Media.getContentUri(volume)
            PeyvandMediaCollection.AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            PeyvandMediaCollection.DOWNLOADS -> MediaStore.Downloads.getContentUri(volume)
        }
    }

    private fun moveToUnusedName(temporary: File, directory: File, safeName: String): File {
        val dot = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val extension = if (dot > 0) safeName.substring(dot) else ""
        repeat(MAX_NAME_COLLISION_ATTEMPTS) { attempt ->
            val candidateName = if (attempt == 0) safeName else "$stem ($attempt)$extension"
            val candidate = File(directory, candidateName)
            try {
                Files.move(temporary.toPath(), candidate.toPath())
                return candidate
            } catch (_: FileAlreadyExistsException) {
                // Never replace an existing user file; retry with a deterministic suffix.
            }
        }
        throw IOException("Could not allocate a collision-free PEYVAND filename")
    }

    private fun sanitizeDisplayName(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            .map { character ->
                when {
                    character == '/' || character.code == 0x5c || character == ':' -> '_'
                    character.code < 0x20 || character.code == 0x7f -> '_'
                    else -> character
                }
            }
            .joinToString("")
            .trim()
            .trim('.')
            .ifBlank { "received-file" }
        val output = StringBuilder()
        var index = 0
        var byteCount = 0
        while (index < normalized.length) {
            val codePoint = normalized.codePointAt(index)
            val codePointText = String(Character.toChars(codePoint))
            val codePointBytes = codePointText.toByteArray(Charsets.UTF_8).size
            if (byteCount + codePointBytes > MAX_DISPLAY_NAME_BYTES) break
            output.appendCodePoint(codePoint)
            byteCount += codePointBytes
            index += Character.charCount(codePoint)
        }
        return output.toString().ifBlank { "received-file" }
    }

    private suspend fun copyAndDigest(
        source: InputStream,
        destination: OutputStream,
        expectedSizeBytes: Long,
    ): Pair<Long, ByteArray> {
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            context.ensureActive()
            var count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) {
                val single = source.read()
                if (single < 0) break
                buffer[0] = single.toByte()
                count = 1
            }
            if (count.toLong() > expectedSizeBytes - total) {
                throw IOException("Published file exceeds the verified byte count")
            }
            context.ensureActive()
            destination.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count.toLong()
        }
        return total to digest.digest()
    }

    private suspend fun calculateDigest(input: InputStream, expectedSizeBytes: Long): Pair<Long, ByteArray> {
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            context.ensureActive()
            var count = input.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[0] = single.toByte()
                count = 1
            }
            if (count.toLong() > expectedSizeBytes - total) {
                throw IOException("Read-back file exceeds the verified byte count")
            }
            digest.update(buffer, 0, count)
            total += count.toLong()
        }
        return total to digest.digest()
    }

    private fun requireExpectedDigest(
        actual: Pair<Long, ByteArray>,
        expectedSizeBytes: Long,
        expectedDigest: ByteArray,
    ) {
        if (actual.first != expectedSizeBytes ||
            !MessageDigest.isEqual(actual.second, expectedDigest)
        ) {
            throw IOException("PEYVAND destination does not match the verified transfer receipt")
        }
    }

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

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_DISPLAY_NAME_BYTES = 255
        const val MAX_NAME_COLLISION_ATTEMPTS = 1000
        const val SHA256_BYTES = 32
        const val SHA256_HEX_LENGTH = SHA256_BYTES * 2
    }
}
