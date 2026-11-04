package com.peyvand.app.presentation.connection

import androidx.lifecycle.ViewModel
import com.peyvand.core.domain.connection.PeerConnectivityRepository

class PeerConnectionViewModel(
    private val repository: PeerConnectivityRepository,
) : ViewModel() {
    val state = repository.connection
    val tcpChannel = repository.tcpChannel

    fun connectToPeer(peerId: String) = repository.connectToPeer(peerId)

    fun disconnect() = repository.disconnect()
}
