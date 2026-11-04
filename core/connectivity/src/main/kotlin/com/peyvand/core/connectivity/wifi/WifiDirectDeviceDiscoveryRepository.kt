package com.peyvand.core.connectivity.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import com.peyvand.core.domain.connection.PeerConnectionFailure
import com.peyvand.core.domain.connection.PeerConnectionRepository
import com.peyvand.core.domain.connection.PeerConnectionSnapshot
import com.peyvand.core.domain.connection.PeerConnectionStatePolicy
import com.peyvand.core.domain.connection.PeerConnectionStatus
import com.peyvand.core.domain.discovery.DeviceDiscoveryFailure
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository
import com.peyvand.core.domain.discovery.DiscoveryCandidateRegistry
import com.peyvand.core.domain.discovery.DiscoverySourceDiagnostics
import com.peyvand.core.domain.discovery.DeviceDiscoverySnapshot
import com.peyvand.core.domain.discovery.DiscoveryOperation
import com.peyvand.core.domain.discovery.DiscoveryRole
import com.peyvand.core.domain.discovery.DiscoveryStatus
import com.peyvand.core.domain.discovery.PlatformDiscoveryState
import com.peyvand.core.domain.discovery.PeyvandDiscoveryAdvertisement
import com.peyvand.core.domain.discovery.WifiDirectAvailability
import com.peyvand.core.domain.discovery.WifiDirectBusyRetryPolicy
import com.peyvand.core.domain.discovery.WifiDirectPermissionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wi-Fi Direct DNS-SD adapter.
 *
 * It advertises PEYVAND availability or browses for compatible services based
 * on the active screen role. It does not connect, authenticate, or transfer.
 */
