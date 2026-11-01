package com.peyvand.app

import android.app.Application
import com.peyvand.core.connectivity.wifi.WifiDirectDeviceDiscoveryRepository
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository

class PeyvandApplication : Application() {
    val deviceDiscoveryRepository: DeviceDiscoveryRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        WifiDirectDeviceDiscoveryRepository(applicationContext)
    }
}
