package com.peyvand.app.presentation.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.connection.PeerConnectionRepository

class PeerConnectionViewModelFactory(
    private val repository: PeerConnectionRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(PeerConnectionViewModel::class.java)) {
            throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
        }
        return PeerConnectionViewModel(repository) as T
    }
}
