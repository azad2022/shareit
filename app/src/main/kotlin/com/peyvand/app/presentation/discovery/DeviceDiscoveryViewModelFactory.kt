package com.peyvand.app.presentation.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository

class DeviceDiscoveryViewModelFactory(
    private val repository: DeviceDiscoveryRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(DeviceDiscoveryViewModel::class.java)) {
            throw IllegalArgumentException(
                "Unsupported ViewModel type: ${modelClass.name}",
            )
        }
        return DeviceDiscoveryViewModel(repository) as T
    }
}
