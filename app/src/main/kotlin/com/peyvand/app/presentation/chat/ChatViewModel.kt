package com.peyvand.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peyvand.core.domain.chat.ChatMessage
import com.peyvand.core.domain.chat.ChatOutboxLimitExceededException
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.domain.chat.PeerIdentityId
import com.peyvand.core.domain.chat.TrustedPeerIdentity
import com.peyvand.core.domain.chat.TrustedPeerRepository
import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.transfer.chat.ChatSessionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatConversationPreview(
    val peer: TrustedPeerIdentity,
    val latestMessage: ChatMessage?,
    val pendingOutboxCount: Int,
)

data class ChatHomeUiState(
    val conversations: List<ChatConversationPreview> = emptyList(),
    val connectedPeerFingerprint: String? = null,
    val activeChatPeerFingerprint: String? = null,
    val isLoading: Boolean = true,
)

data class ChatConversationUiState(
    val peer: TrustedPeerIdentity? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingOutboxCount: Int = 0,
    val isConnected: Boolean = false,
    val isSyncChannelReady: Boolean = false,
    val isConnectedToDifferentPeer: Boolean = false,
    val notice: ChatUiNotice? = null,
)

enum class ChatUiNotice {
    MESSAGE_SAVED_WAITING_FOR_DIRECT_CONNECTION,
    MESSAGE_STORAGE_FAILED,
    MESSAGE_QUEUE_FULL,
    SECURE_SESSION_UNAVAILABLE,
    PEER_IDENTITY_CHANGED,
}

sealed interface ChatSendResult {
    data object Saved : ChatSendResult
    data object NotSaved : ChatSendResult
    data object NoConversationSelected : ChatSendResult
}

