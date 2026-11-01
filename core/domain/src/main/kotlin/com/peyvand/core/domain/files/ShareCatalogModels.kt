package com.peyvand.core.domain.files

/**
 * User-visible content groups available to the local sender file browser.
 * FILES is populated only from explicit Storage Access Framework selections.
 */
enum class ShareCategory {
    MUSIC,
    PHOTOS,
    APPLICATIONS,
    VIDEOS,
    FILES,
}

sealed interface ShareSource {
    data class ContentUri(val value: String) : ShareSource
    data class InstalledPackage(val packageName: String) : ShareSource
}

data class ShareItem(
    val id: String,
    val category: ShareCategory,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long?,
    val source: ShareSource,
)

data class ShareCatalogPage(
    val items: List<ShareItem>,
    val hasMore: Boolean,
)

interface ShareCatalogRepository {
    suspend fun loadItems(
        category: ShareCategory,
        searchQuery: String,
        offset: Int,
        limit: Int,
    ): ShareCatalogPage

    /** Resolve metadata for documents explicitly selected through ACTION_OPEN_DOCUMENT. */
    suspend fun resolveDocuments(uriStrings: List<String>): List<ShareItem>
}

enum class SharePermission {
    READ_EXTERNAL_STORAGE,
    READ_MEDIA_AUDIO,
    READ_MEDIA_IMAGES,
    READ_MEDIA_VIDEO,
    READ_MEDIA_VISUAL_USER_SELECTED,
}

/**
 * Pure Android-version policy for the minimum permission set used by the file browser.
 * Generic documents use SAF and installed-app discovery uses narrow package visibility.
 */
object SharePermissionPolicy {
    fun permissionsFor(category: ShareCategory, apiLevel: Int): List<SharePermission> =
        when (category) {
            ShareCategory.MUSIC -> if (apiLevel >= 33) {
                listOf(SharePermission.READ_MEDIA_AUDIO)
            } else {
                listOf(SharePermission.READ_EXTERNAL_STORAGE)
            }

            ShareCategory.PHOTOS -> visualMediaPermissions(
                apiLevel = apiLevel,
                mediaPermission = SharePermission.READ_MEDIA_IMAGES,
            )

            ShareCategory.VIDEOS -> visualMediaPermissions(
                apiLevel = apiLevel,
                mediaPermission = SharePermission.READ_MEDIA_VIDEO,
            )

            ShareCategory.APPLICATIONS,
            ShareCategory.FILES,
            -> emptyList()
        }

    fun canRead(
        category: ShareCategory,
        apiLevel: Int,
        granted: Set<SharePermission>,
    ): Boolean {
        val required = permissionsFor(category, apiLevel)
        if (required.isEmpty()) return true

        if (apiLevel >= 34 &&
            (category == ShareCategory.PHOTOS || category == ShareCategory.VIDEOS)
        ) {
            return required.any(granted::contains)
        }

        return required.all(granted::contains)
    }

    private fun visualMediaPermissions(
        apiLevel: Int,
        mediaPermission: SharePermission,
    ): List<SharePermission> = when {
        apiLevel >= 34 -> listOf(
            mediaPermission,
            SharePermission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        apiLevel >= 33 -> listOf(mediaPermission)
        else -> listOf(SharePermission.READ_EXTERNAL_STORAGE)
    }
}
