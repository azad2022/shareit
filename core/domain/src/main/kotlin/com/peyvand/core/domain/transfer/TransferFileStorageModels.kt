package com.peyvand.core.domain.transfer

import com.peyvand.core.domain.files.ShareItem
import java.io.Closeable
import java.io.InputStream

data class TransferSourceMetadata(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
) {
    init {
        require(displayName.isNotBlank())
        require(mimeType.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0L)
    }
}

interface TransferFileSource : Closeable {
    val metadata: TransferSourceMetadata

    /** Opens the user-authorized source stream. A handle is intended for one transfer. */
    fun openStream(): InputStream
}

/**
 * App-private receiving stage. A chunk may be acknowledged only after [sync] succeeds.
 * A staged file is never a completed user-visible artifact until it is verified and published.
 */
interface TransferStagingSink : Closeable {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Flushes the current bytes to the durability level used by the receiver ACK policy. */
    fun sync()

    /** Closes the write side after the final byte is received. */
    fun seal()

    /** Available only after seal; the returned stream must be closed by the caller. */
    fun openVerifiedInputStream(): InputStream

    /** Deletes the private stage, including after a transfer failure or cancellation. */
    fun delete()
}

interface TransferFileStorageRepository {
    /** Only user-authorized ContentUri sources are supported in the first file-transfer slice. */
    suspend fun openSource(item: ShareItem): TransferFileSource

    /**
     * Conservative maximum receivable bytes after reserving space for both private staging
     * and the public destination copy. This point-in-time limit is checked again at stage creation.
     */
    suspend fun maximumReceiveSizeBytes(): Long

    /** Creates an unpredictable, app-private stage and enforces [maximumSizeBytes] while writing. */
    suspend fun createPrivateStaging(maximumSizeBytes: Long): TransferStagingSink

    /**
     * Copies a sealed, already verified stage to a user-selected document URI, re-hashes the
     * published document, and removes the stage only after read-back verification succeeds.
     */
    suspend fun publishVerified(
        staging: TransferStagingSink,
        destinationUri: String,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        expectedSha256Hex: String,
    ): Boolean

    /**
     * Publishes a verified stage into PEYVAND's app-managed public receive hierarchy.
     * The implementation chooses the Android storage API from the media type and API level,
     * never overwrites an existing user file, verifies the published bytes, and only then
     * makes the result visible to other applications.
     */
    suspend fun publishVerifiedToDefaultDestination(
        staging: TransferStagingSink,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        expectedSha256Hex: String,
    ): Boolean = throw UnsupportedOperationException(
        "PEYVAND default destination publication is not supported by this storage adapter",
    )

    /**
     * Legacy explicit-URI publication retained for controlled compatibility paths.
     */
    suspend fun publishVerifiedToTree(
        staging: TransferStagingSink,
        treeUri: String,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long,
        expectedSha256Hex: String,
    ): Boolean = throw UnsupportedOperationException("Tree publication is not supported by this storage adapter")
}