/** Presentation coordinator for local chat. It owns no socket, cryptographic key or database transaction. */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val trustedPeerRepository: TrustedPeerRepository,
    private val connectivityRepository: PeerConnectivityRepository,
) : ViewModel() {

    private val trustedPeers: StateFlow<List<TrustedPeerIdentity>> =
        trustedPeerRepository.observeTrustedPeers().stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    // Declared before any derived state that subscribes to this flow.
    private val activeCoordinatorPeerState = MutableStateFlow<PeerIdentityId?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val conversationPreviews = trustedPeers.flatMapLatest { peers ->
        if (peers.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                peers.map { peer ->
                    combine(
                        chatRepository.observeRecentMessages(peer.identityId, limit = 1),
                        chatRepository.observePendingOutboxCount(peer.identityId),
                    ) { messages, pending ->
                        ChatConversationPreview(
                            peer = peer,
                            latestMessage = messages.lastOrNull(),
                            pendingOutboxCount = pending,
                        )
                    }
                },
            ) { previews -> previews.toList() }
                .map { previews ->
                    previews.sortedByDescending { preview ->
                        preview.latestMessage?.createdAtEpochMillis
                            ?: preview.peer.lastAuthenticatedAtEpochMillis
                    }
                }
        }
    }

    val homeUiState: StateFlow<ChatHomeUiState> = combine(
        conversationPreviews,
        connectivityRepository.secureSession,
        activeCoordinatorPeerState,
    ) { previews, session, coordinatorPeer ->
        ChatHomeUiState(
            conversations = previews,
            connectedPeerFingerprint = if (session.status == SecureSessionStatus.AUTHENTICATED) {
                session.peerIdentityId
            } else {
                null
            },
            activeChatPeerFingerprint = coordinatorPeer?.sha256Fingerprint,
            isLoading = false,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        ChatHomeUiState(),
    )

    private val selectedPeer = MutableStateFlow<PeerIdentityId?>(null)
    private val notice = MutableStateFlow<ChatUiNotice?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversationUiState: StateFlow<ChatConversationUiState> = selectedPeer
        .flatMapLatest { identityId ->
            if (identityId == null) {
                flowOf(ChatConversationUiState())
            } else {
                combine(
                    trustedPeers,
                    chatRepository.observeRecentMessages(identityId, limit = 100),
                    chatRepository.observePendingOutboxCount(identityId),
                    connectivityRepository.secureSession,
                    notice,
                    activeCoordinatorPeerState,
                ) { peers, messages, pending, session, currentNotice, coordinatorPeer ->
                    val peer = peers.firstOrNull { it.identityId == identityId }
                    val connectedPeer = session.peerIdentityId?.let {
                        runCatching { PeerIdentityId.fromSha256Fingerprint(it) }.getOrNull()
                    }
                    ChatConversationUiState(
                        peer = peer,
                        messages = messages.map { message ->
                            if (
                                session.status == SecureSessionStatus.AUTHENTICATED &&
                                connectedPeer == identityId &&
                                coordinatorPeer == identityId &&
                                message.direction == com.peyvand.core.domain.chat.ChatMessageDirection.OUTGOING &&
                                message.deliveryState ==
                                    com.peyvand.core.domain.chat.ChatMessageDeliveryState.WAITING_FOR_CONNECTION
                            ) {
                                message.copy(
                                    deliveryState =
                                        com.peyvand.core.domain.chat.ChatMessageDeliveryState.SYNCHRONIZING,
                                )
                            } else {
                                message
                            }
                        },
                        pendingOutboxCount = pending,
                        isConnected = session.status == SecureSessionStatus.AUTHENTICATED &&
                            connectedPeer == identityId,
                        isSyncChannelReady = coordinatorPeer == identityId,
                        isConnectedToDifferentPeer =
                            session.status == SecureSessionStatus.AUTHENTICATED &&
                                connectedPeer != null && connectedPeer != identityId,
                        notice = currentNotice,
                    )
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            ChatConversationUiState(),
        )

    private val sessionLock = Any()
    private var activeCoordinator: ChatSessionCoordinator? = null
    private var activeCoordinatorPeer: PeerIdentityId? = null

    init {
        viewModelScope.launch {
            connectivityRepository.secureSession.collect { snapshot ->
                if (snapshot.status != SecureSessionStatus.AUTHENTICATED ||
                    snapshot.peerIdentityId.isNullOrBlank()
                ) {
                    closeActiveCoordinator()
                    return@collect
                }

                val authenticatedId = runCatching {
                    PeerIdentityId.fromSha256Fingerprint(snapshot.peerIdentityId)
                }.getOrNull() ?: run {
                    closeActiveCoordinator()
                    notice.value = ChatUiNotice.PEER_IDENTITY_CHANGED
                    return@collect
                }

                val coordinatorPeer = synchronized(sessionLock) { activeCoordinatorPeer }
                if (coordinatorPeer != null && coordinatorPeer != authenticatedId) {
                    closeActiveCoordinator()
                }

                if (selectedPeer.value == authenticatedId) {
                    ensureCoordinatorFor(authenticatedId)
                }
            }
        }
    }

    fun openConversation(identityId: PeerIdentityId) {
        selectedPeer.value = identityId
        notice.value = null
        ensureCoordinatorFor(identityId)
    }

    /** Called after bilateral SAS, pinned mTLS and persistent identity proof have completed. */
    fun openAuthenticatedConversation(): PeerIdentityId? {
        val session = connectivityRepository.secureSession.value
        if (session.status != SecureSessionStatus.AUTHENTICATED) return null
        val fingerprint = session.peerIdentityId ?: return null
        val identityId = runCatching {
            PeerIdentityId.fromSha256Fingerprint(fingerprint)
        }.getOrNull() ?: return null
        openConversation(identityId)
        return identityId
    }

    fun clearConversationSelection() {
        selectedPeer.value = null
        notice.value = null
    }

    /** Enqueues durably first. A socket write is never interpreted as delivery. */
    suspend fun sendMessage(body: String): ChatSendResult {
        val identityId = selectedPeer.value ?: return ChatSendResult.NoConversationSelected
        return try {
            val message = chatRepository.enqueueOutgoingMessage(
                peerIdentityId = identityId,
                body = body,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
            val coordinator = synchronized(sessionLock) {
                activeCoordinator.takeIf { activeCoordinatorPeer == identityId }
            }
            if (coordinator == null) {
                notice.value = ChatUiNotice.MESSAGE_SAVED_WAITING_FOR_DIRECT_CONNECTION
            } else {
                try {
                    coordinator.sendPersistedMessage(message)
                    notice.value = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    notice.value = ChatUiNotice.MESSAGE_SAVED_WAITING_FOR_DIRECT_CONNECTION
                }
            }
            ChatSendResult.Saved
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            notice.value = if (exception is ChatOutboxLimitExceededException) {
                ChatUiNotice.MESSAGE_QUEUE_FULL
            } else {
                ChatUiNotice.MESSAGE_STORAGE_FAILED
            }
            ChatSendResult.NotSaved
        }
    }

    fun disconnectCurrentPeer() {
        closeActiveCoordinator()
        connectivityRepository.disconnect()
    }

    private fun ensureCoordinatorFor(identityId: PeerIdentityId) {
        val session = connectivityRepository.secureSession.value
        if (session.status != SecureSessionStatus.AUTHENTICATED ||
            session.peerIdentityId != identityId.sha256Fingerprint
        ) return

        synchronized(sessionLock) {
            if (activeCoordinatorPeer == identityId && activeCoordinator != null) {
                return
            }
            // A logical CHAT lease belongs to exactly one authenticated peer session.
            // Release a stale child before claiming the replacement session's channel.
            activeCoordinator?.close()
            activeCoordinator = null
            activeCoordinatorPeer = null
            activeCoordinatorPeerState.value = null

            val channel = connectivityRepository.claimAuthenticatedChannel(
                AuthenticatedPeerChannelPurpose.CHAT,
            ) ?: run {
                notice.value = ChatUiNotice.SECURE_SESSION_UNAVAILABLE
                return
            }

            lateinit var created: ChatSessionCoordinator
            created = ChatSessionCoordinator(
                scope = viewModelScope,
                channel = channel,
                peerIdentityId = identityId,
                repository = chatRepository,
                onFailure = {
                    synchronized(sessionLock) {
                        if (activeCoordinator === created) {
                            activeCoordinator = null
                            activeCoordinatorPeer = null
                            activeCoordinatorPeerState.value = null
                        }
                    }
                    notice.value = ChatUiNotice.MESSAGE_SAVED_WAITING_FOR_DIRECT_CONNECTION
                    connectivityRepository.disconnect()
                },
            )
            activeCoordinator = created
            activeCoordinatorPeer = identityId
            activeCoordinatorPeerState.value = identityId
            created.start()
        }
    }

    private fun closeActiveCoordinator() {
        synchronized(sessionLock) {
            activeCoordinator?.close()
            activeCoordinator = null
            activeCoordinatorPeer = null
            activeCoordinatorPeerState.value = null
        }
    }

    override fun onCleared() {
        closeActiveCoordinator()
        connectivityRepository.disconnect()
        super.onCleared()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
