package com.peyvand.app

import android.app.Application
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

class PeyvandApplication : Application() {
    private val localChatDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatDatabaseFactory.open(applicationContext)
    }

    private val peerIdentityKeyProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystorePeerIdentityKeyProvider()
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
