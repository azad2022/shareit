package com.peyvand.core.storage

import java.util.Locale

/**
 * Resolves a concrete MIME type suitable for protocol manifests and SAF destinations.
 *
 * Providers sometimes return wildcard or generic MIME values. Prefer a meaningful
 * provider/fallback type, but use the extension-derived type when all provider values
 * are generic. MIME parameters are removed because the transfer protocol stores a
 * media type, not a Content-Type header.
 */
internal object TransferMimeTypeResolver {
    private const val DEFAULT_MIME_TYPE = "application/octet-stream"

    private val mimeTypePattern = Regex(
        "^[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,64}$",
    )

    fun resolve(
        providerMimeType: String?,
        fallbackMimeType: String?,
        extensionMimeType: String?,
    ): String {
        val provider = normalize(providerMimeType)
        val fallback = normalize(fallbackMimeType)
        val extension = normalize(extensionMimeType)

        return sequenceOf(provider, fallback)
            .filterNotNull()
            .firstOrNull { it != DEFAULT_MIME_TYPE }
            ?: extension
            ?: provider
            ?: fallback
            ?: DEFAULT_MIME_TYPE
    }

    private fun normalize(value: String?): String? {
        val normalized = value
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return null
        val concrete = normalized.takeIf(mimeTypePattern::matches) ?: return null
        return when (concrete) {
            // Some OEM media providers still report legacy JPEG aliases. SAF
            // destination providers expect the registered image/jpeg media type.
            "image/jpg", "image/pjpeg", "image/x-jpeg" -> "image/jpeg"
            else -> concrete
        }
    }
}
