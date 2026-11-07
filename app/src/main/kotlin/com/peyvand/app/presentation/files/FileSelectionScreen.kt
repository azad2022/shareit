package com.peyvand.app.presentation.files

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peyvand.app.R
import com.peyvand.core.domain.files.ShareCategory
import com.peyvand.core.domain.files.ShareItem
import com.peyvand.core.domain.files.ShareSource
import com.peyvand.core.domain.files.SharePermission
import com.peyvand.core.domain.files.SharePermissionPolicy

@SuppressLint("InlinedApi")
@Composable
fun FileSelectionScreen(
    viewModel: ShareSelectionViewModel,
    selectionMode: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasPermissionForCategory(context, state.category)) {
            permissionDenied = false
            viewModel.loadItems()
        } else {
            permissionDenied = true
        }
    }

    val documentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.addDocuments(uris.map(Uri::toString))
    }

    LaunchedEffect(state.category, state.searchQuery) {
        delay(250)
        if (state.category == ShareCategory.APPLICATIONS ||
            hasPermissionForCategory(context, state.category)
        ) {
            viewModel.loadItems()
        }
    }

    val categories = ShareCategory.entries
    val selectedItems = state.selectedItems.values.toList()
    val selectedBytes = selectedItems.mapNotNull(ShareItem::sizeBytes).sum()
    val unknownSizeCount = selectedItems.count { it.sizeBytes == null }
    val visibleItems = if (state.category == ShareCategory.FILES && state.searchQuery.isNotBlank()) {
        state.items.filter { it.displayName.contains(state.searchQuery, ignoreCase = true) }
    } else {
        state.items
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    if (selectionMode) R.string.file_selection_title
                    else R.string.file_manager_title,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (selectionMode) {
            Text(
                text = stringResource(R.string.file_selection_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(state.category),
        ) {
            categories.forEach { category ->
                Tab(
                    selected = state.category == category,
                    onClick = {
                        permissionDenied = false
                        viewModel.selectCategory(category)
                        when (category) {
                            ShareCategory.FILES -> documentsLauncher.launch(arrayOf("*/*"))
                            ShareCategory.APPLICATIONS -> Unit
                            else -> {
                                if (!hasPermissionForCategory(context, category)) {
                                    permissionLauncher.launch(platformPermissions(category))
                                }
                            }
                        }
                    },
                    text = {
                        Text(
                            text = stringResource(category.labelResource()),
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateSearchQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.file_search_label)) },
            placeholder = { Text(stringResource(R.string.file_search_hint)) },
        )

        if (hasPartialVisualMediaAccess(context, state.category)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.file_permission_partial),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(platformPermissions(state.category))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.file_permission_reselect_media))
                    }
                }
            }
        }

        if (state.category != ShareCategory.APPLICATIONS &&
            state.category != ShareCategory.FILES &&
            !hasPermissionForCategory(context, state.category)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (permissionDenied) R.string.file_permission_denied
                            else R.string.file_permission_explanation,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Button(
                        onClick = {
                            permissionLauncher.launch(platformPermissions(state.category))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.file_permission_action))
                    }
                }
            }
        } else if (state.category == ShareCategory.FILES && visibleItems.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.file_documents_picker_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { documentsLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.file_documents_picker_action))
                    }
                }
            }
        }

        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.hasError -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.file_catalog_error),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            visibleItems.isEmpty() &&
                (state.category == ShareCategory.APPLICATIONS ||
                    (state.category != ShareCategory.FILES &&
                        hasPermissionForCategory(context, state.category))) -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.file_catalog_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleItems, key = ShareItem::id) { item ->
                    ShareItemRow(
                        item = item,
                        selected = state.selectedItems.containsKey(item.id),
                        selectionMode = selectionMode,
                        onToggle = { viewModel.toggleSelection(item) },
                    )
                }
                if (state.hasMoreItems || state.isLoadingMore || state.hasMoreError) {
                    item(key = "load-more") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.hasMoreError) {
                                Text(
                                    text = stringResource(R.string.file_load_more_error),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedButton(
                                onClick = viewModel::loadNextPage,
                                enabled = !state.isLoadingMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            if (state.hasMoreError) {
                                                R.string.file_load_more_retry
                                            } else {
                                                R.string.file_load_more
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectionMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.file_selected_count,
                            selectedItems.size,
                            selectedItems.size,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            R.string.file_selected_size,
                            Formatter.formatFileSize(context, selectedBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (unknownSizeCount > 0) {
                        Text(
                            text = stringResource(R.string.file_selected_unknown_size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (selectedItems.size != 1 ||
                        selectedItems.singleOrNull()?.source !is ShareSource.ContentUri
                    ) {
                        Text(
                            text = stringResource(R.string.file_transfer_selection_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = viewModel::clearSelection,
                            enabled = selectedItems.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.file_selection_clear))
                        }
                        Button(
                            onClick = onContinue,
                            enabled = selectedItems.size == 1 &&
                                selectedItems.single().source is ShareSource.ContentUri,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.action_continue))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareItemRow(
    item: ShareItem,
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
) {
    val rowModifier = if (selectionMode) {
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = categoryGlyph(item.category),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
            Text(
                text = item.sizeBytes?.let { Formatter.formatFileSize(LocalContext.current, it) }
                    ?: stringResource(R.string.file_size_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun categoryGlyph(category: ShareCategory): String = when (category) {
    ShareCategory.MUSIC -> "♫"
    ShareCategory.PHOTOS -> "▧"
    ShareCategory.APPLICATIONS -> "▦"
    ShareCategory.VIDEOS -> "▶"
    ShareCategory.FILES -> "▤"
}

private fun ShareCategory.labelResource(): Int = when (this) {
    ShareCategory.MUSIC -> R.string.file_category_music
    ShareCategory.PHOTOS -> R.string.file_category_photos
    ShareCategory.APPLICATIONS -> R.string.file_category_apps
    ShareCategory.VIDEOS -> R.string.file_category_videos
    ShareCategory.FILES -> R.string.file_category_files
}

@SuppressLint("InlinedApi")
private fun platformPermissions(category: ShareCategory): Array<String> {
    if (Build.VERSION.SDK_INT >= 33) {
        return when (category) {
            ShareCategory.MUSIC -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            ShareCategory.PHOTOS -> if (Build.VERSION.SDK_INT >= 34) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            } else {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            }
            ShareCategory.VIDEOS -> if (Build.VERSION.SDK_INT >= 34) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            } else {
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            }
            else -> emptyArray()
        }
    }

    return when (category) {
        ShareCategory.MUSIC,
        ShareCategory.PHOTOS,
        ShareCategory.VIDEOS,
        -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        else -> emptyArray()
    }
}

@SuppressLint("InlinedApi")
private fun hasPartialVisualMediaAccess(
    context: Context,
    category: ShareCategory,
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false

    val fullPermission = when (category) {
        ShareCategory.PHOTOS -> Manifest.permission.READ_MEDIA_IMAGES
        ShareCategory.VIDEOS -> Manifest.permission.READ_MEDIA_VIDEO
        else -> return false
    }
    val fullAccessGranted = ContextCompat.checkSelfPermission(
        context,
        fullPermission,
    ) == PackageManager.PERMISSION_GRANTED
    val selectedAccessGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED

    return selectedAccessGranted && !fullAccessGranted
}

@SuppressLint("InlinedApi")
private fun hasPermissionForCategory(
    context: Context,
    category: ShareCategory,
): Boolean {
    val apiLevel = Build.VERSION.SDK_INT
    val permissions = platformPermissions(category)
    if (permissions.isEmpty()) return true

    val granted = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }.toSet()

    val policyPermissions = granted.mapNotNull { permission ->
        when (permission) {
            Manifest.permission.READ_EXTERNAL_STORAGE -> SharePermission.READ_EXTERNAL_STORAGE
            Manifest.permission.READ_MEDIA_AUDIO -> SharePermission.READ_MEDIA_AUDIO
            Manifest.permission.READ_MEDIA_IMAGES -> SharePermission.READ_MEDIA_IMAGES
            Manifest.permission.READ_MEDIA_VIDEO -> SharePermission.READ_MEDIA_VIDEO
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED ->
                SharePermission.READ_MEDIA_VISUAL_USER_SELECTED
            else -> null
        }
    }.toSet()

    return SharePermissionPolicy.canRead(category, apiLevel, policyPermissions)
}
