package com.peyvand.core.connectivity.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.peyvand.core.domain.network.NetworkEnvironmentRepository
import com.peyvand.core.domain.network.NetworkEnvironmentSnapshot
import com.peyvand.core.domain.network.VpnRoutePolicy
import com.peyvand.core.domain.network.VpnRouteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes only the default network applicable to this app UID.
 * It neither inspects VPN providers nor bypasses another app's VPN.
 */
class AndroidNetworkEnvironmentRepository(
    context: Context,
) : NetworkEnvironmentRepository {

    private val connectivityManager =
        context.applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE,
        ) as? ConnectivityManager

    private val mutableSnapshot = MutableStateFlow(NetworkEnvironmentSnapshot())
    override val snapshot = mutableSnapshot.asStateFlow()

    private var monitoring = false
    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            mutableSnapshot.value = NetworkEnvironmentSnapshot(
                vpnRouteStatus = VpnRoutePolicy.classify(
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                ),
            )
        }

        override fun onLost(network: Network) {
            refresh()
        }
    }

    override fun startMonitoring() {
        if (monitoring) return
        monitoring = true
        val manager = connectivityManager ?: run {
            publishUnknown()
            return
        }

        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            callbackRegistered = true
            refresh()
        } catch (_: SecurityException) {
            publishUnknown()
        } catch (_: RuntimeException) {
            publishUnknown()
        }
    }

    override fun stopMonitoring() {
        if (!monitoring && !callbackRegistered) return
        monitoring = false

        if (callbackRegistered) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (_: IllegalArgumentException) {
                // Already removed by the framework.
            } catch (_: RuntimeException) {
                // Do not let diagnostics prevent Activity teardown.
            } finally {
                callbackRegistered = false
            }
        }
        publishUnknown()
    }

    override fun refresh() {
        val manager = connectivityManager ?: run {
            publishUnknown()
            return
        }

        try {
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            mutableSnapshot.value = NetworkEnvironmentSnapshot(
                vpnRouteStatus = VpnRoutePolicy.classify(
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                ),
            )
        } catch (_: SecurityException) {
            publishUnknown()
        } catch (_: RuntimeException) {
            publishUnknown()
        }
    }

    private fun publishUnknown() {
        mutableSnapshot.value = NetworkEnvironmentSnapshot(
            vpnRouteStatus = VpnRouteStatus.UNKNOWN,
        )
    }
}
