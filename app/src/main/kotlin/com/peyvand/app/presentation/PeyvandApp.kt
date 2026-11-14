package com.peyvand.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peyvand.app.R
import com.peyvand.app.presentation.chat.ChatConversationScreen
import com.peyvand.app.presentation.chat.ChatHomeScreen
import com.peyvand.app.presentation.chat.ChatViewModel
import com.peyvand.app.presentation.chat.NearbyChatHubScreen
import com.peyvand.app.presentation.profile.DeviceProfileScreen
import com.peyvand.app.presentation.profile.DeviceProfileViewModel
import com.peyvand.app.presentation.connection.PeerConnectionViewModel
import com.peyvand.app.presentation.discovery.DeviceDiscoveryViewModel
import com.peyvand.app.presentation.files.FileSelectionScreen
import com.peyvand.app.presentation.files.ShareSelectionViewModel
import com.peyvand.app.presentation.home.DeviceDiscoveryScreen
import com.peyvand.app.presentation.language.LanguageSelectionScreen
import com.peyvand.app.presentation.network.NetworkEnvironmentViewModel
import com.peyvand.app.presentation.transfer.FileTransferSessionScreen
import com.peyvand.app.presentation.transfer.FileTransferViewModel
import com.peyvand.core.domain.connection.PeerConnectionStatus
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.domain.discovery.DiscoveryRole
import com.peyvand.core.domain.chat.PeerIdentityId

private enum class HomeDestination {
    HOME,
    NEARBY_HUB,
    CHAT_FIND_DEVICE,
    CHAT_PREPARE_TO_BE_FOUND,
    CHAT_CONVERSATION,
    CHAT_ATTACHMENT_SELECTION,
    CHAT_ATTACHMENT_SEND,
    CHAT_ATTACHMENT_RECEIVE,
    ACTIVITY,
    DEVICE_PROFILE,
}