class WifiDirectDeviceDiscoveryRepository(
    context: Context,
) : DeviceDiscoveryRepository, PeerConnectionRepository {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val wifiP2pManager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val mutableSnapshot = MutableStateFlow(DeviceDiscoverySnapshot())
    private val mutableConnection = MutableStateFlow(PeerConnectionSnapshot())
    override val connection = mutableConnection.asStateFlow()

    private var connectionGeneration = 0L
    private var connectionTimeoutTask: Runnable? = null
    override val snapshot = mutableSnapshot.asStateFlow()

    private var channel: WifiP2pManager.Channel? = null
    private var monitoring = false
    private var receiverRegistered = false
    private var p2pEnabled: Boolean? = null
    private var generation = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var p2pStateQueryInFlight = false
    private var p2pStateQueryFailed = false
    private var p2pStateQueryGeneration = 0L
    private var p2pStateQueryTimeoutTask: Runnable? = null
    private var serviceDiscoveryStartInFlight = false
    private val pendingDiscoveryRetryTasks = linkedSetOf<Runnable>()
    private val pendingCleanupRetryTasks = linkedSetOf<Runnable>()

    private val serviceDiscoveryLifecycle = WifiDirectServiceDiscoveryLifecycleController(
        mainHandler = mainHandler,
        managerProvider = { wifiP2pManager },
        channelProvider = { channel },
        activeScanGeneration = { generation },
        isCurrentScan = ::isCurrent,
        isMonitoring = { monitoring },
        isReceiverRegistered = { receiverRegistered },
        activeRoleProvider = { activeRole },
        cleanupInProgress = { cleanupInProgress },
        cleanupRequested = { cleanupRequested },
        startInFlight = { serviceDiscoveryStartInFlight },
        discoveryRequestAccepted = { mutableSnapshot.value.discoveryStarted },
        hasRequiredPermission = ::hasRequiredRuntimePermission,
        onSnapshotChanged = { platformState, stopCount, restartCount ->
            mutableSnapshot.value = mutableSnapshot.value.copy(
                platformDiscoveryState = platformState,
                discoveryStopCount = stopCount,
                discoveryRestartCount = restartCount,
            )
        },
        startRearm = { expectedGeneration ->
            if (currentAvailability() != WifiDirectAvailability.READY) {
                refreshAvailability()
            } else {
                val manager = wifiP2pManager
                val activeChannel = channel
                when {
                    manager == null -> setUnavailable()
                    activeChannel == null -> setFailure(
                        DeviceDiscoveryFailure.PLATFORM_ERROR,
                        expectedGeneration,
                        failureOperation = DiscoveryOperation.DISCOVER_SERVICES,
                    )
                    else -> startServiceDiscoveryWithRetry(
                        manager,
                        activeChannel,
                        expectedGeneration,
                        isRearm = true,
                    )
                }
            }
        },
    )

    private val serviceInstanceName by lazy {
        PeyvandDiscoveryAdvertisement.INSTANCE_NAME_PREFIX +
            UUID.randomUUID().toString().replace("-", "").take(
                PeyvandDiscoveryAdvertisement.INSTANCE_ID_LENGTH,
            )
    }

    private companion object {
        const val LOG_TAG = "PeyvandP2P"
        const val MAX_PENDING_PEERS = 128
        const val MAX_VISIBLE_PEERS = 64
        const val P2P_STATE_QUERY_TIMEOUT_MS = 5_000L
        const val P2P_CONNECTION_TIMEOUT_MS = 30_000L
    }

    private val candidateRegistry = DiscoveryCandidateRegistry(
        maxPendingServices = MAX_PENDING_PEERS,
        maxPendingRecords = MAX_PENDING_PEERS,
        maxVisiblePeers = MAX_VISIBLE_PEERS,
    )

    // Address-to-token mapping exists only for the active scan. Raw addresses are
    // never copied into the UI diagnostics snapshot or written to logs.
    private val sourceTokensByAddress = linkedMapOf<String, String>()
    private val sourceDiagnosticsByToken = linkedMapOf<String, DiscoverySourceDiagnostics>()
    private var missingSourceAddressCount = 0
    private var untrackedSourceCount = 0

    // Do not overlap a new scan with asynchronous cleanup from the previous scan.
    private var cleanupInProgress = false
    private var cleanupRequested = false
    private var activeRole: DiscoveryRole? = null
    private var restartRoleAfterCleanup: DiscoveryRole? = null
    private var cleanupCallbacksRemaining = 0
    private var cleanupPassId = 0L

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
            val wasMonitoring = monitoring
            invalidateP2pStateQuery()
            serviceDiscoveryLifecycle.cancelForChannelLoss()
            serviceDiscoveryStartInFlight = false
            cancelDiscoveryRetryTasks()
            cancelCleanupRetryTasks()
            p2pEnabled = null
            generation += 1
            clearPendingPeers()
            cleanupPassId += 1L
            cleanupInProgress = false
            cleanupRequested = false
            activeRole = null
            restartRoleAfterCleanup = null
            cleanupCallbacksRemaining = 0
            channel = if (wasMonitoring) initializeChannel() else null

            mutableSnapshot.value = mutableSnapshot.value.copy(
                availability = if (!wasMonitoring || channel == null) {
                    WifiDirectAvailability.UNAVAILABLE
                } else {
                    currentAvailability()
                },
                status = if (wasMonitoring) {
                    DiscoveryStatus.FAILED
                } else {
                    DiscoveryStatus.IDLE
                },
                peers = emptyList(),
                platformDiscoveryState = PlatformDiscoveryState.UNKNOWN,
                sourceDiagnostics = emptyList(),
                missingSourceAddressCount = 0,
                untrackedSourceCount = 0,
                failure = if (wasMonitoring) {
                    DeviceDiscoveryFailure.PLATFORM_ERROR
                } else {
                    null
                },
            )
        }
    }

    private val p2pStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    serviceDiscoveryLifecycle.onDiscoveryStateBroadcast(intent)
                    return
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    handleP2pConnectionChanged()
                    return
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> Unit
                else -> return
            }

            invalidateP2pStateQuery()
            p2pEnabled = intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                WifiP2pManager.WIFI_P2P_STATE_DISABLED,
            ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

            if (p2pEnabled == false) {
                clearConnectionState()
                serviceDiscoveryLifecycle.cancelPendingWork()
                serviceDiscoveryStartInFlight = false
                val wasDiscovering = mutableSnapshot.value.status.let {
                    it == DiscoveryStatus.STARTING ||
                        it == DiscoveryStatus.ADVERTISING ||
                        it == DiscoveryStatus.DISCOVERING
                }
                generation += 1
                cancelDiscoveryRetryTasks()
                clearPendingPeers()
                if (wasDiscovering) releaseDiscoveryRegistrations()
                activeRole = null
                restartRoleAfterCleanup = null
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    availability = WifiDirectAvailability.WIFI_P2P_DISABLED,
                    status = DiscoveryStatus.IDLE,
                    peers = emptyList(),
                    platformDiscoveryState = PlatformDiscoveryState.UNKNOWN,
                    failure = null,
                    failureOperation = null,
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
     * Observe Wi-Fi P2P enabled-state changes only while foregrounded and
     * after the version-specific runtime permission has been granted.
     */
    private fun registerP2pStateReceiverIfPermitted() {
        if (!monitoring || receiverRegistered || wifiP2pManager == null ||
            !hasRequiredRuntimePermission()
        ) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
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
            p2pStateQueryFailed = true
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

        disconnect()
        stopDiscovery()
        monitoring = false
        invalidateP2pStateQuery()
        p2pEnabled = null
        unregisterP2pStateReceiver()
    }

    private fun unregisterP2pStateReceiver() {
        if (!receiverRegistered) return

        try {
            appContext.unregisterReceiver(p2pStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Safe during teardown if Android already removed the receiver.
        } finally {
            receiverRegistered = false
            serviceDiscoveryLifecycle.cancelPendingWork()
        }
    }

    override fun refreshAvailability() {
        val permissionGranted = hasRequiredRuntimePermission()
        if (permissionGranted) {
            // A previous state-query failure is retried only when a later
            // foreground refresh explicitly asks us to re-check availability.
            if (p2pStateQueryFailed) p2pStateQueryFailed = false
            // A P2P channel can disconnect while the Activity remains visible.
            // Retry initialization on the next availability refresh rather than
            // leaving the adapter permanently unavailable until process restart.
            if (monitoring && channel == null &&
                wifiP2pManager != null &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
            ) {
                channel = initializeChannel()
            }
            registerP2pStateReceiverIfPermitted()
            requestP2pStateIfNeeded()
        } else {
            invalidateP2pStateQuery()
            p2pEnabled = null
            unregisterP2pStateReceiver()
        }

        val availability = currentAvailability()
        val current = mutableSnapshot.value

        mutableSnapshot.value = current.copy(
            availability = availability,
            platformDiscoveryState = if (availability == WifiDirectAvailability.READY) {
                current.platformDiscoveryState
            } else {
                PlatformDiscoveryState.UNKNOWN
            },
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
            cancelDiscoveryRetryTasks()
            serviceDiscoveryLifecycle.cancelPendingWork()
            serviceDiscoveryStartInFlight = false
            val wasDiscovering = current.status.let {
                it == DiscoveryStatus.STARTING ||
                    it == DiscoveryStatus.ADVERTISING ||
                    it == DiscoveryStatus.DISCOVERING
            }
            generation += 1
            clearPendingPeers()
            activeRole = null
            restartRoleAfterCleanup = null
            if (wasDiscovering) releaseDiscoveryRegistrations()
        }
    }

    @SuppressLint("MissingPermission")

    @SuppressLint("MissingPermission")
    override fun connectToPeer(peerId: String) {
        val discovery = mutableSnapshot.value
        val isDiscoveryActive = discovery.status == DiscoveryStatus.STARTING ||
            discovery.status == DiscoveryStatus.ADVERTISING ||
            discovery.status == DiscoveryStatus.DISCOVERING
        if (!PeerConnectionStatePolicy.mayInitiate(
                peerId = peerId,
                currentlyDiscoveredPeerIds = discovery.peers.map { it.id },
                isDiscoveryActive = isDiscoveryActive,
            )
        ) {
            mutableConnection.value = PeerConnectionSnapshot(
                status = PeerConnectionStatus.FAILED,
                failure = PeerConnectionFailure.INVALID_DISCOVERY_CANDIDATE,
            )
            return
        }

        if (!hasRequiredRuntimePermission()) {
            mutableConnection.value = PeerConnectionSnapshot(
                status = PeerConnectionStatus.FAILED,
                selectedPeerId = peerId,
                locallyInitiated = true,
                failure = PeerConnectionFailure.PERMISSION_DENIED,
            )
            return
        }

        if (mutableConnection.value.status == PeerConnectionStatus.CONNECTING ||
            mutableConnection.value.status == PeerConnectionStatus.GROUP_FORMED ||
            mutableConnection.value.status == PeerConnectionStatus.DISCONNECTING
        ) {
            return
        }

        val manager = wifiP2pManager ?: run {
            mutableConnection.value = PeerConnectionSnapshot(
                status = PeerConnectionStatus.FAILED,
                selectedPeerId = peerId,
                locallyInitiated = true,
                failure = PeerConnectionFailure.UNSUPPORTED,
            )
            return
        }
        val activeChannel = channel ?: run {
            mutableConnection.value = PeerConnectionSnapshot(
                status = PeerConnectionStatus.FAILED,
                selectedPeerId = peerId,
                locallyInitiated = true,
                failure = PeerConnectionFailure.PLATFORM_ERROR,
            )
            return
        }

        clearConnectionTimeout()
        val requestGeneration = ++connectionGeneration
        mutableConnection.value = PeerConnectionSnapshot(
            status = PeerConnectionStatus.CONNECTING,
            selectedPeerId = peerId,
            locallyInitiated = true,
        )
        scheduleConnectionTimeout(requestGeneration)

        val config = WifiP2pConfig().apply {
            deviceAddress = peerId
        }
        try {
            manager.connect(
                activeChannel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrentConnection(requestGeneration)) return
                        // ActionListener success only means Android accepted the request.
                        // Group formation is confirmed separately by the P2P broadcast
                        // followed by requestConnectionInfo().
                        stopDiscovery()
                    }

                    override fun onFailure(reason: Int) {
                        if (!isCurrentConnection(requestGeneration)) return
                        failConnection(mapConnectionFailure(reason))
                    }
                },
            )
        } catch (_: SecurityException) {
            if (isCurrentConnection(requestGeneration)) {
                failConnection(PeerConnectionFailure.PERMISSION_DENIED)
            }
        } catch (_: RuntimeException) {
            if (isCurrentConnection(requestGeneration)) {
                failConnection(PeerConnectionFailure.PLATFORM_ERROR)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleP2pConnectionChanged() {
        if (!monitoring || !hasRequiredRuntimePermission()) return
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        val requestGeneration = connectionGeneration

        try {
            manager.requestConnectionInfo(
                activeChannel,
                WifiP2pManager.ConnectionInfoListener { info ->
                    if (!isCurrentConnection(requestGeneration)) {
                        return@ConnectionInfoListener
                    }

                    val ownerAddress = info.groupOwnerAddress?.hostAddress
                    if (PeerConnectionStatePolicy.isGroupFormed(
                            info.groupFormed,
                            ownerAddress,
                        )
                    ) {
                        clearConnectionTimeout()
                        val previous = mutableConnection.value
                        mutableConnection.value = PeerConnectionSnapshot(
                            status = PeerConnectionStatus.GROUP_FORMED,
                            selectedPeerId = previous.selectedPeerId,
                            isGroupOwner = info.isGroupOwner,
                            groupOwnerAddress = ownerAddress,
                            locallyInitiated = previous.locallyInitiated,
                        )
                    } else if (mutableConnection.value.status == PeerConnectionStatus.GROUP_FORMED) {
                        clearConnectionTimeout()
                        mutableConnection.value = PeerConnectionSnapshot()
                    }
                },
            )
        } catch (_: SecurityException) {
            if (mutableConnection.value.status != PeerConnectionStatus.IDLE) {
                failConnection(PeerConnectionFailure.PERMISSION_DENIED)
            }
        } catch (_: RuntimeException) {
            if (mutableConnection.value.status != PeerConnectionStatus.IDLE) {
                failConnection(PeerConnectionFailure.PLATFORM_ERROR)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        clearConnectionTimeout()
        val previous = mutableConnection.value
        if (previous.status == PeerConnectionStatus.IDLE) return

        val manager = wifiP2pManager
        val activeChannel = channel
        val requestGeneration = ++connectionGeneration
        mutableConnection.value = previous.copy(
            status = PeerConnectionStatus.DISCONNECTING,
            groupOwnerAddress = null,
            isGroupOwner = null,
            failure = null,
        )

        if (manager == null || activeChannel == null) {
            mutableConnection.value = PeerConnectionSnapshot()
            return
        }

        try {
            manager.removeGroup(
                activeChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (connectionGeneration == requestGeneration) {
                            mutableConnection.value = PeerConnectionSnapshot()
                        }
                    }

                    override fun onFailure(reason: Int) {
                        if (connectionGeneration == requestGeneration) {
                            mutableConnection.value = PeerConnectionSnapshot(
                                status = PeerConnectionStatus.FAILED,
                                selectedPeerId = previous.selectedPeerId,
                                failure = mapConnectionFailure(reason),
                            )
                        }
                    }
                },
            )
        } catch (_: SecurityException) {
            if (connectionGeneration == requestGeneration) {
                mutableConnection.value = PeerConnectionSnapshot(
                    status = PeerConnectionStatus.FAILED,
                    selectedPeerId = previous.selectedPeerId,
                    failure = PeerConnectionFailure.PERMISSION_DENIED,
                )
            }
        } catch (_: RuntimeException) {
            if (connectionGeneration == requestGeneration) {
                mutableConnection.value = PeerConnectionSnapshot(
                    status = PeerConnectionStatus.FAILED,
                    selectedPeerId = previous.selectedPeerId,
                    failure = PeerConnectionFailure.PLATFORM_ERROR,
                )
            }
        }
    }

    private fun scheduleConnectionTimeout(expectedGeneration: Long) {
        clearConnectionTimeout()
        val timeoutTask = Runnable {
            connectionTimeoutTask = null
            if (isCurrentConnection(expectedGeneration) &&
                mutableConnection.value.status == PeerConnectionStatus.CONNECTING
            ) {
                failConnection(PeerConnectionFailure.CONNECTION_TIMEOUT)
            }
        }
        connectionTimeoutTask = timeoutTask
        mainHandler.postDelayed(timeoutTask, P2P_CONNECTION_TIMEOUT_MS)
    }

    private fun clearConnectionTimeout() {
        connectionTimeoutTask?.let(mainHandler::removeCallbacks)
        connectionTimeoutTask = null
    }

    private fun isCurrentConnection(expectedGeneration: Long): Boolean =
        connectionGeneration == expectedGeneration &&
            monitoring &&
            receiverRegistered &&
            hasRequiredRuntimePermission()

    private fun failConnection(failure: PeerConnectionFailure) {
        clearConnectionTimeout()
        val previous = mutableConnection.value
        connectionGeneration += 1
        mutableConnection.value = PeerConnectionSnapshot(
            status = PeerConnectionStatus.FAILED,
            selectedPeerId = previous.selectedPeerId,
            locallyInitiated = previous.locallyInitiated,
            failure = failure,
        )
        removeP2pGroupBestEffort()
    }

    @SuppressLint("MissingPermission")
    private fun removeP2pGroupBestEffort() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        try {
            manager.removeGroup(
                activeChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                },
            )
        } catch (_: SecurityException) {
            // Best effort: clear local session state even if platform permission was revoked.
        } catch (_: RuntimeException) {
            // Best effort cleanup must not crash the discovery lifecycle.
        }
    }

    private fun clearConnectionState() {
        clearConnectionTimeout()
        connectionGeneration += 1
        mutableConnection.value = PeerConnectionSnapshot()
    }

    private fun mapConnectionFailure(reason: Int): PeerConnectionFailure = when (reason) {
        WifiP2pManager.BUSY -> PeerConnectionFailure.BUSY
        WifiP2pManager.P2P_UNSUPPORTED -> PeerConnectionFailure.UNSUPPORTED
        else -> PeerConnectionFailure.PLATFORM_ERROR
    }

    override fun startDiscovery(role: DiscoveryRole) {
        val currentStatus = mutableSnapshot.value.status
        val isActive = currentStatus == DiscoveryStatus.STARTING ||
            currentStatus == DiscoveryStatus.ADVERTISING ||
            currentStatus == DiscoveryStatus.DISCOVERING

        if (isActive && activeRole == role) return
        if (isActive) stopDiscovery()

        if (cleanupInProgress || cleanupRequested) {
            restartRoleAfterCleanup = role
            return
        }

        refreshAvailability()
        if (mutableSnapshot.value.availability != WifiDirectAvailability.READY) return
        if (cleanupInProgress || cleanupRequested) {
            restartRoleAfterCleanup = role
            return
        }

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
        activeRole = role
        clearPendingPeers()
        sourceTokensByAddress.clear()
        sourceDiagnosticsByToken.clear()
        missingSourceAddressCount = 0
        untrackedSourceCount = 0
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = DiscoveryStatus.STARTING,
            peers = emptyList(),
            failure = null,
            failureOperation = null,
            serviceResponseCount = 0,
            acceptedServiceResponseCount = 0,
            txtRecordResponseCount = 0,
            acceptedTxtDomainCount = 0,
            compatibleTxtRecordCount = 0,
            localServiceRegistered = false,
            serviceRequestRegistered = false,
            discoveryStarted = false,
            platformDiscoveryState = PlatformDiscoveryState.UNKNOWN,
            discoveryStopCount = 0,
            discoveryRestartCount = 0,
            sourceDiagnostics = emptyList(),
            missingSourceAddressCount = 0,
            untrackedSourceCount = 0,
        )
        serviceDiscoveryLifecycle.beginScan()

        try {
            manager.setDnsSdResponseListeners(
                    activeChannel,
                    WifiP2pManager.DnsSdServiceResponseListener {
                            instanceName,
                            registrationType,
                            device,
                        ->
                        if (isCurrent(requestGeneration)) {
                            mutableSnapshot.value = mutableSnapshot.value.copy(
                                serviceResponseCount = mutableSnapshot.value.serviceResponseCount + 1,
                            )
                            val sourceToken = sourceTokenFor(device.deviceAddress)
                            sourceToken?.let { token ->
                                updateSourceDiagnostics(token) { summary ->
                                    summary.copy(
                                        serviceResponseCount = summary.serviceResponseCount + 1,
                                    )
                                }
                            }

                            val compatible =
                                PeyvandDiscoveryAdvertisement.isCompatibleInstanceName(instanceName) &&
                                    PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType(registrationType)
                            if (compatible) {
                                mutableSnapshot.value = mutableSnapshot.value.copy(
                                    acceptedServiceResponseCount =
                                        mutableSnapshot.value.acceptedServiceResponseCount + 1,
                                )
                                sourceToken?.let { token ->
                                    updateSourceDiagnostics(token) { summary ->
                                        summary.copy(
                                            acceptedServiceResponseCount =
                                                summary.acceptedServiceResponseCount + 1,
                                        )
                                    }
                                }

                                val candidate = candidateRegistry.recordServiceResponse(
                                    deviceAddress = device.deviceAddress,
                                    instanceName = instanceName,
                                    displayName = device.deviceName,
                                )
                                if (candidate != null) {
                                    publishCandidates(requestGeneration)
                                    sourceToken?.let { token ->
                                        updateSourceDiagnostics(token) { summary ->
                                            summary.copy(candidatePublished = true)
                                        }
                                    }
                                }
                            } else {
                                Log.d(
                                    LOG_TAG,
                                    "DNS-SD service response rejected by PEYVAND name/type validation",
                                )
                            }
                        }
                    },
                    WifiP2pManager.DnsSdTxtRecordListener {
                            fullDomain,
                            record,
                            device,
                        ->
                        if (isCurrent(requestGeneration)) {
                            mutableSnapshot.value = mutableSnapshot.value.copy(
                                txtRecordResponseCount = mutableSnapshot.value.txtRecordResponseCount + 1,
                            )
                            val sourceToken = sourceTokenFor(device.deviceAddress)
                            sourceToken?.let { token ->
                                updateSourceDiagnostics(token) { summary ->
                                    summary.copy(
                                        txtRecordResponseCount = summary.txtRecordResponseCount + 1,
                                    )
                                }
                            }

                            val instanceName =
                                PeyvandDiscoveryAdvertisement.instanceNameFromFullDomain(fullDomain)
                            if (instanceName != null) {
                                mutableSnapshot.value = mutableSnapshot.value.copy(
                                    acceptedTxtDomainCount =
                                        mutableSnapshot.value.acceptedTxtDomainCount + 1,
                                )
                                sourceToken?.let { token ->
                                    updateSourceDiagnostics(token) { summary ->
                                        summary.copy(
                                            acceptedTxtDomainCount =
                                                summary.acceptedTxtDomainCount + 1,
                                        )
                                    }
                                }

                                val recordMap = record.toMap()
                                val metadataCompatible =
                                    PeyvandDiscoveryAdvertisement.isCompatible(recordMap)
                                if (metadataCompatible) {
                                    mutableSnapshot.value = mutableSnapshot.value.copy(
                                        compatibleTxtRecordCount =
                                            mutableSnapshot.value.compatibleTxtRecordCount + 1,
                                    )
                                    sourceToken?.let { token ->
                                        updateSourceDiagnostics(token) { summary ->
                                            summary.copy(
                                                compatibleTxtRecordCount =
                                                    summary.compatibleTxtRecordCount + 1,
                                            )
                                        }
                                    }
                                } else {
                                    Log.d(
                                        LOG_TAG,
                                        "DNS-SD TXT response has a valid PEYVAND domain but incompatible app/schema metadata",
                                    )
                                }

                                val candidate = candidateRegistry.recordTxtResponse(
                                    deviceAddress = device.deviceAddress,
                                    instanceName = instanceName,
                                    isCompatible = metadataCompatible,
                                )
                                if (candidate != null) {
                                    publishCandidates(requestGeneration)
                                    sourceToken?.let { token ->
                                        updateSourceDiagnostics(token) { summary ->
                                            summary.copy(candidatePublished = true)
                                        }
                                    }
                                }
                            } else {
                                Log.d(
                                    LOG_TAG,
                                    "DNS-SD TXT response rejected by PEYVAND domain validation",
                                )
                            }
                        }
                    },
                )

            // Sender and receiver screens share the same discovery contract:
            // advertise PEYVAND presence, then browse for nearby PEYVAND peers.
            registerLocalServiceWithRetry(manager, activeChannel, requestGeneration)

        } catch (_: SecurityException) {
            setPermissionRequired(requestGeneration)
        } catch (_: RuntimeException) {
            setFailure(DeviceDiscoveryFailure.PLATFORM_ERROR, requestGeneration)
        }
    }

    private fun registerLocalServiceWithRetry(
        manager: WifiP2pManager,
        activeChannel: WifiP2pManager.Channel,
        expectedGeneration: Long,
        retryAttempt: Int = 0,
    ) {
        try {
            manager.addLocalService(
                activeChannel,
                serviceInfo,
                actionListener(
                    expectedGeneration = expectedGeneration,
                    operation = DiscoveryOperation.ADD_LOCAL_SERVICE,
                    retryAttempt = retryAttempt,
                    onBusyRetry = { nextAttempt ->
                        registerLocalServiceWithRetry(
                            manager,
                            activeChannel,
                            expectedGeneration,
                            nextAttempt,
                        )
                    },
                ) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        status = DiscoveryStatus.ADVERTISING,
                        failure = null,
                    )
                    registerServiceRequestWithRetry(
                        manager,
                        activeChannel,
                        expectedGeneration,
                    )
                },
            )
        } catch (_: SecurityException) {
            setPermissionRequired(expectedGeneration)
        } catch (_: RuntimeException) {
            setFailure(
                DeviceDiscoveryFailure.PLATFORM_ERROR,
                expectedGeneration,
                failureOperation = DiscoveryOperation.ADD_LOCAL_SERVICE,
            )
        }
    }

    private fun registerServiceRequestWithRetry(
        manager: WifiP2pManager,
        activeChannel: WifiP2pManager.Channel,
        expectedGeneration: Long,
        retryAttempt: Int = 0,
    ) {
        try {
            manager.addServiceRequest(
                activeChannel,
                serviceRequest,
                actionListener(
                    expectedGeneration = expectedGeneration,
                    operation = DiscoveryOperation.ADD_SERVICE_REQUEST,
                    retryAttempt = retryAttempt,
                    onBusyRetry = { nextAttempt ->
                        registerServiceRequestWithRetry(
                            manager,
                            activeChannel,
                            expectedGeneration,
                            nextAttempt,
                        )
                    },
                ) {
                    startServiceDiscoveryWithRetry(
                        manager,
                        activeChannel,
                        expectedGeneration,
                    )
                },
            )
        } catch (_: SecurityException) {
            setPermissionRequired(expectedGeneration)
        } catch (_: RuntimeException) {
            setFailure(
                DeviceDiscoveryFailure.PLATFORM_ERROR,
                expectedGeneration,
                failureOperation = DiscoveryOperation.ADD_SERVICE_REQUEST,
            )
        }
    }

    private fun startServiceDiscoveryWithRetry(
        manager: WifiP2pManager,
        activeChannel: WifiP2pManager.Channel,
        expectedGeneration: Long,
        retryAttempt: Int = 0,
        isRearm: Boolean = false,
    ) {
        if (!isCurrent(expectedGeneration) ||
            activeRole == null ||
            cleanupInProgress ||
            cleanupRequested ||
            serviceDiscoveryStartInFlight
        ) {
            return
        }

        serviceDiscoveryStartInFlight = true
        if (isRearm) {
            serviceDiscoveryLifecycle.onRearmRequested(expectedGeneration)
        }

        try {
            manager.discoverServices(
                activeChannel,
                actionListener(
                    expectedGeneration = expectedGeneration,
                    operation = DiscoveryOperation.DISCOVER_SERVICES,
                    retryAttempt = retryAttempt,
                    onBusyRetry = { nextAttempt ->
                        serviceDiscoveryStartInFlight = false
                        startServiceDiscoveryWithRetry(
                            manager,
                            activeChannel,
                            expectedGeneration,
                            retryAttempt = nextAttempt,
                            isRearm = isRearm,
                        )
                    },
                ) {
                    serviceDiscoveryStartInFlight = false
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        status = DiscoveryStatus.DISCOVERING,
                        failure = null,
                    )
                    serviceDiscoveryLifecycle.onDiscoveryRequestAccepted(
                        expectedGeneration = expectedGeneration,
                        isRearm = isRearm,
                    )
                },
            )
        } catch (_: SecurityException) {
            serviceDiscoveryStartInFlight = false
            setPermissionRequired(expectedGeneration)
        } catch (_: RuntimeException) {
            serviceDiscoveryStartInFlight = false
            setFailure(
                DeviceDiscoveryFailure.PLATFORM_ERROR,
                expectedGeneration,
                failureOperation = DiscoveryOperation.DISCOVER_SERVICES,
            )
        }
    }

    override fun stopDiscovery() {
        cancelDiscoveryRetryTasks()
        serviceDiscoveryLifecycle.cancelPendingWork()
        serviceDiscoveryStartInFlight = false
        val wasActive = mutableSnapshot.value.status.let {
            it == DiscoveryStatus.STARTING ||
                it == DiscoveryStatus.ADVERTISING ||
                it == DiscoveryStatus.DISCOVERING
        }
        restartRoleAfterCleanup = null
        activeRole = null
        generation += 1
        clearPendingPeers()
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = DiscoveryStatus.IDLE,
            peers = emptyList(),
            platformDiscoveryState = PlatformDiscoveryState.UNKNOWN,
            failure = null,
            failureOperation = null,
        )
        // Activity/background teardown can call stopMonitoring while discovery
        // is already idle. Avoid sending redundant P2P cleanup requests then.
        if (wasActive) releaseDiscoveryRegistrations()
    }

    @SuppressLint("MissingPermission")
    private fun releaseDiscoveryRegistrations() {
        if (cleanupInProgress) {
            cleanupRequested = true
            return
        }
        cleanupInProgress = true
        beginCleanupPass()
    }

    @SuppressLint("MissingPermission")
    private fun beginCleanupPass() {
        val manager = wifiP2pManager
        val activeChannel = channel
        if (manager == null || activeChannel == null) {
            cleanupInProgress = false
            cleanupRequested = false
            restartRoleAfterCleanup = null
            return
        }

        cleanupRequested = false
        val passId = ++cleanupPassId
        // Serialize framework mutations. P2P service cleanup calls are not
        // submitted concurrently, which avoids self-inflicted BUSY responses.
        cleanupCallbacksRemaining = 1
        requestClearServiceRequests(
            passId,
            manager,
            activeChannel,
            onComplete = {
                requestClearLocalServices(
                    passId,
                    manager,
                    activeChannel,
                    onComplete = { finishCleanupAction(passId) },
                )
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestClearServiceRequests(
        passId: Long,
        manager: WifiP2pManager,
        activeChannel: WifiP2pManager.Channel,
        onComplete: () -> Unit,
        retryAttempt: Int = 0,
    ) {
        try {
            manager.clearServiceRequests(
                activeChannel,
                cleanupActionListener(
                    passId,
                    DiscoveryOperation.CLEAR_SERVICE_REQUESTS,
                    retryAttempt,
                    onComplete,
                ) { nextAttempt ->
                    requestClearServiceRequests(
                        passId,
                        manager,
                        activeChannel,
                        onComplete,
                        nextAttempt,
                    )
                },
            )
        } catch (exception: SecurityException) {
            Log.w(LOG_TAG, "P2P cleanup clearServiceRequests denied", exception)
            onComplete()
        } catch (exception: RuntimeException) {
            Log.w(LOG_TAG, "P2P cleanup clearServiceRequests failed", exception)
            onComplete()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestClearLocalServices(
        passId: Long,
        manager: WifiP2pManager,
        activeChannel: WifiP2pManager.Channel,
        onComplete: () -> Unit,
        retryAttempt: Int = 0,
    ) {
        try {
            manager.clearLocalServices(
                activeChannel,
                cleanupActionListener(
                    passId,
                    DiscoveryOperation.CLEAR_LOCAL_SERVICES,
                    retryAttempt,
                    onComplete,
                ) { nextAttempt ->
                    requestClearLocalServices(
                        passId,
                        manager,
                        activeChannel,
                        onComplete,
                        nextAttempt,
                    )
                },
            )
        } catch (exception: SecurityException) {
            Log.w(LOG_TAG, "P2P cleanup clearLocalServices denied", exception)
            onComplete()
        } catch (exception: RuntimeException) {
            Log.w(LOG_TAG, "P2P cleanup clearLocalServices failed", exception)
            onComplete()
        }
    }

    private fun cleanupActionListener(
        passId: Long,
        operation: DiscoveryOperation,
        retryAttempt: Int,
        onComplete: () -> Unit,
        onBusyRetry: (Int) -> Unit,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (passId != cleanupPassId || !cleanupInProgress) return
            Log.d(LOG_TAG, "Wi-Fi Direct cleanup operation $operation succeeded")
            onComplete()
        }

        override fun onFailure(reason: Int) {
            if (passId != cleanupPassId || !cleanupInProgress) return
            val nextAttempt = retryAttempt + 1
            val retryDelay = if (reason == WifiP2pManager.BUSY) {
                WifiDirectBusyRetryPolicy.delayMillisForRetry(nextAttempt)
            } else {
                null
            }
            if (retryDelay != null) {
                Log.w(
                    LOG_TAG,
                    "Wi-Fi Direct cleanup operation $operation is BUSY; retry $nextAttempt in ${retryDelay}ms",
                )
                scheduleCleanupRetry(passId, retryDelay) { onBusyRetry(nextAttempt) }
                return
            }

            Log.w(
                LOG_TAG,
                "Wi-Fi Direct cleanup operation $operation failed with framework reason=$reason",
            )
            onComplete()
        }
    }

    private fun scheduleCleanupRetry(
        expectedPassId: Long,
        delayMillis: Long,
        action: () -> Unit,
    ) {
        lateinit var task: Runnable
        task = Runnable {
            pendingCleanupRetryTasks.remove(task)
            if (cleanupInProgress && cleanupPassId == expectedPassId) action()
        }
        pendingCleanupRetryTasks += task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun cancelCleanupRetryTasks() {
        pendingCleanupRetryTasks.forEach(mainHandler::removeCallbacks)
        pendingCleanupRetryTasks.clear()
    }

    private fun finishCleanupAction(passId: Long) {
        if (passId != cleanupPassId || !cleanupInProgress) return
        if (cleanupCallbacksRemaining <= 0) return

        cleanupCallbacksRemaining -= 1
        if (cleanupCallbacksRemaining > 0) return
        if (cleanupRequested) {
            beginCleanupPass()
            return
        }

        cleanupInProgress = false
        val roleToRestart = restartRoleAfterCleanup
        restartRoleAfterCleanup = null
        if (roleToRestart != null && monitoring) {
            startDiscovery(roleToRestart)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestP2pStateIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !monitoring ||
            !receiverRegistered ||
            p2pEnabled != null ||
            p2pStateQueryInFlight ||
            p2pStateQueryFailed
        ) return

        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        val queryGeneration = ++p2pStateQueryGeneration
        p2pStateQueryInFlight = true

        val timeoutTask = Runnable {
            if (isCurrentP2pStateQuery(queryGeneration, activeChannel)) {
                p2pStateQueryInFlight = false
                p2pStateQueryTimeoutTask = null
                p2pStateQueryFailed = true
                publishP2pStateQueryFailure("timed out")
            }
        }
        p2pStateQueryTimeoutTask = timeoutTask
        mainHandler.postDelayed(timeoutTask, P2P_STATE_QUERY_TIMEOUT_MS)

        try {
            manager.requestP2pState(
                activeChannel,
                object : WifiP2pManager.P2pStateListener {
                    override fun onP2pStateAvailable(state: Int) {
                        if (!isCurrentP2pStateQuery(queryGeneration, activeChannel)) return
                        finishP2pStateQuery(queryGeneration)
                        p2pEnabled = when (state) {
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED -> true
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED -> false
                            else -> null
                        }
                        if (p2pEnabled == null) {
                            p2pStateQueryFailed = true
                            publishP2pStateQueryFailure("returned an unknown state")
                        } else {
                            p2pStateQueryFailed = false
                            refreshAvailability()
                        }
                    }
                },
            )
        } catch (exception: SecurityException) {
            failP2pStateQuery(queryGeneration, activeChannel, "permission denied", exception)
        } catch (exception: RuntimeException) {
            failP2pStateQuery(queryGeneration, activeChannel, "platform error", exception)
        }
    }

    private fun isCurrentP2pStateQuery(
        expectedGeneration: Long,
        expectedChannel: WifiP2pManager.Channel,
    ): Boolean =
        monitoring &&
            receiverRegistered &&
            p2pStateQueryInFlight &&
            p2pStateQueryGeneration == expectedGeneration &&
            channel === expectedChannel &&
            hasRequiredRuntimePermission()

    private fun finishP2pStateQuery(expectedGeneration: Long) {
        if (p2pStateQueryGeneration != expectedGeneration) return
        p2pStateQueryInFlight = false
        p2pStateQueryTimeoutTask?.let(mainHandler::removeCallbacks)
        p2pStateQueryTimeoutTask = null
    }

    private fun failP2pStateQuery(
        expectedGeneration: Long,
        expectedChannel: WifiP2pManager.Channel,
        reason: String,
        exception: RuntimeException? = null,
    ) {
        if (!isCurrentP2pStateQuery(expectedGeneration, expectedChannel)) return
        finishP2pStateQuery(expectedGeneration)
        p2pStateQueryFailed = true
        if (exception != null) {
            Log.w(LOG_TAG, "Wi-Fi P2P state query failed: $reason", exception)
        } else {
            Log.w(LOG_TAG, "Wi-Fi P2P state query failed: $reason")
        }
        publishP2pStateQueryFailure(reason)
    }

    private fun publishP2pStateQueryFailure(reason: String) {
        Log.w(LOG_TAG, "Wi-Fi P2P state is unavailable: $reason")
        mutableSnapshot.value = mutableSnapshot.value.copy(
            availability = WifiDirectAvailability.UNAVAILABLE,
            status = DiscoveryStatus.FAILED,
            peers = emptyList(),
            failure = DeviceDiscoveryFailure.PLATFORM_ERROR,
            failureOperation = null,
        )
    }

    private fun invalidateP2pStateQuery() {
        p2pStateQueryGeneration += 1
        p2pStateQueryInFlight = false
        p2pStateQueryFailed = false
        p2pStateQueryTimeoutTask?.let(mainHandler::removeCallbacks)
        p2pStateQueryTimeoutTask = null
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

        // The P2P state query requires a live framework channel. Do not show
        // CHECKING forever when channel initialization itself failed.
        if (channel == null) {
            return WifiDirectAvailability.UNAVAILABLE
        }

        if (p2pEnabled == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return if (p2pStateQueryFailed) {
                WifiDirectAvailability.UNAVAILABLE
            } else {
                WifiDirectAvailability.CHECKING
            }
        }

        // requestP2pState() is unavailable before API 29. If the initial
        // state broadcast was not returned, attempt the supported operation
        // and use its ActionListener result instead of waiting indefinitely.
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

    private fun scheduleDiscoveryRetry(
        expectedGeneration: Long,
        delayMillis: Long,
        action: () -> Unit,
    ) {
        lateinit var task: Runnable
        task = Runnable {
            pendingDiscoveryRetryTasks.remove(task)
            if (isCurrent(expectedGeneration) && !cleanupInProgress && !cleanupRequested) {
                action()
            }
        }
        pendingDiscoveryRetryTasks += task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun cancelDiscoveryRetryTasks() {
        pendingDiscoveryRetryTasks.forEach(mainHandler::removeCallbacks)
        pendingDiscoveryRetryTasks.clear()
    }

    private fun actionListener(
        expectedGeneration: Long,
        operation: DiscoveryOperation,
        retryAttempt: Int = 0,
        onBusyRetry: ((Int) -> Unit)? = null,
        onSuccess: () -> Unit,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (isCurrent(expectedGeneration)) {
                recordActionSuccess(expectedGeneration, operation)
                onSuccess()
            }
        }

        override fun onFailure(reason: Int) {
            if (!isCurrent(expectedGeneration)) return
            val nextAttempt = retryAttempt + 1
            val retryDelay = if (reason == WifiP2pManager.BUSY && onBusyRetry != null) {
                WifiDirectBusyRetryPolicy.delayMillisForRetry(nextAttempt)
            } else {
                null
            }
            if (retryDelay != null) {
                Log.w(
                    LOG_TAG,
                    "Wi-Fi Direct operation $operation is BUSY; retry $nextAttempt in ${retryDelay}ms",
                )
                scheduleDiscoveryRetry(expectedGeneration, retryDelay) {
                    onBusyRetry?.invoke(nextAttempt)
                }
                return
            }
            handlePlatformFailure(expectedGeneration, reason, operation)
        }
    }

    private fun recordActionSuccess(
        expectedGeneration: Long,
        operation: DiscoveryOperation,
    ) {
        if (!isCurrent(expectedGeneration)) return
        Log.d(LOG_TAG, "Wi-Fi Direct operation $operation succeeded")

        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(
            localServiceRegistered = current.localServiceRegistered ||
                operation == DiscoveryOperation.ADD_LOCAL_SERVICE,
            serviceRequestRegistered = current.serviceRequestRegistered ||
                operation == DiscoveryOperation.ADD_SERVICE_REQUEST,
            discoveryStarted = current.discoveryStarted ||
                operation == DiscoveryOperation.DISCOVER_SERVICES,
        )
    }

    private fun handlePlatformFailure(
        expectedGeneration: Long,
        reason: Int,
        operation: DiscoveryOperation,
    ) {
        if (!isCurrent(expectedGeneration)) return
        Log.w(LOG_TAG, "Wi-Fi Direct operation $operation failed with framework reason=$reason")

        when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> {
                setFailure(
                    failure = DeviceDiscoveryFailure.UNSUPPORTED,
                    expectedGeneration = expectedGeneration,
                    availability = WifiDirectAvailability.UNSUPPORTED,
                    failureOperation = operation,
                )
            }

            WifiP2pManager.BUSY -> {
                setFailure(
                    DeviceDiscoveryFailure.BUSY,
                    expectedGeneration = expectedGeneration,
                    failureOperation = operation,
                )
            }

            else -> {
                setFailure(
                    DeviceDiscoveryFailure.PLATFORM_ERROR,
                    expectedGeneration = expectedGeneration,
                    failureOperation = operation,
                )
            }
        }
    }

    private fun publishCandidates(expectedGeneration: Long) {
        if (!isCurrent(expectedGeneration)) return
        mutableSnapshot.value = mutableSnapshot.value.copy(
            peers = candidateRegistry.snapshot(),
            failure = null,
        )
    }

    private fun sourceTokenFor(deviceAddress: String): String? {
        if (deviceAddress.isBlank()) {
            missingSourceAddressCount += 1
            mutableSnapshot.value = mutableSnapshot.value.copy(
                missingSourceAddressCount = missingSourceAddressCount,
            )
            Log.w(LOG_TAG, "DNS-SD callback omitted its source device address")
            return null
        }

        sourceTokensByAddress[deviceAddress]?.let { return it }
        if (sourceTokensByAddress.size >= MAX_PENDING_PEERS) {
            untrackedSourceCount += 1
            mutableSnapshot.value = mutableSnapshot.value.copy(
                untrackedSourceCount = untrackedSourceCount,
            )
            return null
        }

        val token = "P${sourceTokensByAddress.size + 1}"
        sourceTokensByAddress[deviceAddress] = token
        return token
    }

    private fun updateSourceDiagnostics(
        token: String,
        update: (DiscoverySourceDiagnostics) -> DiscoverySourceDiagnostics,
    ) {
        val current = sourceDiagnosticsByToken[token] ?: DiscoverySourceDiagnostics(token)
        val updated = update(current)
        sourceDiagnosticsByToken[token] = updated
        mutableSnapshot.value = mutableSnapshot.value.copy(
            sourceDiagnostics = sourceDiagnosticsByToken.values.toList(),
        )
    }

    private fun clearPendingPeers() {
        candidateRegistry.clear()
        sourceTokensByAddress.clear()
    }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        monitoring && generation == expectedGeneration

    private fun setUnavailable() {
        setFailure(
            failure = DeviceDiscoveryFailure.PLATFORM_ERROR,
            availability = WifiDirectAvailability.UNAVAILABLE,
        )
    }

    private fun setPermissionRequired(expectedGeneration: Long? = null) {
        setFailure(
            failure = DeviceDiscoveryFailure.PERMISSION_REQUIRED,
            expectedGeneration = expectedGeneration,
            availability = WifiDirectAvailability.PERMISSION_REQUIRED,
        )
    }

    private fun setFailure(
        failure: DeviceDiscoveryFailure,
        expectedGeneration: Long? = null,
        availability: WifiDirectAvailability? = null,
        failureOperation: DiscoveryOperation? = null,
    ) {
        if (expectedGeneration != null && !isCurrent(expectedGeneration)) return

        // Invalidate queued framework callbacks before requesting best-effort
        // teardown. A late DNS-SD response cannot repopulate a failed scan.
        generation += 1
        cancelDiscoveryRetryTasks()
        serviceDiscoveryLifecycle.cancelPendingWork()
        serviceDiscoveryStartInFlight = false
        activeRole = null
        restartRoleAfterCleanup = null
        clearPendingPeers()
        releaseDiscoveryRegistrations()

        mutableSnapshot.value = mutableSnapshot.value.copy(
            availability = availability ?: mutableSnapshot.value.availability,
            status = DiscoveryStatus.FAILED,
            failure = failure,
            failureOperation = failureOperation,
            peers = emptyList(),
            platformDiscoveryState = PlatformDiscoveryState.UNKNOWN,
        )
    }
}
