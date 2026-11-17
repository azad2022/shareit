package com.peyvand.app

import android.app.Application
import com.peyvand.core.calling.VoiceCallControlSessionHost
import com.peyvand.core.calling.VoiceCallSignal
import com.peyvand.core.connectivity.network.AndroidNetworkEnvironmentRepository
import com.peyvand.core.connectivity.wifi.WifiDirectDeviceDiscoveryRepository
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository
import com.peyvand.core.domain.network.NetworkEnvironmentRepository
import com.peyvand.core.domain.files.ShareCatalogRepository
import com.peyvand.core.domain.connection.PeerConnectivityRepository
import com.peyvand.core.storage.AndroidShareCatalogRepository
import com.peyvand.core.storage.AndroidTransferFileStorageRepository
import com.peyvand.core.domain.transfer.TransferFileStorageRepository
import com.peyvand.core.domain.chat.ChatRepository
import com.peyvand.core.data.chat.ChatDatabaseFactory
import com.peyvand.core.data.chat.AndroidKeystoreChatPayloadCipher
import com.peyvand.core.data.chat.RoomChatRepository
import com.peyvand.core.data.chat.AndroidKeystorePeerIdentityKeyProvider
import com.peyvand.core.data.chat.RoomTrustedPeerRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class PeyvandApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Starts only protocol/capability monitoring. Microphone and Telecom remain idle.
        voiceCallControlSessionHost
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingVoiceCallSignals = Channel<VoiceCallSignal>(capacity = 64)

    /** One ordered consumer receives signals from the active authenticated session. */
    val incomingVoiceCallSignals: Flow<VoiceCallSignal> = pendingVoiceCallSignals.receiveAsFlow()

    private val localChatDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatDatabaseFactory.open(applicationContext)
    }

    private val peerIdentityKeyProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystorePeerIdentityKeyProvider(trustedPeerRepository)
    }

    val trustedPeerRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomTrustedPeerRepository(localChatDatabase)
    }

    private val wifiDirectRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        WifiDirectDeviceDiscoveryRepository(
            context = applicationContext,
            peerIdentityKeyProvider = peerIdentityKeyProvider,
            trustedPeerRepository = trustedPeerRepository,
        )
    }

    val deviceDiscoveryRepository: DeviceDiscoveryRepository
        get() = wifiDirectRepository

    val peerConnectionRepository: PeerConnectivityRepository
        get() = wifiDirectRepository

    /**
     * Process-owned call-control listener. It probes peer capability only after mTLS and
     * exact Wi-Fi Direct endpoint binding are ready, then holds one CALL_CONTROL lease
     * for the authenticated session so incoming INVITEs are not dependent on a chat-screen
     * click. No microphone, WebRTC PeerConnection, or Telecom call is created here.
     */
    val voiceCallControlSessionHost: VoiceCallControlSessionHost by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        VoiceCallControlSessionHost(
            secureSession = peerConnectionRepository.secureSession,
            capabilityVerified = peerConnectionRepository.voiceCallCapabilityVerified,
            verifyCapability = peerConnectionRepository::verifyVoiceCallCapability,
            endpointProvider = wifiDirectRepository,
            claimAuthenticatedChannel = peerConnectionRepository::claimAuthenticatedChannel,
            scope = applicationScope,
            onSignal = { signal ->
                if (pendingVoiceCallSignals.trySend(signal).isFailure) {
                    throw IOException("The application incoming call-signal queue is full.")
                }
            },
        ).also(VoiceCallControlSessionHost::start)
    }

    val networkEnvironmentRepository: NetworkEnvironmentRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidNetworkEnvironmentRepository(applicationContext)
    }

    val shareCatalogRepository: ShareCatalogRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidShareCatalogRepository(applicationContext)
    }

    val transferFileStorageRepository: TransferFileStorageRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidTransferFileStorageRepository(applicationContext)
    }

    /**
     * Device-local chat persistence. Accessing this repository creates no socket,
     * network session, server connection, or backup destination.
     */
    val chatRepository: ChatRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomChatRepository(
            database = localChatDatabase,
            cipher = AndroidKeystoreChatPayloadCipher(),
        )
    }
}
