package com.peyvand.core.connectivity.wifi

import android.annotation.SuppressLint
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.util.Log
import com.peyvand.core.domain.discovery.DiscoveryRole
import com.peyvand.core.domain.discovery.PlatformDiscoveryState
import com.peyvand.core.domain.discovery.WifiDirectServiceDiscoveryRearmPolicy

/**
 * Owns the Android Wi-Fi P2P service-discovery lifecycle for the browser role.
 *
 * This controller does not register DNS-SD requests, own permission policy, or
 * decide peer compatibility. It observes platform scan state and schedules a
 * bounded re-arm through the repository's existing discoverServices operation.
 */
internal class WifiDirectServiceDiscoveryLifecycleController(
    private val mainHandler: Handler,
    private val managerProvider: () -> WifiP2pManager?,
    private val channelProvider: () -> WifiP2pManager.Channel?,
    private val activeScanGeneration: () -> Long,
    private val isCurrentScan: (Long) -> Boolean,
    private val isMonitoring: () -> Boolean,
    private val isReceiverRegistered: () -> Boolean,
    private val activeRoleProvider: () -> DiscoveryRole?,
    private val cleanupInProgress: () -> Boolean,
    private val cleanupRequested: () -> Boolean,
    private val startInFlight: () -> Boolean,
    private val discoveryRequestAccepted: () -> Boolean,
    private val hasRequiredPermission: () -> Boolean,
    private val onSnapshotChanged: (
        PlatformDiscoveryState,
        Int,
        Int,
    ) -> Unit,
    private val startRearm: (Long) -> Unit,
) {
    private companion object {
        const val LOG_TAG = "PeyvandP2P"
        const val DISCOVERY_STATE_QUERY_TIMEOUT_MS = 5_000L
    }

    private var platformState = PlatformDiscoveryState.UNKNOWN
    private var discoveryStopCount = 0
    private var discoveryRestartCount = 0
    private var consecutiveDiscoveryStops = 0

    private var stateQueryInFlight = false
    private var stateQueryGeneration = 0L
    private var stateQueryTimeoutTask: Runnable? = null
    private var pendingRearmTask: Runnable? = null

    fun beginScan() {
        cancelTasksAndQuery()
        platformState = PlatformDiscoveryState.UNKNOWN
        discoveryStopCount = 0
        discoveryRestartCount = 0
        consecutiveDiscoveryStops = 0
        publishState()
    }

    /** Cancel delayed work and stale queries without resetting per-scan counters. */
    fun cancelPendingWork() {
        cancelTasksAndQuery()
        consecutiveDiscoveryStops = 0
        platformState = PlatformDiscoveryState.UNKNOWN
        publishState()
    }

    fun cancelForChannelLoss() {
        cancelPendingWork()
        discoveryStopCount = 0
        discoveryRestartCount = 0
        publishState()
    }

    fun onRearmRequested(expectedGeneration: Long) {
        if (!isCurrentBrowserScan(expectedGeneration)) return
        // The old STOPPED state no longer describes the newly submitted request.
        platformState = PlatformDiscoveryState.UNKNOWN
        publishState()
    }

    fun onDiscoveryStateBroadcast(intent: Intent) {
        if (intent.action != WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION) return
        val state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)
        handlePlatformState(state, source = "broadcast")
    }

    fun onDiscoveryRequestAccepted(
        expectedGeneration: Long,
        isRearm: Boolean,
    ) {
        if (!isCurrentBrowserScan(expectedGeneration)) return

        if (isRearm) discoveryRestartCount += 1
        publishState()

        when (platformState) {
            PlatformDiscoveryState.STOPPED -> scheduleRearm(
                expectedGeneration,
                source = "state-already-stopped",
            )
            PlatformDiscoveryState.UNKNOWN -> {
                val activeChannel = channelProvider() ?: return
                requestDiscoveryStateIfSupported(expectedGeneration, activeChannel)
            }
            PlatformDiscoveryState.RUNNING -> Unit
        }
    }

    private fun handlePlatformState(state: Int, source: String) {
        val expectedGeneration = activeScanGeneration()
        if (!isCurrentBrowserScan(expectedGeneration)) return

        when (state) {
            WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED -> {
                invalidateStateQuery()
                consecutiveDiscoveryStops = 0
                cancelRearm()
                platformState = PlatformDiscoveryState.RUNNING
                publishState()
                Log.i(LOG_TAG, "Wi-Fi P2P service discovery is running ($source)")
            }

            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED -> {
                invalidateStateQuery()
                val newTransition = platformState != PlatformDiscoveryState.STOPPED
                if (newTransition) consecutiveDiscoveryStops += 1
                platformState = PlatformDiscoveryState.STOPPED
                if (newTransition) discoveryStopCount += 1
                publishState()
                Log.i(
                    LOG_TAG,
                    "Wi-Fi P2P service discovery stopped ($source); " +
                        "consecutiveStops=$consecutiveDiscoveryStops",
                )
                if (discoveryRequestAccepted() && !startInFlight()) {
                    scheduleRearm(expectedGeneration, source)
                }
            }

            else -> Log.w(LOG_TAG, "Ignoring unknown Wi-Fi P2P discovery state=$state ($source)")
        }
    }

    private fun scheduleRearm(expectedGeneration: Long, source: String) {
        if (!canRearm(expectedGeneration) || pendingRearmTask != null) return

        val stopCount = consecutiveDiscoveryStops.coerceAtLeast(1)
        val delayMillis = WifiDirectServiceDiscoveryRearmPolicy
            .delayMillisForStopCount(stopCount)

        lateinit var task: Runnable
        task = Runnable {
            if (pendingRearmTask === task) pendingRearmTask = null
            if (!canRearm(expectedGeneration)) return@Runnable

            Log.i(
                LOG_TAG,
                "Re-arming Wi-Fi P2P service discovery after STOPPED ($source); " +
                    "delayMs=$delayMillis, stopCount=$stopCount",
            )
            startRearm(expectedGeneration)
        }

        pendingRearmTask = task
        mainHandler.postDelayed(task, delayMillis)
    }

    private fun canRearm(expectedGeneration: Long): Boolean =
        isCurrentBrowserScan(expectedGeneration) &&
            WifiDirectServiceDiscoveryRearmPolicy.shouldRearm(
                activeRole = activeRoleProvider(),
                monitoring = isMonitoring(),
                cleanupInProgress = cleanupInProgress(),
                cleanupRequested = cleanupRequested(),
                startInFlight = startInFlight(),
                discoveryRequestAccepted = discoveryRequestAccepted(),
                platformState = platformState,
            )

    private fun cancelRearm() {
        pendingRearmTask?.let(mainHandler::removeCallbacks)
        pendingRearmTask = null
    }

    @SuppressLint("MissingPermission")
    private fun requestDiscoveryStateIfSupported(
        expectedGeneration: Long,
        expectedChannel: WifiP2pManager.Channel,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !isCurrentBrowserScan(expectedGeneration) ||
            stateQueryInFlight ||
            !isReceiverRegistered()
        ) {
            return
        }

        val manager = managerProvider() ?: return
        val queryGeneration = ++stateQueryGeneration
        stateQueryInFlight = true

        val timeoutTask = Runnable {
            if (isCurrentStateQuery(queryGeneration, expectedGeneration, expectedChannel)) {
                stateQueryInFlight = false
                stateQueryTimeoutTask = null
                Log.w(
                    LOG_TAG,
                    "Wi-Fi P2P discovery-state query timed out; waiting for framework broadcast",
                )
            }
        }
        stateQueryTimeoutTask = timeoutTask
        mainHandler.postDelayed(timeoutTask, DISCOVERY_STATE_QUERY_TIMEOUT_MS)

        try {
            manager.requestDiscoveryState(
                expectedChannel,
                object : WifiP2pManager.DiscoveryStateListener {
                    override fun onDiscoveryStateAvailable(state: Int) {
                        if (!isCurrentStateQuery(
                                queryGeneration,
                                expectedGeneration,
                                expectedChannel,
                            )
                        ) {
                            return
                        }
                        finishStateQuery(queryGeneration)
                        handlePlatformState(state, source = "requestDiscoveryState")
                    }
                },
            )
        } catch (_: SecurityException) {
            finishStateQuery(queryGeneration)
            Log.w(LOG_TAG, "Wi-Fi P2P discovery-state query was denied")
        } catch (_: RuntimeException) {
            finishStateQuery(queryGeneration)
            Log.w(LOG_TAG, "Wi-Fi P2P discovery-state query failed")
        }
    }

    private fun isCurrentStateQuery(
        expectedQueryGeneration: Long,
        expectedScanGeneration: Long,
        expectedChannel: WifiP2pManager.Channel,
    ): Boolean =
        isMonitoring() &&
            isReceiverRegistered() &&
            stateQueryInFlight &&
            stateQueryGeneration == expectedQueryGeneration &&
            isCurrentBrowserScan(expectedScanGeneration) &&
            channelProvider() === expectedChannel &&
            hasRequiredPermission()

    private fun finishStateQuery(expectedQueryGeneration: Long) {
        if (stateQueryGeneration != expectedQueryGeneration) return
        stateQueryInFlight = false
        stateQueryTimeoutTask?.let(mainHandler::removeCallbacks)
        stateQueryTimeoutTask = null
    }

    private fun invalidateStateQuery() {
        stateQueryGeneration += 1
        stateQueryInFlight = false
        stateQueryTimeoutTask?.let(mainHandler::removeCallbacks)
        stateQueryTimeoutTask = null
    }

    private fun cancelTasksAndQuery() {
        invalidateStateQuery()
        cancelRearm()
    }

    private fun isCurrentBrowserScan(expectedGeneration: Long): Boolean =
        isCurrentScan(expectedGeneration) &&
            activeRoleProvider() == DiscoveryRole.BROWSE_ONLY

    private fun publishState() {
        onSnapshotChanged(platformState, discoveryStopCount, discoveryRestartCount)
    }
}
