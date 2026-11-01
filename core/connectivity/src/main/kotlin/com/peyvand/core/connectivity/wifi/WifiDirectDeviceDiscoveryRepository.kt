package com.peyvand.core.connectivity.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.UUID
import com.peyvand.core.domain.discovery.DeviceDiscoveryFailure
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository
import com.peyvand.core.domain.discovery.DeviceDiscoverySnapshot
import com.peyvand.core.domain.discovery.DiscoveryStatus
import com.peyvand.core.domain.discovery.NearbyPeer
import com.peyvand.core.domain.discovery.PeyvandDiscoveryAdvertisement
import com.peyvand.core.domain.discovery.WifiDirectAvailability
import com.peyvand.core.domain.discovery.WifiDirectPermissionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wi-Fi Direct DNS-SD adapter.
 *
 * It discovers PEYVAND service advertisements only. It does not connect to
 * peers, authenticate peers, open sockets, or transfer data.
 */
class WifiDirectDeviceDiscoveryRepository(
    context: Context,
) : DeviceDiscoveryRepository {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val wifiP2pManager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val mutableSnapshot = MutableStateFlow(DeviceDiscoverySnapshot())
    override val snapshot = mutableSnapshot.asStateFlow()

    private var channel: WifiP2pManager.Channel? = null
    private var monitoring = false
    private var receiverRegistered = false
    private var p2pEnabled: Boolean? = null
    private var generation = 0L

    private val serviceInstanceName by lazy {
        PeyvandDiscoveryAdvertisement.INSTANCE_NAME_PREFIX +
            UUID.randomUUID().toString().replace("-", "").take(
                PeyvandDiscoveryAdvertisement.INSTANCE_ID_LENGTH,
            )
    }

    private companion object {
        const val MAX_PENDING_PEERS = 128
        const val MAX_VISIBLE_PEERS = 64
    }

    private val discoveredServices = mutableMapOf<String, WifiP2pDevice>()
    private val discoveredRecords = mutableMapOf<String, Map<String, String>>()

    private val serviceInfo by lazy {
        WifiP2pDnsSdServiceInfo.newInstance(
            serviceInstanceName,
            PeyvandDiscoveryAdvertisement.SERVICE_TYPE,
            mapOf(
                PeyvandDiscoveryAdvertisement.APP_KEY to PeyvandDiscoveryAdvertisement.APP_ID,
                PeyvandDiscoveryAdvertisement.DISCOVERY_VERSION_KEY to
                    PeyvandDiscoveryAdvertisement.DISCOVERY_VERSION,
            ),
        )
    }

    private val serviceRequest by lazy {
        WifiP2pDnsSdServiceRequest.newInstance(
            PeyvandDiscoveryAdvertisement.SERVICE_TYPE,
        )
    }

    private val channelListener = object : WifiP2pManager.ChannelListener {
        override fun onChannelDisconnected() {
            channel = initializeChannel()
            generation += 1
            clearPendingPeers()
            mutableSnapshot.value = mutableSnapshot.value.copy(
                availability = if (channel == null) {
                    WifiDirectAvailability.UNAVAILABLE
                } else {
                    currentAvailability()
                },
                status = DiscoveryStatus.FAILED,
                peers = emptyList(),
                failure = DeviceDiscoveryFailure.PLATFORM_ERROR,
            )
        }
    }

    private val p2pStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) return

            p2pEnabled = intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                WifiP2pManager.WIFI_P2P_STATE_DISABLED,
            ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

            if (p2pEnabled == false) {
                generation += 1
                clearPendingPeers()
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    availability = WifiDirectAvailability.WIFI_P2P_DISABLED,
                    status = DiscoveryStatus.IDLE,
                    peers = emptyList(),
                    failure = null,
                )
            } else {
                refreshAvailability()
            }
        }
    }

    init {
        channel = initializeChannel()
    }

    override fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        registerP2pStateReceiverIfPermitted()
        refreshAvailability()
    }

    /**
     * Wi-Fi P2P state is exposed as a protected sticky system broadcast.
     * Register only after the version-specific runtime permission is granted.
     */
    private fun registerP2pStateReceiverIfPermitted() {
        if (!monitoring || receiverRegistered || wifiP2pManager == null ||
            !hasRequiredRuntimePermission()
        ) {
            return
        }

        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        try {
            val stickyIntent = ContextCompat.registerReceiver(
                appContext,
                p2pStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
            stickyIntent?.let { p2pStateReceiver.onReceive(appContext, it) }
        } catch (_: SecurityException) {
            p2pEnabled = null
            mutableSnapshot.value = mutableSnapshot.value.copy(
                availability = WifiDirectAvailability.UNAVAILABLE,
                status = DiscoveryStatus.FAILED,
                failure = DeviceDiscoveryFailure.PLATFORM_ERROR,
                peers = emptyList(),
            )
        }
    }

    override fun stopMonitoring() {
        if (!monitoring && !receiverRegistered) return

        stopDiscovery()
        monitoring = false
        p2pEnabled = null

        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(p2pStateReceiver)
            } catch (_: IllegalArgumentException) {
                // Safe during teardown if Android already removed the receiver.
            } finally {
                receiverRegistered = false
            }
        }
    }

    override fun refreshAvailability() {
        registerP2pStateReceiverIfPermitted()
        val availability = currentAvailability()
        val current = mutableSnapshot.value

        mutableSnapshot.value = current.copy(
            availability = availability,
            failure = when (availability) {
                WifiDirectAvailability.PERMISSION_REQUIRED ->
                    DeviceDiscoveryFailure.PERMISSION_REQUIRED
                else -> current.failure.takeUnless {
                    it == DeviceDiscoveryFailure.PERMISSION_REQUIRED
                }
            },
            status = if (availability == WifiDirectAvailability.READY) {
                current.status
            } else {
                DiscoveryStatus.IDLE
            },
            peers = if (availability == WifiDirectAvailability.READY) {
                current.peers
            } else {
                emptyList()
            },
        )

        if (availability != WifiDirectAvailability.READY) {
            generation += 1
            clearPendingPeers()
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        val currentStatus = mutableSnapshot.value.status
        if (currentStatus == DiscoveryStatus.STARTING ||
            currentStatus == DiscoveryStatus.DISCOVERING
        ) {
            return
        }

        refreshAvailability()
        if (mutableSnapshot.value.availability != WifiDirectAvailability.READY) return

        val manager = wifiP2pManager ?: run {
            setUnavailable()
            return
        }
        val activeChannel = channel ?: initializeChannel()?.also {
            channel = it
        } ?: run {
            setFailure(DeviceDiscoveryFailure.PLATFORM_ERROR)
            return
        }

        val requestGeneration = ++generation
        clearPendingPeers()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = DiscoveryStatus.STARTING,
            peers = emptyList(),
            failure = null,
        )

        try {
            manager.setDnsSdResponseListeners(
                activeChannel,
                WifiP2pManager.DnsSdServiceResponseListener {
                        instanceName,
                        registrationType,
                        device,
                    ->
                    if (isCurrent(requestGeneration) &&
                        PeyvandDiscoveryAdvertisement.isCompatibleInstanceName(instanceName) &&
                        registrationType.contains(
                            PeyvandDiscoveryAdvertisement.SERVICE_TYPE,
                            ignoreCase = true,
                        )
                    ) {
                        val address = device.deviceAddress
                        if (address.isNotBlank()) {
                            rememberService(address, device)
                            publishPeerIfCompatible(address, requestGeneration)
                        }
                    }
                },
                WifiP2pManager.DnsSdTxtRecordListener {
                        fullDomain,
                        record,
                        device,
                    ->
                    if (isCurrent(requestGeneration) &&
                        fullDomain.contains(
                            PeyvandDiscoveryAdvertisement.SERVICE_TYPE,
                            ignoreCase = true,
                        )
                    ) {
                        val address = device.deviceAddress
                        if (address.isNotBlank()) {
                            rememberRecord(address, record.toMap())
                            publishPeerIfCompatible(address, requestGeneration)
                        }
                    }
                },
            )

            manager.clearServiceRequests(
                activeChannel,
                actionListener(requestGeneration) {
                    manager.clearLocalServices(
                        activeChannel,
                        actionListener(requestGeneration) {
                            manager.addLocalService(
                                activeChannel,
                                serviceInfo,
                                actionListener(requestGeneration) {
                                    manager.addServiceRequest(
                                        activeChannel,
                                        serviceRequest,
                                        actionListener(requestGeneration) {
                                            manager.discoverServices(
                                                activeChannel,
                                                actionListener(requestGeneration) {
                                                    mutableSnapshot.value =
                                                        mutableSnapshot.value.copy(
                                                            status = DiscoveryStatus.DISCOVERING,
                                                            failure = null,
                                                        )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        } catch (_: SecurityException) {
            setPermissionRequired(requestGeneration)
        } catch (_: RuntimeException) {
            setFailure(DeviceDiscoveryFailure.PLATFORM_ERROR, requestGeneration)
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        generation += 1
        clearPendingPeers()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = DiscoveryStatus.IDLE,
            peers = emptyList(),
            failure = null,
        )

        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return

        try {
            manager.clearServiceRequests(activeChannel, noOpActionListener())
            manager.clearLocalServices(activeChannel, noOpActionListener())
        } catch (_: SecurityException) {
            refreshAvailability()
        } catch (_: RuntimeException) {
            // Framework teardown may already have invalidated the P2P channel.
        }
    }

    private fun initializeChannel(): WifiP2pManager.Channel? =
        try {
            wifiP2pManager?.initialize(
                appContext,
                Looper.getMainLooper(),
                channelListener,
            )
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun currentAvailability(): WifiDirectAvailability {
        if (wifiP2pManager == null ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        ) {
            return WifiDirectAvailability.UNSUPPORTED
        }

        if (!hasRequiredRuntimePermission()) {
            return WifiDirectAvailability.PERMISSION_REQUIRED
        }

        if (!isLocationModeEnabled()) {
            return WifiDirectAvailability.LOCATION_SERVICES_DISABLED
        }

        if (p2pEnabled == false) {
            return WifiDirectAvailability.WIFI_P2P_DISABLED
        }

        if (p2pEnabled == null) {
            return WifiDirectAvailability.CHECKING
        }

        if (channel == null) {
            return WifiDirectAvailability.UNAVAILABLE
        }

        return WifiDirectAvailability.READY
    }

    @SuppressLint("InlinedApi")
    private fun hasRequiredRuntimePermission(): Boolean {
        val permission = when (
            WifiDirectPermissionPolicy.forApiLevel(Build.VERSION.SDK_INT)
        ) {
            WifiDirectPermissionPolicy.Permission.NEARBY_WIFI_DEVICES ->
                Manifest.permission.NEARBY_WIFI_DEVICES
            WifiDirectPermissionPolicy.Permission.LEGACY_FINE_LOCATION ->
                Manifest.permission.ACCESS_FINE_LOCATION
            WifiDirectPermissionPolicy.Permission.LEGACY_COARSE_LOCATION ->
                error("Coarse location alone cannot authorize Wi-Fi Direct discovery.")
        }

        return ContextCompat.checkSelfPermission(
            appContext,
            permission,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun isLocationModeEnabled(): Boolean {
        val manager = locationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun actionListener(
        expectedGeneration: Long,
        onSuccess: () -> Unit,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (isCurrent(expectedGeneration)) onSuccess()
        }

        override fun onFailure(reason: Int) {
            handlePlatformFailure(expectedGeneration, reason)
        }
    }

    private fun noOpActionListener(): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) = Unit
        }

    private fun handlePlatformFailure(expectedGeneration: Long, reason: Int) {
        if (!isCurrent(expectedGeneration)) return

        when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    availability = WifiDirectAvailability.UNSUPPORTED,
                    status = DiscoveryStatus.FAILED,
                    peers = emptyList(),
                    failure = DeviceDiscoveryFailure.UNSUPPORTED,
                )
                clearPendingPeers()
            }

            WifiP2pManager.BUSY -> {
                setFailure(DeviceDiscoveryFailure.BUSY, expectedGeneration)
            }

            else -> {
                setFailure(DeviceDiscoveryFailure.PLATFORM_ERROR, expectedGeneration)
            }
        }
    }

    private fun publishPeerIfCompatible(
        address: String,
        expectedGeneration: Long,
    ) {
        if (!isCurrent(expectedGeneration)) return

        val device = discoveredServices[address] ?: return
        val record = discoveredRecords[address] ?: return
        if (!PeyvandDiscoveryAdvertisement.isCompatible(record)) return

        val peer = NearbyPeer(
            id = address,
            displayName = device.deviceName.takeIf { it.isNotBlank() },
        )

        val existingPeers = mutableSnapshot.value.peers
        if (existingPeers.none { it.id == address } &&
            existingPeers.size >= MAX_VISIBLE_PEERS
        ) {
            return
        }
        val peers = (existingPeers.filterNot { it.id == address } + peer)
            .sortedBy { it.displayName?.lowercase().orEmpty() }

        mutableSnapshot.value = mutableSnapshot.value.copy(
            peers = peers,
            failure = null,
        )
    }

    private fun rememberService(address: String, device: WifiP2pDevice) {
        if (!discoveredServices.containsKey(address) &&
            discoveredServices.size >= MAX_PENDING_PEERS
        ) {
            val oldestAddress = discoveredServices.keys.firstOrNull()
            if (oldestAddress != null) {
                discoveredServices.remove(oldestAddress)
                discoveredRecords.remove(oldestAddress)
            }
        }
        discoveredServices[address] = device
    }

    private fun rememberRecord(address: String, record: Map<String, String>) {
        if (!discoveredRecords.containsKey(address) &&
            discoveredRecords.size >= MAX_PENDING_PEERS
        ) {
            val oldestAddress = discoveredRecords.keys.firstOrNull()
            if (oldestAddress != null) {
                discoveredRecords.remove(oldestAddress)
                discoveredServices.remove(oldestAddress)
            }
        }
        discoveredRecords[address] = record
    }

    private fun clearPendingPeers() {
        discoveredServices.clear()
        discoveredRecords.clear()
    }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        monitoring && generation == expectedGeneration

    private fun setUnavailable() {
        mutableSnapshot.value = mutableSnapshot.value.copy(
            availability = WifiDirectAvailability.UNAVAILABLE,
            status = DiscoveryStatus.FAILED,
            failure = DeviceDiscoveryFailure.PLATFORM_ERROR,
        )
    }

    private fun setPermissionRequired(expectedGeneration: Long? = null) {
        if (expectedGeneration != null && !isCurrent(expectedGeneration)) return
        mutableSnapshot.value = mutableSnapshot.value.copy(
            availability = WifiDirectAvailability.PERMISSION_REQUIRED,
            status = DiscoveryStatus.FAILED,
            failure = DeviceDiscoveryFailure.PERMISSION_REQUIRED,
            peers = emptyList(),
        )
        clearPendingPeers()
    }

    private fun setFailure(
        failure: DeviceDiscoveryFailure,
        expectedGeneration: Long? = null,
    ) {
        if (expectedGeneration != null && !isCurrent(expectedGeneration)) return
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = DiscoveryStatus.FAILED,
            failure = failure,
            peers = emptyList(),
        )
    }
}
