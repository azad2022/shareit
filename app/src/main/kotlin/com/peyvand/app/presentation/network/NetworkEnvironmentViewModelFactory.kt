package com.peyvand.app.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.network.NetworkEnvironmentRepository

class NetworkEnvironmentViewModelFactory(
    private val repository: NetworkEnvironmentRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(NetworkEnvironmentViewModel::class.java)) {
            throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
        }
        return NetworkEnvironmentViewModel(repository) as T
    }
}
