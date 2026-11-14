package com.peyvand.core.data.profile

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.peyvand.core.data.chat.ChatDatabase
import com.peyvand.core.data.chat.LocalDeviceProfileEntity
import com.peyvand.core.domain.profile.DeviceProfileRepository
import com.peyvand.core.domain.profile.DeviceProfileRules
import com.peyvand.core.domain.profile.LocalDeviceProfile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Stores the local profile in the existing private Room database.
 * Avatar bytes are canonical JPEG, bounded to 32 KiB, and never retain the picker URI.
 */
class RoomDeviceProfileRepository(
    context: Context,
    private val database: ChatDatabase,
) : DeviceProfileRepository {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun observeLocalProfile(): Flow<LocalDeviceProfile> =
        database.localDeviceProfile().observe().map { entity ->
            entity?.toDomain() ?: LocalDeviceProfile()
        }

    override suspend fun getLocalProfile(): LocalDeviceProfile =
        withContext(Dispatchers.IO) {
            database.localDeviceProfile().get()?.toDomain() ?: LocalDeviceProfile()
        }

    override suspend fun importAvatar(contentUri: String): ByteArray =
        withContext(Dispatchers.IO) {
            require(contentUri.isNotBlank())
            val uri = Uri.parse(contentUri)
            require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
                "Only a user-selected content URI is accepted for a profile image."
            }
            val mimeType = resolver.getType(uri)?.lowercase()
            require(mimeType?.startsWith("image/") == true && mimeType != "image/svg+xml") {
                "The selected item is not a supported raster image."
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "The selected image could not be opened." }
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            require(
                bounds.outWidth in 1..MAX_SOURCE_DIMENSION &&
                    bounds.outHeight in 1..MAX_SOURCE_DIMENSION &&
                    bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_SOURCE_PIXELS,
            ) {
                "The selected image dimensions exceed the safe processing limit."
            }

            val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "The selected image could not be reopened." }
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: error("The selected image could not be decoded.")

            val orientation = resolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "The selected image metadata could not be read." }
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
            val oriented = applyOrientation(decoded, orientation)
            val cropped = centerCrop(oriented, OUTPUT_DIMENSION)
            try {
                encodeBoundedJpeg(cropped)
            } finally {
                if (cropped !== oriented && !cropped.isRecycled) cropped.recycle()
                if (oriented !== decoded && !oriented.isRecycled) oriented.recycle()
                if (!decoded.isRecycled) decoded.recycle()
            }
        }

    override suspend fun saveLocalProfile(
        deviceNameOverride: String?,
        profileName: String?,
        avatarBytes: ByteArray?,
        shareWithTrustedPeers: Boolean,
    ): LocalDeviceProfile = withContext(Dispatchers.IO) {
        val normalizedDeviceName = DeviceProfileRules.normalizeDeviceName(deviceNameOverride)
        val normalizedProfileName = DeviceProfileRules.normalizeProfileName(profileName)
        DeviceProfileRules.validateAvatar(avatarBytes)
        val current = database.localDeviceProfile().get()?.toDomain() ?: LocalDeviceProfile()

        val same = current.deviceNameOverride == normalizedDeviceName &&
            current.profileName == normalizedProfileName &&
            nullableBytesEqual(current.avatarBytes, avatarBytes) &&
            current.shareWithTrustedPeers == shareWithTrustedPeers
        if (same) return@withContext current

        check(current.revision < Long.MAX_VALUE) { "The local profile revision is exhausted." }
        val updated = LocalDeviceProfile(
            deviceNameOverride = normalizedDeviceName,
            profileName = normalizedProfileName,
            avatarBytes = avatarBytes?.copyOf(),
            revision = current.revision + 1L,
            shareWithTrustedPeers = shareWithTrustedPeers,
        )
        database.withTransaction {
            database.localDeviceProfile().replace(updated.toEntity())
        }
        updated
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    }

    private fun centerCrop(bitmap: Bitmap, size: Int): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val square = Bitmap.createBitmap(bitmap, left, top, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        if (square !== bitmap && square !== scaled && !square.isRecycled) {
            square.recycle()
        }
        return scaled
    }

    private fun encodeBoundedJpeg(bitmap: Bitmap): ByteArray {
        for (quality in JPEG_QUALITIES) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw IllegalStateException("The profile avatar could not be encoded.")
            }
            val encoded = output.toByteArray()
            if (encoded.size <= DeviceProfileRules.MAX_AVATAR_BYTES) {
                DeviceProfileRules.validateAvatar(encoded)
                return encoded
            }
        }
        throw IllegalArgumentException("The selected image cannot be compressed within the profile size limit.")
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_DECODE_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    private fun nullableBytesEqual(first: ByteArray?, second: ByteArray?): Boolean =
        when {
            first == null -> second == null
            second == null -> false
            else -> first.contentEquals(second)
        }

    private fun LocalDeviceProfileEntity.toDomain() = LocalDeviceProfile(
        deviceNameOverride = deviceNameOverride,
        profileName = profileName,
        avatarBytes = avatarBytes?.copyOf(),
        revision = profileRevision,
        shareWithTrustedPeers = shareWithTrustedPeers,
    )

    private fun LocalDeviceProfile.toEntity() = LocalDeviceProfileEntity(
        deviceNameOverride = deviceNameOverride,
        profileName = profileName,
        avatarBytes = avatarBytes?.copyOf(),
        profileRevision = revision,
        shareWithTrustedPeers = shareWithTrustedPeers,
    )

    private companion object {
        const val OUTPUT_DIMENSION = 256
        const val MAX_DECODE_DIMENSION = 512
        const val MAX_SOURCE_DIMENSION = 16_384
        const val MAX_SOURCE_PIXELS = 100_000_000L
        val JPEG_QUALITIES = listOf(82, 76, 70, 64, 58)
    }
}
