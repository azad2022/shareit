package com.peyvand.app.presentation.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peyvand.core.domain.files.ShareCatalogRepository
import com.peyvand.core.domain.files.ShareCategory
import com.peyvand.core.domain.files.ShareItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareSelectionUiState(
    val category: ShareCategory = ShareCategory.PHOTOS,
    val searchQuery: String = "",
    val items: List<ShareItem> = emptyList(),
    val selectedItems: Map<String, ShareItem> = emptyMap(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreItems: Boolean = false,
    val hasError: Boolean = false,
    val hasMoreError: Boolean = false,
)

class ShareSelectionViewModel(
    private val repository: ShareCatalogRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ShareSelectionUiState())
    val state = mutableState.asStateFlow()

    private var loadJob: Job? = null

    fun selectCategory(category: ShareCategory) {
        loadJob?.cancel()
        mutableState.update {
            it.copy(
                category = category,
                searchQuery = "",
                items = emptyList(),
                isLoading = false,
                isLoadingMore = false,
                hasMoreItems = false,
                hasError = false,
                hasMoreError = false,
            )
        }
    }

    fun updateSearchQuery(query: String) {
        if (mutableState.value.searchQuery == query) return
        loadJob?.cancel()
        mutableState.update {
            it.copy(
                searchQuery = query,
                items = emptyList(),
                isLoading = false,
                isLoadingMore = false,
                hasMoreItems = false,
                hasError = false,
                hasMoreError = false,
            )
        }
    }

    fun loadItems() {
        loadPage(offset = 0, append = false)
    }

    fun loadNextPage() {
        val snapshot = mutableState.value
        if (!snapshot.hasMoreItems || snapshot.isLoading || snapshot.isLoadingMore ||
            snapshot.category == ShareCategory.FILES
        ) {
            return
        }
        loadPage(offset = snapshot.items.size, append = true)
    }

    private fun loadPage(offset: Int, append: Boolean) {
        val snapshot = mutableState.value
        if (snapshot.category == ShareCategory.FILES) return

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { current ->
                if (append) {
                    current.copy(isLoadingMore = true, hasMoreError = false)
                } else {
                    current.copy(
                        items = emptyList(),
                        isLoading = true,
                        isLoadingMore = false,
                        hasMoreItems = false,
                        hasError = false,
                        hasMoreError = false,
                    )
                }
            }
            try {
                val page = repository.loadItems(
                    category = snapshot.category,
                    searchQuery = snapshot.searchQuery,
                    offset = offset,
                    limit = PAGE_SIZE,
                )
                mutableState.update { current ->
                    if (current.category == snapshot.category &&
                        current.searchQuery == snapshot.searchQuery
                    ) {
                        val updatedItems = if (append) {
                            (current.items + page.items).distinctBy(ShareItem::id)
                        } else {
                            page.items
                        }
                        current.copy(
                            items = updatedItems,
                            isLoading = false,
                            isLoadingMore = false,
                            hasMoreItems = page.hasMore,
                            hasError = false,
                            hasMoreError = false,
                        )
                    } else {
                        current
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update { current ->
                    if (current.category == snapshot.category &&
                        current.searchQuery == snapshot.searchQuery
                    ) {
                        if (append) {
                            current.copy(isLoadingMore = false, hasMoreError = true)
                        } else {
                            current.copy(
                                items = emptyList(),
                                isLoading = false,
                                hasMoreItems = false,
                                hasError = true,
                            )
                        }
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun toggleSelection(item: ShareItem) {
        mutableState.update { current ->
            val updated = current.selectedItems.toMutableMap()
            if (updated.containsKey(item.id)) {
                updated.remove(item.id)
            } else {
                updated[item.id] = item
            }
            current.copy(selectedItems = updated)
        }
    }

    fun addDocuments(uriStrings: List<String>) {
        if (uriStrings.isEmpty()) return
        viewModelScope.launch {
            try {
                val documents = repository.resolveDocuments(uriStrings)
                mutableState.update { current ->
                    val updated = current.selectedItems.toMutableMap()
                    documents.forEach { document -> updated[document.id] = document }
                    current.copy(
                        items = if (current.category == ShareCategory.FILES) {
                            documents
                        } else {
                            current.items
                        },
                        selectedItems = updated,
                        hasError = documents.isEmpty(),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update { it.copy(hasError = true) }
            }
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selectedItems = emptyMap()) }
    }

    private companion object {
        const val PAGE_SIZE = 80
    }
}
