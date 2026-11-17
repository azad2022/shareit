package com.peyvand.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.profile.DeviceProfileRepository

class DeviceProfileViewModelFactory(
    private val repository: DeviceProfileRepository,
    private val defaultDeviceName: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(DeviceProfileViewModel::class.java)) {
            throw IllegalArgumentException("Unsupported ViewModel type: " + modelClass.name)
        }
        return DeviceProfileViewModel(
            repository = repository,
            defaultDeviceName = defaultDeviceName,
        ) as T
    }
}
