package com.peyvand.app.presentation.discovery

import androidx.lifecycle.ViewModel
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository

class DeviceDiscoveryViewModel(
    private val repository: DeviceDiscoveryRepository,
) : ViewModel() {
    val state = repository.snapshot

    fun refreshAvailability() = repository.refreshAvailability()
    fun startDiscovery() = repository.startDiscovery()
    fun stopDiscovery() = repository.stopDiscovery()
}
