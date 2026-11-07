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

class PeyvandApplication : Application() {
    private val wifiDirectRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        WifiDirectDeviceDiscoveryRepository(applicationContext)
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
}
