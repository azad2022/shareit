package com.peyvand.app.presentation.connection

import androidx.lifecycle.ViewModel
import com.peyvand.core.domain.connection.PeerConnectionRepository

class PeerConnectionViewModel(
    private val repository: PeerConnectionRepository,
) : ViewModel() {
    val state = repository.connection

    fun connectToPeer(peerId: String) = repository.connectToPeer(peerId)

    fun disconnect() = repository.disconnect()
}
