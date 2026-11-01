package com.peyvand.app.presentation.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.files.ShareCatalogRepository

class ShareSelectionViewModelFactory(
    private val repository: ShareCatalogRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(ShareSelectionViewModel::class.java)) {
            throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
        }
        return ShareSelectionViewModel(repository) as T
    }
}
