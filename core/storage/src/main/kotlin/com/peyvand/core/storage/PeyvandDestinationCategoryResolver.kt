package com.peyvand.core.storage

import java.util.Locale

internal enum class PeyvandMediaCollection { IMAGES, VIDEOS, AUDIO, DOWNLOADS }

internal data class PeyvandDestinationCategory(
    /** Full path relative to primary shared storage, including Android's standard media root. */
    val relativeDirectory: String,
    val collection: PeyvandMediaCollection,
)

/**
 * MIME metadata takes precedence. Extensions are a fallback only for generic MIME values.
 * Voice-vs-music is ambiguous for common containers, so uncertain audio is never mislabeled.
 */
internal object PeyvandDestinationCategoryResolver {
    fun resolve(displayName: String, mimeType: String): PeyvandDestinationCategory {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeType.trim().lowercase(Locale.ROOT)
        val genericMime = mime.isBlank() || mime in GENERIC_MIME_TYPES

        if (mime == APK_MIME_TYPE || (genericMime && extension in APP_EXTENSIONS)) {
            return category("Download/PEYVAND/Apps", PeyvandMediaCollection.DOWNLOADS)
        }
        if (mime.startsWith("image/")) {
            return if (mime == "image/svg+xml") {
                category("Download/PEYVAND/Documents", PeyvandMediaCollection.DOWNLOADS)
            } else category("Pictures/PEYVAND/Images", PeyvandMediaCollection.IMAGES)
        }
        if (mime.startsWith("video/")) {
            return category("Movies/PEYVAND/Videos", PeyvandMediaCollection.VIDEOS)
        }
        if (mime.startsWith("audio/")) return audioCategory(extension, mime)
        if (mime.startsWith("text/") || mime in DOCUMENT_MIME_TYPES) {
            return category("Download/PEYVAND/Documents", PeyvandMediaCollection.DOWNLOADS)
        }

        if (genericMime) {
            when {
                extension in APP_EXTENSIONS ->
                    return category("Download/PEYVAND/Apps", PeyvandMediaCollection.DOWNLOADS)
                extension in IMAGE_EXTENSIONS ->
                    return category("Pictures/PEYVAND/Images", PeyvandMediaCollection.IMAGES)
                extension in VIDEO_EXTENSIONS ->
                    return category("Movies/PEYVAND/Videos", PeyvandMediaCollection.VIDEOS)
                extension in AUDIO_EXTENSIONS ->
                    return audioCategory(extension, mime)
                extension in DOCUMENT_EXTENSIONS ->
                    return category("Download/PEYVAND/Documents", PeyvandMediaCollection.DOWNLOADS)
            }
        }
        return category("Download/PEYVAND/Other", PeyvandMediaCollection.DOWNLOADS)
    }

    private fun audioCategory(extension: String, mime: String): PeyvandDestinationCategory {
        val directory = when {
            mime in VOICE_MIME_TYPES || extension in VOICE_EXTENSIONS ->
                "Music/PEYVAND/Audio/Voice"
            extension in MUSIC_EXTENSIONS ->
                "Music/PEYVAND/Audio/Music"
            else -> "Music/PEYVAND/Audio/Other"
        }
        return category(directory, PeyvandMediaCollection.AUDIO)
    }

    private fun category(directory: String, collection: PeyvandMediaCollection) =
        PeyvandDestinationCategory(directory, collection)

    private val GENERIC_MIME_TYPES = setOf(
        "application/octet-stream",
        "application/unknown",
        "binary/octet-stream",
    )
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private val APP_EXTENSIONS = setOf("apk", "apks", "xapk", "aab")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "tif", "tiff")
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "mov", "avi", "webm", "3gp", "3gpp", "mpeg", "mpg")
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "opus", "aac", "m4a", "m4b", "amr", "3gp", "3gpp", "3g2", "wma", "wav", "aiff", "aif", "mid", "midi")
    private val VOICE_EXTENSIONS = setOf("amr", "3gp", "3gpp", "3g2")
    private val MUSIC_EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "aac", "wma", "mid", "midi")
    private val VOICE_MIME_TYPES = setOf("audio/amr", "audio/3gpp", "audio/3gpp2")
    private val DOCUMENT_EXTENSIONS = setOf("pdf", "txt", "md", "csv", "rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub")
    private val DOCUMENT_MIME_TYPES = setOf(
        "application/pdf",
        "application/rtf",
        "application/msword",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/epub+zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}
