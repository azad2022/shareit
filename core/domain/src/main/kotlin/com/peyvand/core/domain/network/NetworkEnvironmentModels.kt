package com.peyvand.core.domain.network

import kotlinx.coroutines.flow.StateFlow

/** App-default network VPN routing status; not a test of local peer reachability. */
enum class VpnRouteStatus {
    CHECKING,
    ACTIVE_FOR_APP,
    NOT_ACTIVE_FOR_APP,
    UNKNOWN,
}

data class NetworkEnvironmentSnapshot(
    val vpnRouteStatus: VpnRouteStatus = VpnRouteStatus.CHECKING,
)

interface NetworkEnvironmentRepository {
    val snapshot: StateFlow<NetworkEnvironmentSnapshot>
    fun startMonitoring()
    fun stopMonitoring()
    fun refresh()
}

object VpnRoutePolicy {
    fun classify(isVpnTransport: Boolean?): VpnRouteStatus = when (isVpnTransport) {
        true -> VpnRouteStatus.ACTIVE_FOR_APP
        false -> VpnRouteStatus.NOT_ACTIVE_FOR_APP
        null -> VpnRouteStatus.UNKNOWN
    }
}
