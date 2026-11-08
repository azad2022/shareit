package com.peyvand.core.storage

import android.Manifest
import android.content.ContentUris
import android.os.Build
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peyvand.core.domain.transfer.TransferStagingSink
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPeyvandDestinationPublisherTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun verifiedJpegIsPublishedToPicturesPeyvand() = runBlocking {
        val jpeg = android.util.Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0dHx8fExciJCIeJBweHx7/2wBDAQUFBQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh7/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDyyiiivzo/ss//2Q==",
            android.util.Base64.DEFAULT,
        )
        publishAndVerify(
            bytes = jpeg,
            name = "peyvand-test-" + UUID.randomUUID().toString() + ".jpg",
            mime = "image/jpeg",
            relativeDirectory = "Pictures/PEYVAND/Images",
            collectionKind = CollectionKind.IMAGES,
        )
    }

    @Test
    fun verifiedPdfIsPublishedToDownloadsPeyvand() = runBlocking {
        val pdf = "%PDF-1.7\n% PEYVAND instrumentation test\n%%EOF\n".toByteArray(Charsets.US_ASCII)
        publishAndVerify(
            bytes = pdf,
            name = "peyvand-test-" + UUID.randomUUID().toString() + ".pdf",
            mime = "application/pdf",
            relativeDirectory = "Download/PEYVAND/Documents",
            collectionKind = CollectionKind.DOWNLOADS,
        )
    }

    private suspend fun publishAndVerify(
        bytes: ByteArray,
        name: String,
        mime: String,
        relativeDirectory: String,
        collectionKind: CollectionKind,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
            assertTrue(
                context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
            assertTrue(
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            )
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (collectionKind) {
                CollectionKind.IMAGES ->
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                CollectionKind.DOWNLOADS ->
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else {
            null
        }

        val stage = TestStagingSink(bytes)
        val publisher = AndroidPeyvandDestinationPublisher(context)
        assertTrue(
            publisher.publishVerified(
                staging = stage,
                displayName = name,
                mimeType = mime,
                expectedSizeBytes = bytes.size.toLong(),
                expectedSha256Hex = sha256(bytes),
            ),
        )
        assertTrue(stage.deleted)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetCollection = requireNotNull(collection)
            val projection = arrayOf(
                BaseColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_PENDING,
            )
            val uri = context.contentResolver.query(
                targetCollection,
                projection,
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf(name, "$relativeDirectory/"),
                null,
            )?.use { cursor ->
                assertTrue("Published MediaStore item is missing", cursor.moveToFirst())
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)))
                assertEquals(
                    "$relativeDirectory/",
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)),
                )
                ContentUris.withAppendedId(
                    targetCollection,
                    cursor.getLong(cursor.getColumnIndexOrThrow(BaseColumns._ID)),
                )
            } ?: throw AssertionError("MediaStore query returned null")

            val actual = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw AssertionError("Published MediaStore item could not be read")
            assertArrayEquals(bytes, actual)
            context.contentResolver.delete(uri, null, null)
        } else {
            @Suppress("DEPRECATION")
            val file = File(Environment.getExternalStorageDirectory(), "$relativeDirectory/$name")
            assertTrue("Published shared-storage file is missing", file.isFile)
            assertArrayEquals(bytes, file.readBytes())
            assertTrue("Test output cleanup failed", file.delete())
        }
    }

    private enum class CollectionKind { IMAGES, DOWNLOADS }

    private class TestStagingSink(
        private val bytes: ByteArray,
    ) : TransferStagingSink {
        var deleted = false
            private set

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            error("The publisher test uses an already sealed staging file")
        }

        override fun sync() = Unit

        override fun seal() = Unit

        override fun openVerifiedInputStream(): InputStream {
            check(!deleted)
            return ByteArrayInputStream(bytes)
        }

        override fun delete() {
            deleted = true
        }

        override fun close() = Unit
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