@Composable
fun PeyvandApp(
    languageViewModel: MainViewModel,
    discoveryViewModel: DeviceDiscoveryViewModel,
    networkEnvironmentViewModel: NetworkEnvironmentViewModel,
    shareSelectionViewModel: ShareSelectionViewModel,
    peerConnectionViewModel: PeerConnectionViewModel,
    fileTransferViewModel: FileTransferViewModel,
    chatViewModel: ChatViewModel,
    deviceProfileViewModel: DeviceProfileViewModel,
) {
    val uiState by languageViewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by peerConnectionViewModel.state.collectAsStateWithLifecycle()
    val secureSession by peerConnectionViewModel.secureSession.collectAsStateWithLifecycle()
    val selectionState by shareSelectionViewModel.state.collectAsStateWithLifecycle()
    val chatHomeState by chatViewModel.homeUiState.collectAsStateWithLifecycle()
    val conversationState by chatViewModel.conversationUiState.collectAsStateWithLifecycle()
    val deviceProfileState by deviceProfileViewModel.state.collectAsStateWithLifecycle()
    val selectedItems = selectionState.selectedItems.values.toList()
    var destination by rememberSaveable { mutableStateOf(HomeDestination.HOME) }
    var selectedChatPeerFingerprint by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(
        destination,
        secureSession.status,
        secureSession.peerIdentityId,
        connectionState.status,
        connectionState.locallyInitiated,
        selectedItems,
    ) {
        val isChatDiscoveryRoute =
            destination == HomeDestination.CHAT_FIND_DEVICE ||
                destination == HomeDestination.CHAT_PREPARE_TO_BE_FOUND
        if (isChatDiscoveryRoute && secureSession.status == SecureSessionStatus.AUTHENTICATED) {
            val authenticatedPeer = chatViewModel.openAuthenticatedConversation()
            if (authenticatedPeer != null) {
                selectedChatPeerFingerprint = authenticatedPeer.sha256Fingerprint
                destination = HomeDestination.CHAT_CONVERSATION
            }
        }
        if (
            (destination == HomeDestination.CHAT_ATTACHMENT_SEND ||
                destination == HomeDestination.CHAT_ATTACHMENT_RECEIVE) &&
            secureSession.status == SecureSessionStatus.AUTHENTICATED &&
            connectionState.status == PeerConnectionStatus.GROUP_FORMED
        ) {
            fileTransferViewModel.prepareSession(
                isSender = destination == HomeDestination.CHAT_ATTACHMENT_SEND,
                selectedItems = selectedItems,
            )
        }
    }

    LaunchedEffect(destination, selectedChatPeerFingerprint) {
        if (destination == HomeDestination.CHAT_CONVERSATION) {
            val fingerprint = selectedChatPeerFingerprint
            val identity = fingerprint?.let {
                runCatching { PeerIdentityId.fromSha256Fingerprint(it) }.getOrNull()
            }
            if (identity == null) {
                selectedChatPeerFingerprint = null
                destination = HomeDestination.HOME
            } else {
                chatViewModel.openConversation(identity)
            }
        }
    }

    BackHandler(
        enabled = uiState is MainUiState.Home && destination != HomeDestination.HOME,
    ) {
        when (destination) {
            HomeDestination.CHAT_CONVERSATION -> {
                selectedChatPeerFingerprint = null
                chatViewModel.clearConversationSelection()
                destination = HomeDestination.HOME
            }

            HomeDestination.CHAT_ATTACHMENT_SELECTION -> {
                destination = HomeDestination.CHAT_CONVERSATION
            }

            HomeDestination.CHAT_ATTACHMENT_SEND,
            HomeDestination.CHAT_ATTACHMENT_RECEIVE,
            -> {
                fileTransferViewModel.cancelTransfer()
                fileTransferViewModel.leaveSession()
                shareSelectionViewModel.clearSelection()
                // Release only the FILE_TRANSFER logical channel. The chat channel stays alive.
                destination = HomeDestination.CHAT_CONVERSATION
            }

            HomeDestination.CHAT_FIND_DEVICE,
            HomeDestination.CHAT_PREPARE_TO_BE_FOUND,
            -> {
                chatViewModel.disconnectCurrentPeer()
                destination = HomeDestination.NEARBY_HUB
            }

            HomeDestination.DEVICE_PROFILE -> {
                deviceProfileViewModel.discardChanges()
                destination = HomeDestination.HOME
            }

            HomeDestination.NEARBY_HUB,
            HomeDestination.ACTIVITY,
            -> destination = HomeDestination.HOME

            HomeDestination.HOME -> Unit
        }
    }

    AnimatedContent(
        targetState = uiState,
        contentKey = { state -> state::class },
        transitionSpec = {
            (fadeIn(tween(durationMillis = 220)) +
                slideInVertically(tween(durationMillis = 220)) { height -> height / 24 }) togetherWith
                (fadeOut(tween(durationMillis = 160)) +
                    slideOutVertically(tween(durationMillis = 160)) { height -> -height / 24 })
        },
        label = "peyvand-main-route",
    ) { targetState ->
        when (targetState) {
            is MainUiState.LanguageSelection -> LanguageSelectionScreen(
                isSaving = targetState.isSaving,
                hasError = targetState.hasError,
                onContinue = languageViewModel::confirmLanguageSelection,
            )

            is MainUiState.Home -> when (destination) {
                HomeDestination.HOME -> ChatHomeScreen(
                    state = chatHomeState,
                    localProfile = deviceProfileState.localProfile,
                    defaultDeviceName = deviceProfileState.defaultDeviceName,
                    onOpenProfile = { destination = HomeDestination.DEVICE_PROFILE },
                    onOpenConversation = { identity ->
                        selectedChatPeerFingerprint = identity.sha256Fingerprint
                        destination = HomeDestination.CHAT_CONVERSATION
                    },
                    onOpenNearby = { destination = HomeDestination.NEARBY_HUB },
                    onOpenTransfers = { destination = HomeDestination.ACTIVITY },
                )

                HomeDestination.DEVICE_PROFILE -> DeviceProfileScreen(
                    viewModel = deviceProfileViewModel,
                    onBack = {
                        deviceProfileViewModel.discardChanges()
                        destination = HomeDestination.HOME
                    },
                )

                HomeDestination.NEARBY_HUB -> NearbyChatHubScreen(
                    onBack = { destination = HomeDestination.HOME },
                    onFindPeer = {
                        chatViewModel.disconnectCurrentPeer()
                        destination = HomeDestination.CHAT_FIND_DEVICE
                    },
                    onPrepareToBeFound = {
                        chatViewModel.disconnectCurrentPeer()
                        destination = HomeDestination.CHAT_PREPARE_TO_BE_FOUND
                    },
                )

                HomeDestination.CHAT_FIND_DEVICE -> DeviceDiscoveryScreen(
                    viewModel = discoveryViewModel,
                    networkEnvironmentViewModel = networkEnvironmentViewModel,
                    title = stringResource(R.string.chat_find_discovery_title),
                    subtitle = stringResource(R.string.chat_find_discovery_subtitle),
                    role = DiscoveryRole.BROWSE_ONLY,
                    onBack = {
                        chatViewModel.disconnectCurrentPeer()
                        destination = HomeDestination.NEARBY_HUB
                    },
                    peerConnectionViewModel = peerConnectionViewModel,
                )

                HomeDestination.CHAT_PREPARE_TO_BE_FOUND -> DeviceDiscoveryScreen(
                    viewModel = discoveryViewModel,
                    networkEnvironmentViewModel = networkEnvironmentViewModel,
                    title = stringResource(R.string.chat_ready_discovery_title),
                    subtitle = stringResource(R.string.chat_ready_discovery_subtitle),
                    role = DiscoveryRole.ADVERTISE_ONLY,
                    onBack = {
                        chatViewModel.disconnectCurrentPeer()
                        destination = HomeDestination.NEARBY_HUB
                    },
                    peerConnectionViewModel = peerConnectionViewModel,
                )

                HomeDestination.CHAT_CONVERSATION -> ChatConversationScreen(
                    state = conversationState,
                    onBack = {
                        selectedChatPeerFingerprint = null
                        chatViewModel.clearConversationSelection()
                        destination = HomeDestination.HOME
                    },
                    onConnect = { destination = HomeDestination.NEARBY_HUB },
                    onSendAttachment = {
                        shareSelectionViewModel.clearSelection()
                        destination = HomeDestination.CHAT_ATTACHMENT_SELECTION
                    },
                    onPrepareReceive = {
                        shareSelectionViewModel.clearSelection()
                        destination = HomeDestination.CHAT_ATTACHMENT_RECEIVE
                    },
                    onSendMessage = chatViewModel::sendMessage,
                )

                HomeDestination.CHAT_ATTACHMENT_SELECTION -> FileSelectionScreen(
                    viewModel = shareSelectionViewModel,
                    selectionMode = true,
                    onBack = { destination = HomeDestination.CHAT_CONVERSATION },
                    onContinue = { destination = HomeDestination.CHAT_ATTACHMENT_SEND },
                )

                HomeDestination.CHAT_ATTACHMENT_SEND,
                HomeDestination.CHAT_ATTACHMENT_RECEIVE,
                -> FileTransferSessionScreen(
                    viewModel = fileTransferViewModel,
                    onBack = {
                        fileTransferViewModel.leaveSession()
                        shareSelectionViewModel.clearSelection()
                        destination = HomeDestination.CHAT_CONVERSATION
                    },
                )

                HomeDestination.ACTIVITY -> TransferActivityScreen(
                    onBack = { destination = HomeDestination.HOME },
                )
            }
        }
    }
}

@Composable
private fun TransferActivityScreen(
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
            Text(
                text = stringResource(R.string.activity_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.activity_empty),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
