package com.peyvand.core.connectivity.wifi

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import com.peyvand.core.domain.connection.PeerConnectionFailure
import com.peyvand.core.domain.connection.PeerConnectionRepository
import com.peyvand.core.domain.connection.PeerConnectionSnapshot
import com.peyvand.core.domain.connection.PeerConnectionStatePolicy
import com.peyvand.core.domain.connection.PeerConnectionStatus
import com.peyvand.core.domain.discovery.DeviceDiscoverySnapshot
import com.peyvand.core.domain.discovery.DiscoveryMetadataStatus
import com.peyvand.core.domain.discovery.DiscoveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns only the Wi-Fi Direct group-connection lifecycle.
 *
 * It deliberately reuses the discovery repository's manager/channel and relies on
 * that repository's single P2P broadcast receiver. A formed P2P group is not an
 * authenticated peer session and must not authorize file metadata or file bytes.
 */
internal class WifiDirectPeerConnectionController(
    private val mainHandler: Handler,
    private val managerProvider: () -> WifiP2pManager?,
    private val channelProvider: () -> WifiP2pManager.Channel?,
    private val discoverySnapshotProvider: () -> DeviceDiscoverySnapshot,
    private val isMonitoring: () -> Boolean,
    private val isReceiverRegistered: () -> Boolean,
    private val hasRequiredRuntimePermission: () -> Boolean,
    private val onGroupFormed: () -> Unit,
    private val connectionTimeoutMillis: Long = DEFAULT_CONNECTION_TIMEOUT_MILLIS,
) : PeerConnectionRepository {
    private val mutableConnection = MutableStateFlow(PeerConnectionSnapshot())
    override val connection = mutableConnection.asStateFlow()

    private var generation = 0L
    private var timeoutTask: Runnable? = null

    init {
        require(connectionTimeoutMillis > 0L)
    }

    @SuppressLint("MissingPermission")
    override fun connectToPeer(peerId: String) {
        val previousStatus = mutableConnection.value.status
        if (previousStatus == PeerConnectionStatus.CONNECTING ||
            previousStatus == PeerConnectionStatus.GROUP_FORMED ||
            previousStatus == PeerConnectionStatus.DISCONNECTING
        ) {
            return
        }

        val discovery = discoverySnapshotProvider()
        val candidate = discovery.peers.firstOrNull { it.id == peerId }
        val isDiscoveryActive = discovery.status == DiscoveryStatus.STARTING ||
            discovery.status == DiscoveryStatus.ADVERTISING ||
            discovery.status == DiscoveryStatus.DISCOVERING

        if (candidate == null ||
            candidate.metadataStatus == DiscoveryMetadataStatus.INCOMPATIBLE ||
            !PeerConnectionStatePolicy.mayInitiate(
                peerId = peerId,
                currentlyDiscoveredPeerIds = discovery.peers.map { it.id },
                isDiscoveryActive = isDiscoveryActive,
            )
        ) {
            publishFailure(PeerConnectionFailure.INVALID_DISCOVERY_CANDIDATE, peerId)
            return
        }

        if (!hasRequiredRuntimePermission()) {
            publishFailure(PeerConnectionFailure.PERMISSION_DENIED, peerId)
            return
        }

        val manager = managerProvider()
        val activeChannel = channelProvider()
        if (manager == null || activeChannel == null) {
            publishFailure(PeerConnectionFailure.PLATFORM_ERROR, peerId)
            return
        }

        clearTimeout()
        val requestGeneration = ++generation
        mutableConnection.value = PeerConnectionSnapshot(
            status = PeerConnectionStatus.CONNECTING,
            selectedPeerId = peerId,
            locallyInitiated = true,
        )
        scheduleTimeout(requestGeneration)

        val config = WifiP2pConfig().apply {
            deviceAddress = peerId
        }
        try {
            manager.connect(
                activeChannel,
                config,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrent(requestGeneration)) return
                        // This callback only confirms that Android accepted the request.
                        // Actual group formation is confirmed via CONNECTION_CHANGED and
                        // requestConnectionInfo(). Do not tear down discovery on acceptance.
                    }

                    override fun onFailure(reason: Int) {
                        if (isCurrent(requestGeneration)) {
                            fail(mapFailure(reason))
                        }
                    }
                },
            )
        } catch (_: SecurityException) {
            if (isCurrent(requestGeneration)) fail(PeerConnectionFailure.PERMISSION_DENIED)
        } catch (_: RuntimeException) {
            if (isCurrent(requestGeneration)) fail(PeerConnectionFailure.PLATFORM_ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    fun onConnectionChanged() {
        if (!isMonitoring() || !isReceiverRegistered() || !hasRequiredRuntimePermission()) {
            return
        }
        val status = mutableConnection.value.status
        if (status == PeerConnectionStatus.FAILED ||
            status == PeerConnectionStatus.DISCONNECTING
        ) {
            return
        }

        val manager = managerProvider() ?: return
        val activeChannel = channelProvider() ?: return
        val requestGeneration = generation
        try {
            manager.requestConnectionInfo(
                activeChannel,
                WifiP2pManager.ConnectionInfoListener { info ->
                    if (!isCurrent(requestGeneration)) return@ConnectionInfoListener

                    val ownerAddress = info.groupOwnerAddress?.hostAddress
                    if (PeerConnectionStatePolicy.isGroupFormed(info.groupFormed, ownerAddress)) {
                        clearTimeout()
                        val previous = mutableConnection.value
                        mutableConnection.value = PeerConnectionSnapshot(
                            status = PeerConnectionStatus.GROUP_FORMED,
                            selectedPeerId = previous.selectedPeerId,
                            isGroupOwner = info.isGroupOwner,
                            groupOwnerAddress = ownerAddress,
                            locallyInitiated = previous.locallyInitiated,
                        )
                        // Releasing DNS-SD registrations is safe only after Android
                        // confirms that the P2P group has actually formed.
                        onGroupFormed()
                    } else if (mutableConnection.value.status == PeerConnectionStatus.GROUP_FORMED) {
                        clearTimeout()
                        mutableConnection.value = PeerConnectionSnapshot()
                    }
                },
            )
        } catch (_: SecurityException) {
            if (mutableConnection.value.status != PeerConnectionStatus.IDLE) {
                fail(PeerConnectionFailure.PERMISSION_DENIED)
            }
        } catch (_: RuntimeException) {
            if (mutableConnection.value.status != PeerConnectionStatus.IDLE) {
                fail(PeerConnectionFailure.PLATFORM_ERROR)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        clearTimeout()
        val previous = mutableConnection.value
        if (previous.status == PeerConnectionStatus.IDLE) return

        val manager = managerProvider()
        val activeChannel = channelProvider()
        val requestGeneration = ++generation
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
                        if (generation == requestGeneration) {
                            mutableConnection.value = PeerConnectionSnapshot()
                        }
                    }

                    override fun onFailure(reason: Int) {
                        if (generation == requestGeneration) {
                            publishFailure(mapFailure(reason), previous.selectedPeerId)
                        }
                    }
                },
            )
        } catch (_: SecurityException) {
            if (generation == requestGeneration) {
                publishFailure(PeerConnectionFailure.PERMISSION_DENIED, previous.selectedPeerId)
            }
        } catch (_: RuntimeException) {
            if (generation == requestGeneration) {
                publishFailure(PeerConnectionFailure.PLATFORM_ERROR, previous.selectedPeerId)
            }
        }
    }

    /** Called after channel loss or P2P disable; Android has already invalidated the group. */
    fun reset() {
        clearTimeout()
        generation += 1
        mutableConnection.value = PeerConnectionSnapshot()
    }

    private fun scheduleTimeout(expectedGeneration: Long) {
        clearTimeout()
        val task = Runnable {
            timeoutTask = null
            if (isCurrent(expectedGeneration) &&
                mutableConnection.value.status == PeerConnectionStatus.CONNECTING
            ) {
                fail(PeerConnectionFailure.CONNECTION_TIMEOUT)
            }
        }
        timeoutTask = task
        mainHandler.postDelayed(task, connectionTimeoutMillis)
    }

    private fun clearTimeout() {
        timeoutTask?.let(mainHandler::removeCallbacks)
        timeoutTask = null
    }

    private fun isCurrent(expectedGeneration: Long): Boolean =
        generation == expectedGeneration &&
            isMonitoring() &&
            isReceiverRegistered() &&
            hasRequiredRuntimePermission()

    private fun fail(failure: PeerConnectionFailure) {
        val previous = mutableConnection.value
        clearTimeout()
        generation += 1
        publishFailure(failure, previous.selectedPeerId, previous.locallyInitiated)
        removeGroupBestEffort()
    }

    private fun publishFailure(
        failure: PeerConnectionFailure,
        selectedPeerId: String? = null,
        locallyInitiated: Boolean = false,
    ) {
        clearTimeout()
        mutableConnection.value = PeerConnectionSnapshot(
            status = PeerConnectionStatus.FAILED,
            selectedPeerId = selectedPeerId,
            locallyInitiated = locallyInitiated,
            failure = failure,
        )
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupBestEffort() {
        val manager = managerProvider() ?: return
        val activeChannel = channelProvider() ?: return
        try {
            manager.removeGroup(
                activeChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                },
            )
        } catch (_: SecurityException) {
            // Best-effort cleanup after permission revocation.
        } catch (_: RuntimeException) {
            // Best-effort cleanup must not crash the connectivity lifecycle.
        }
    }

    private fun mapFailure(reason: Int): PeerConnectionFailure = when (reason) {
        WifiP2pManager.BUSY -> PeerConnectionFailure.BUSY
        WifiP2pManager.P2P_UNSUPPORTED -> PeerConnectionFailure.UNSUPPORTED
        else -> PeerConnectionFailure.PLATFORM_ERROR
    }

    private companion object {
        const val DEFAULT_CONNECTION_TIMEOUT_MILLIS = 30_000L
    }
}
