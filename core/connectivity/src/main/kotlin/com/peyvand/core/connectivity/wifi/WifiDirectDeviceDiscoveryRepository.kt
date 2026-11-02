package com.peyvand.core.connectivity.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import com.peyvand.core.domain.discovery.DeviceDiscoveryFailure
import com.peyvand.core.domain.discovery.DeviceDiscoveryRepository
import com.peyvand.core.domain.discovery.DiscoveryCandidateRegistry
import com.peyvand.core.domain.discovery.DiscoverySourceDiagnostics
import com.peyvand.core.domain.discovery.DeviceDiscoverySnapshot
import com.peyvand.core.domain.discovery.DiscoveryOperation
import com.peyvand.core.domain.discovery.DiscoveryRole
import com.peyvand.core.domain.discovery.DiscoveryStatus
import com.peyvand.core.domain.discovery.PeyvandDiscoveryAdvertisement
import com.peyvand.core.domain.discovery.WifiDirectAvailability
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
        const val LOG_TAG = "PeyvandP2P"
        const val MAX_PENDING_PEERS = 128
        const val MAX_VISIBLE_PEERS = 64
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
            if (intent.action != WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) return

            p2pEnabled = intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                WifiP2pManager.WIFI_P2P_STATE_DISABLED,
            ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

            if (p2pEnabled == false) {
                val wasDiscovering = mutableSnapshot.value.status.let {
                    it == DiscoveryStatus.STARTING ||
                        it == DiscoveryStatus.ADVERTISING ||
                        it == DiscoveryStatus.DISCOVERING
                }
                generation += 1
                clearPendingPeers()
                if (wasDiscovering) releaseDiscoveryRegistrations()
                activeRole = null
                restartRoleAfterCleanup = null
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    availability = WifiDirectAvailability.WIFI_P2P_DISABLED,
                    status = DiscoveryStatus.IDLE,
                    peers = emptyList(),
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
        }
    }

    override fun refreshAvailability() {
        val permissionGranted = hasRequiredRuntimePermission()
        if (permissionGranted) {
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
        } else {
            p2pEnabled = null
            unregisterP2pStateReceiver()
        }

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
            sourceDiagnostics = emptyList(),
            missingSourceAddressCount = 0,
            untrackedSourceCount = 0,
        )

        try {
            if (role == DiscoveryRole.BROWSE_ONLY) {
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
            }

            when (role) {
                DiscoveryRole.ADVERTISE_ONLY -> {
                    manager.addLocalService(
                        activeChannel,
                        serviceInfo,
                        actionListener(
                            requestGeneration,
                            DiscoveryOperation.ADD_LOCAL_SERVICE,
                        ) {
                            mutableSnapshot.value = mutableSnapshot.value.copy(
                                status = DiscoveryStatus.ADVERTISING,
                                failure = null,
                            )
                        },
                    )
                }

                DiscoveryRole.BROWSE_ONLY -> {
                    manager.addServiceRequest(
                        activeChannel,
                        serviceRequest,
                        actionListener(
                            requestGeneration,
                            DiscoveryOperation.ADD_SERVICE_REQUEST,
                        ) {
                            manager.discoverServices(
                                activeChannel,
                                actionListener(
                                    requestGeneration,
                                    DiscoveryOperation.DISCOVER_SERVICES,
                                ) {
                                    mutableSnapshot.value = mutableSnapshot.value.copy(
                                        status = DiscoveryStatus.DISCOVERING,
                                        failure = null,
                                    )
                                },
                            )
                        },
                    )
                }
            }
        } catch (_: SecurityException) {
            setPermissionRequired(requestGeneration)
        } catch (_: RuntimeException) {
            setFailure(DeviceDiscoveryFailure.PLATFORM_ERROR, requestGeneration)
        }
    }

    override fun stopDiscovery() {
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
            // A second stop/failure signal arrived while the framework was still
            // processing the first cleanup. Run one more pass before restart.
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
        cleanupCallbacksRemaining = 2

        // Keep both cleanup operations independent, but do not allow another
        // scan to register services until both asynchronous callbacks complete.
        try {
            manager.clearServiceRequests(
                activeChannel,
                cleanupActionListener(passId, DiscoveryOperation.CLEAR_SERVICE_REQUESTS),
            )
        } catch (exception: SecurityException) {
            Log.w(LOG_TAG, "P2P cleanup clearServiceRequests denied", exception)
            finishCleanupAction(passId)
        } catch (exception: RuntimeException) {
            Log.w(LOG_TAG, "P2P cleanup clearServiceRequests failed", exception)
            finishCleanupAction(passId)
        }

        try {
            manager.clearLocalServices(
                activeChannel,
                cleanupActionListener(passId, DiscoveryOperation.CLEAR_LOCAL_SERVICES),
            )
        } catch (exception: SecurityException) {
            Log.w(LOG_TAG, "P2P cleanup clearLocalServices denied", exception)
            finishCleanupAction(passId)
        } catch (exception: RuntimeException) {
            Log.w(LOG_TAG, "P2P cleanup clearLocalServices failed", exception)
            finishCleanupAction(passId)
        }
    }

    private fun cleanupActionListener(
        passId: Long,
        operation: DiscoveryOperation,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (passId != cleanupPassId) return
            Log.d(LOG_TAG, "Wi-Fi Direct cleanup operation $operation succeeded")
            finishCleanupAction(passId)
        }

        override fun onFailure(reason: Int) {
            if (passId != cleanupPassId) return
            Log.w(
                LOG_TAG,
                "Wi-Fi Direct cleanup operation $operation failed with framework reason=$reason",
            )
            finishCleanupAction(passId)
        }
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
        operation: DiscoveryOperation,
        onSuccess: () -> Unit,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            if (isCurrent(expectedGeneration)) {
                recordActionSuccess(expectedGeneration, operation)
                onSuccess()
            }
        }

        override fun onFailure(reason: Int) {
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
        )
    }
}
