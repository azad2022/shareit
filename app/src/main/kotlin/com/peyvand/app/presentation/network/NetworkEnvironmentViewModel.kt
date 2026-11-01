package com.peyvand.app.presentation.network

import androidx.lifecycle.ViewModel
import com.peyvand.core.domain.network.NetworkEnvironmentRepository

class NetworkEnvironmentViewModel(
    repository: NetworkEnvironmentRepository,
) : ViewModel() {
    val state = repository.snapshot
}
