package com.peyvand.core.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.peyvand.core.domain.files.ShareCatalogPage
import com.peyvand.core.domain.files.ShareCatalogRepository
import com.peyvand.core.domain.files.ShareCategory
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Android adapter for the user-authorized media catalog, SAF document URIs,
 * and launcher-visible installed applications.
 *
 * It deliberately does not use MANAGE_EXTERNAL_STORAGE or QUERY_ALL_PACKAGES.
 */
class AndroidShareCatalogRepository(
    context: Context,
) : ShareCatalogRepository {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val packageManager: PackageManager = appContext.packageManager

    override suspend fun loadItems(
        category: ShareCategory,
        searchQuery: String,
        offset: Int,
        limit: Int,
    ): ShareCatalogPage = withContext(Dispatchers.IO) {
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit is outside the supported range" }

        when (category) {
            ShareCategory.MUSIC,
            ShareCategory.PHOTOS,
            ShareCategory.VIDEOS,
            -> loadMediaItems(category, searchQuery, offset, limit)

            ShareCategory.APPLICATIONS -> {
                val allItems = loadLaunchableApplications(searchQuery)
                val pageItems = allItems.drop(offset).take(limit)
                ShareCatalogPage(
                    items = pageItems,
                    hasMore = offset + pageItems.size < allItems.size,
                )
            }

            ShareCategory.FILES -> ShareCatalogPage(emptyList(), hasMore = false)
        }
    }

    override suspend fun resolveDocuments(uriStrings: List<String>): List<ShareItem> =
        withContext(Dispatchers.IO) {
            uriStrings.distinct().mapNotNull(::resolveDocument)
        }

    private fun loadMediaItems(
        category: ShareCategory,
        searchQuery: String,
        offset: Int,
        limit: Int,
    ): ShareCatalogPage {
        val collection = when (category) {
            ShareCategory.MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            ShareCategory.PHOTOS -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ShareCategory.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> return ShareCatalogPage(emptyList(), hasMore = false)
        }
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val normalizedQuery = searchQuery.trim()
        val queryArguments = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, BaseColumns._ID),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            if (normalizedQuery.isNotEmpty()) {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf("%$normalizedQuery%"),
                )
            }
        }
        val items = ArrayList<ShareItem>(limit + 1)

        contentResolver.query(
            collection,
            projection,
            queryArguments,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

            while (items.size < limit + 1 && cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex)?.takeIf(String::isNotBlank)
                    ?: continue
                val mediaId = cursor.getLong(idIndex)
                val size = sizeIndex.takeIf { it >= 0 }
                    ?.let(cursor::getLong)
                    ?.takeIf { it >= 0L }
                val mimeType = resolveMimeType(
                    displayName,
                    mimeIndex.takeIf { it >= 0 }?.let(cursor::getString),
                )
                val modifiedAtMillis = modifiedIndex.takeIf { it >= 0 }
                    ?.let(cursor::getLong)
                    ?.takeIf { it > 0L }
                    ?.times(1_000L)
                val itemUri = ContentUris.withAppendedId(collection, mediaId)

                items += ShareItem(
                    id = "media:$itemUri",
                    category = category,
                    displayName = displayName,
                    mimeType = mimeType,
                    sizeBytes = size,
                    modifiedAtEpochMillis = modifiedAtMillis,
                    source = ShareSource.ContentUri(itemUri.toString()),
                )
            }
        }

        val hasMore = items.size > limit
        return ShareCatalogPage(
            items = if (hasMore) items.take(limit) else items,
            hasMore = hasMore,
        )
    }

    @Suppress("DEPRECATION")
    private fun loadLaunchableApplications(searchQuery: String): List<ShareItem> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val visibleActivities = packageManager.queryIntentActivities(launcherIntent, 0)
        val itemsByPackage = linkedMapOf<String, ShareItem>()
        val normalizedQuery = searchQuery.trim().lowercase(Locale.ROOT)

        visibleActivities.forEach { resolveInfo ->
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@forEach
            val packageName = appInfo.packageName
            if (packageName == appContext.packageName || itemsByPackage.containsKey(packageName)) {
                return@forEach
            }

            val isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val isUpdatedSystemApp = appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            if (isSystemApp && !isUpdatedSystemApp) return@forEach

            val label = packageManager.getApplicationLabel(appInfo)
                .toString()
                .takeIf(String::isNotBlank)
                ?: packageName
            if (normalizedQuery.isNotEmpty() &&
                !label.lowercase(Locale.ROOT).contains(normalizedQuery) &&
                !packageName.lowercase(Locale.ROOT).contains(normalizedQuery)
            ) {
                return@forEach
            }

            val apkPaths = buildList {
                appInfo.sourceDir?.let(::add)
                appInfo.splitSourceDirs?.forEach(::add)
            }
            val packageBytes = apkPaths
                .asSequence()
                .map(::File)
                .map(File::length)
                .filter { it > 0L }
                .sum()
                .takeIf { it > 0L }

            itemsByPackage[packageName] = ShareItem(
                id = "app:$packageName",
                category = ShareCategory.APPLICATIONS,
                displayName = label,
                mimeType = APK_MIME_TYPE,
                sizeBytes = packageBytes,
                modifiedAtEpochMillis = null,
                source = ShareSource.InstalledPackage(packageName),
            )
        }

        return itemsByPackage.values.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    private fun resolveDocument(uriString: String): ShareItem? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // The active URI grant can still be used for this process/session.
        } catch (_: UnsupportedOperationException) {
            // Some providers do not support persistable grants.
        }

        val metadata = runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null

                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val displayName = nameIndex.takeIf { it >= 0 }
                    ?.let(cursor::getString)
                    ?.takeIf(String::isNotBlank)
                    ?: return@use null
                val size = sizeIndex.takeIf { it >= 0 }
                    ?.let(cursor::getLong)
                    ?.takeIf { it >= 0L }
                displayName to size
            }
        }.getOrNull() ?: return null
        val (displayName, size) = metadata
        val providerType = runCatching { contentResolver.getType(uri) }.getOrNull()

        return ShareItem(
            id = "document:$uri",
            category = ShareCategory.FILES,
            displayName = displayName,
            mimeType = resolveMimeType(displayName, providerType),
            sizeBytes = size,
            modifiedAtEpochMillis = null,
            source = ShareSource.ContentUri(uri.toString()),
        )
    }

    private fun resolveMimeType(displayName: String, providerType: String? = null): String {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
        val extensionType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return TransferMimeTypeResolver.resolve(
            providerMimeType = providerType,
            fallbackMimeType = null,
            extensionMimeType = extensionType,
        )
    }

    private companion object {
        const val MAX_PAGE_SIZE = 250
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val BINARY_MIME_TYPE = "application/octet-stream"
    }
}
