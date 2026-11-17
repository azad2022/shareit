package com.peyvand.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.domain.chat.TrustedPeerRepository
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.calling.VoiceCallOrchestrator
import com.peyvand.app.calling.VoiceCallTelecomServiceClient

class ChatViewModelFactory(
    private val chatRepository: ChatRepository,
    private val trustedPeerRepository: TrustedPeerRepository,
    private val connectivityRepository: PeerConnectivityRepository,
    private val voiceCallOrchestrator: VoiceCallOrchestrator,
    private val voiceCallTelecomServiceClient: VoiceCallTelecomServiceClient,
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
            voiceCallOrchestrator = voiceCallOrchestrator,
            voiceCallTelecomServiceClient = voiceCallTelecomServiceClient,
        ) as T
    }
}
