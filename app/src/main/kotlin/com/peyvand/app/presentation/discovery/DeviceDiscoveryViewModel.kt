package com.peyvand.app.presentation.discovery

import androidx.lifecycle.ViewModel
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository
import com.peyvand.core.domain.discovery.DiscoveryRole

class DeviceDiscoveryViewModel(
    private val repository: DeviceDiscoveryRepository,
) : ViewModel() {
    val state = repository.snapshot

    fun refreshAvailability() = repository.refreshAvailability()
    fun startDiscovery(role: DiscoveryRole) = repository.startDiscovery(role)
    fun stopDiscovery() = repository.stopDiscovery()
}
