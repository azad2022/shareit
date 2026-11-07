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
import com.peyvand.app.presentation.discovery.DeviceDiscoveryViewModel
import com.peyvand.app.presentation.connection.PeerConnectionViewModel
import com.peyvand.app.presentation.files.FileSelectionScreen
import com.peyvand.app.presentation.files.ShareSelectionViewModel
import com.peyvand.app.presentation.home.DeviceDiscoveryScreen
import com.peyvand.app.presentation.home.MainHomeScreen
import com.peyvand.app.presentation.language.LanguageSelectionScreen
import com.peyvand.app.presentation.network.NetworkEnvironmentViewModel
import com.peyvand.app.presentation.transfer.FileTransferViewModel
import com.peyvand.app.presentation.transfer.FileTransferSessionScreen
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.domain.connection.PeerConnectionStatus
import com.peyvand.core.domain.discovery.DiscoveryRole

private enum class HomeDestination {
    HOME,
    SEND_SELECTION,
    SEND_DISCOVERY,
    RECEIVE_DISCOVERY,
    FILES,
    ACTIVITY,
    TRANSFER,
}

@Composable
fun PeyvandApp(
    languageViewModel: MainViewModel,
    discoveryViewModel: DeviceDiscoveryViewModel,
    networkEnvironmentViewModel: NetworkEnvironmentViewModel,
    shareSelectionViewModel: ShareSelectionViewModel,
    peerConnectionViewModel: PeerConnectionViewModel,
    fileTransferViewModel: FileTransferViewModel,
) {
    val uiState by languageViewModel.uiState.collectAsStateWithLifecycle()
    val connectionState by peerConnectionViewModel.state.collectAsStateWithLifecycle()
    val secureSession by peerConnectionViewModel.secureSession.collectAsStateWithLifecycle()
    val selectionState by shareSelectionViewModel.state.collectAsStateWithLifecycle()
    val selectedItems = selectionState.selectedItems.values.toList()
    var destination by rememberSaveable { mutableStateOf(HomeDestination.HOME) }

    LaunchedEffect(
        destination,
        secureSession.status,
        connectionState.status,
        connectionState.locallyInitiated,
        selectedItems,
    ) {
        val isDiscoveryRoute = destination == HomeDestination.SEND_DISCOVERY ||
            destination == HomeDestination.RECEIVE_DISCOVERY
        if (isDiscoveryRoute && secureSession.status == SecureSessionStatus.AUTHENTICATED) {
            destination = HomeDestination.TRANSFER
        } else if (
            destination == HomeDestination.TRANSFER &&
            secureSession.status == SecureSessionStatus.AUTHENTICATED &&
            connectionState.status == PeerConnectionStatus.GROUP_FORMED
        ) {
            fileTransferViewModel.prepareSession(
                isSender = connectionState.locallyInitiated,
                selectedItems = selectedItems,
            )
        }
    }

    BackHandler(
        enabled = uiState is MainUiState.Home && destination != HomeDestination.HOME,
    ) {
        if (destination == HomeDestination.TRANSFER) {
            fileTransferViewModel.cancelTransfer()
            fileTransferViewModel.leaveSession()
            shareSelectionViewModel.clearSelection()
            peerConnectionViewModel.disconnect()
        } else if (
            destination == HomeDestination.SEND_DISCOVERY ||
            destination == HomeDestination.RECEIVE_DISCOVERY
        ) {
            peerConnectionViewModel.disconnect()
        }
        destination = when (destination) {
            HomeDestination.SEND_DISCOVERY -> HomeDestination.SEND_SELECTION
            HomeDestination.SEND_SELECTION,
            HomeDestination.RECEIVE_DISCOVERY,
            HomeDestination.FILES,
            HomeDestination.ACTIVITY,
            HomeDestination.TRANSFER,
            -> HomeDestination.HOME
            HomeDestination.HOME -> HomeDestination.HOME
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
                HomeDestination.HOME -> MainHomeScreen(
                    onSend = { destination = HomeDestination.SEND_SELECTION },
                    onReceive = { destination = HomeDestination.RECEIVE_DISCOVERY },
                    onFiles = { destination = HomeDestination.FILES },
                    onActivity = { destination = HomeDestination.ACTIVITY },
                )

                HomeDestination.SEND_SELECTION -> FileSelectionScreen(
                    viewModel = shareSelectionViewModel,
                    selectionMode = true,
                    onBack = { destination = HomeDestination.HOME },
                    onContinue = { destination = HomeDestination.SEND_DISCOVERY },
                )

                HomeDestination.FILES -> FileSelectionScreen(
                    viewModel = shareSelectionViewModel,
                    selectionMode = false,
                    onBack = { destination = HomeDestination.HOME },
                    onContinue = {},
                )

                HomeDestination.RECEIVE_DISCOVERY -> DeviceDiscoveryScreen(
                    viewModel = discoveryViewModel,
                    networkEnvironmentViewModel = networkEnvironmentViewModel,
                    title = stringResource(R.string.receive_screen_title),
                    subtitle = stringResource(R.string.receive_screen_subtitle),
                    role = DiscoveryRole.ADVERTISE_ONLY,
                    onBack = {
                        peerConnectionViewModel.disconnect()
                        destination = HomeDestination.HOME
                    },
                    peerConnectionViewModel = peerConnectionViewModel,
                )

                HomeDestination.SEND_DISCOVERY -> DeviceDiscoveryScreen(
                    viewModel = discoveryViewModel,
                    networkEnvironmentViewModel = networkEnvironmentViewModel,
                    title = stringResource(R.string.send_discovery_title),
                    subtitle = stringResource(R.string.send_discovery_subtitle),
                    role = DiscoveryRole.BROWSE_ONLY,
                    onBack = {
                        peerConnectionViewModel.disconnect()
                        destination = HomeDestination.SEND_SELECTION
                    },
                    peerConnectionViewModel = peerConnectionViewModel,
                )

                HomeDestination.ACTIVITY -> TransferActivityScreen(
                    onBack = { destination = HomeDestination.HOME },
                )

                HomeDestination.TRANSFER -> FileTransferSessionScreen(
                    viewModel = fileTransferViewModel,
                    onBack = {
                        fileTransferViewModel.leaveSession()
                        shareSelectionViewModel.clearSelection()
                        peerConnectionViewModel.disconnect()
                        destination = HomeDestination.HOME
                    },
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
