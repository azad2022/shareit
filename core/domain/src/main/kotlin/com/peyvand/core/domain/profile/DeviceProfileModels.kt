package com.peyvand.core.domain.profile

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow

/**
 * PEYVAND-local presentation profile.
 *
 * [deviceNameOverride] is an app-scoped device label. It never changes Android's
 * system device name, Wi-Fi Direct device name, Bluetooth name, or OS settings.
 * Profile name and avatar are optional and are shared only with authenticated peers.
 */
data class LocalDeviceProfile(
    val deviceNameOverride: String? = null,
    val profileName: String? = null,
    val avatarBytes: ByteArray? = null,
    val revision: Long = 0L,
    val shareWithTrustedPeers: Boolean = true,
) {
    val hasProfile: Boolean
        get() = profileName != null || avatarBytes != null
}

interface DeviceProfileRepository {
    fun observeLocalProfile(): Flow<LocalDeviceProfile>

    suspend fun getLocalProfile(): LocalDeviceProfile

    /** Imports, bounds, normalizes, center-crops, and re-encodes a selected image. */
    suspend fun importAvatar(contentUri: String): ByteArray

    /** Persists the complete profile as one revisioned update. */
    suspend fun saveLocalProfile(
        deviceNameOverride: String?,
        profileName: String?,
        avatarBytes: ByteArray?,
        shareWithTrustedPeers: Boolean,
    ): LocalDeviceProfile
}

/** Shared validation rules for local profile input and untrusted peer profile metadata. */
object DeviceProfileRules {
    const val MAX_DEVICE_NAME_CODE_POINTS = 32
    const val MAX_PROFILE_NAME_CODE_POINTS = 32
    const val MAX_NAME_UTF8_BYTES = 128
    const val MAX_AVATAR_BYTES = 32 * 1024

    fun normalizeDeviceName(value: String?): String? =
        normalizeLabel(value, MAX_DEVICE_NAME_CODE_POINTS)

    fun normalizeProfileName(value: String?): String? =
        normalizeLabel(value, MAX_PROFILE_NAME_CODE_POINTS)

    fun normalizeLabel(value: String?, maximumCodePoints: Int): String? {
        if (value == null) return null
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        if (normalized.isEmpty()) return null
        require(normalized.codePointCount(0, normalized.length) <= maximumCodePoints) {
            "The name exceeds the allowed number of Unicode code points."
        }
        require(normalized.toByteArray(StandardCharsets.UTF_8).size <= MAX_NAME_UTF8_BYTES) {
            "The UTF-8 encoded name exceeds the protocol limit."
        }

        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            require(!Character.isISOControl(codePoint)) {
                "Control characters are not allowed in device or profile names."
            }
            require(codePoint !in BIDI_CONTROL_CODE_POINTS) {
                "Bidirectional override and isolate controls are not allowed in names."
            }
            offset += Character.charCount(codePoint)
        }
        return normalized
    }

    fun validateAvatar(bytes: ByteArray?) {
        if (bytes == null) return
        require(bytes.size in MIN_JPEG_BYTES..MAX_AVATAR_BYTES) {
            "Profile avatar size is outside the supported limit."
        }
        require(
            bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[bytes.lastIndex - 1] == 0xFF.toByte() &&
                bytes[bytes.lastIndex] == 0xD9.toByte(),
        ) {
            "Profile avatar must be a complete JPEG image."
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    fun defaultDeviceName(manufacturer: String?, model: String?): String {
        val safeManufacturer = manufacturer
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
        val safeModel = model?.trim()?.takeIf(String::isNotEmpty)
        val candidate = listOfNotNull(safeManufacturer, safeModel)
            .distinct()
            .joinToString(" ")
        return normalizeDeviceName(candidate.takeCodePoints(MAX_DEVICE_NAME_CODE_POINTS))
            ?: "Android device"
    }

    private fun String.takeCodePoints(maximum: Int): String {
        val count = codePointCount(0, length)
        if (count <= maximum) return this
        return substring(0, offsetByCodePoints(0, maximum))
    }

    private const val MIN_JPEG_BYTES = 4
    private val BIDI_CONTROL_CODE_POINTS = setOf(
        0x061C,
        0x200E,
        0x200F,
        0x202A,
        0x202B,
        0x202C,
        0x202D,
        0x202E,
        0x2066,
        0x2067,
        0x2068,
        0x2069,
    )
}
