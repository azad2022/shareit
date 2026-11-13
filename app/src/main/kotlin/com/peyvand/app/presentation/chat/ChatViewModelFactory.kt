package com.peyvand.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.domain.chat.TrustedPeerRepository
import com.peyvand.core.domain.connection.PeerConnectivityRepository

class ChatViewModelFactory(
    private val chatRepository: ChatRepository,
    private val trustedPeerRepository: TrustedPeerRepository,
    private val connectivityRepository: PeerConnectivityRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            throw IllegalArgumentException("Unsupported ViewModel type: ${modelClass.name}")
        }
        return ChatViewModel(
            chatRepository = chatRepository,
            trustedPeerRepository = trustedPeerRepository,
            connectivityRepository = connectivityRepository,
        ) as T
    }
}
