package com.peyvand.app

import android.app.Application
import com.peyvand.core.connectivity.network.AndroidNetworkEnvironmentRepository
import com.peyvand.core.connectivity.wifi.WifiDirectDeviceDiscoveryRepository
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository
import com.peyvand.core.domain.network.NetworkEnvironmentRepository
import com.peyvand.core.domain.files.ShareCatalogRepository
import com.peyvand.core.storage.AndroidShareCatalogRepository

class PeyvandApplication : Application() {
    val deviceDiscoveryRepository: DeviceDiscoveryRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        WifiDirectDeviceDiscoveryRepository(applicationContext)
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
}
