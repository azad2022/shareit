package com.peyvand.core.domain.discovery

import kotlinx.coroutines.flow.StateFlow

enum class WifiDirectAvailability {
    CHECKING,
    READY,
    PERMISSION_REQUIRED,
    LOCATION_SERVICES_DISABLED,
    WIFI_P2P_DISABLED,
    UNSUPPORTED,
    UNAVAILABLE,
}

enum class DiscoveryStatus {
    IDLE,
    STARTING,
    DISCOVERING,
    FAILED,
}

enum class DeviceDiscoveryFailure {
    PERMISSION_REQUIRED,
    BUSY,
    UNSUPPORTED,
    PLATFORM_ERROR,
}

/**
 * A transient peer discovered during the current scan.
 *
 * [id] is an ephemeral platform identifier used only to correlate callbacks
 * within this process. It is not a trusted identity and must not be persisted.
 */
data class NearbyPeer(
    val id: String,
    val displayName: String?,
)

data class DeviceDiscoverySnapshot(
    val availability: WifiDirectAvailability = WifiDirectAvailability.CHECKING,
    val status: DiscoveryStatus = DiscoveryStatus.IDLE,
    val peers: List<NearbyPeer> = emptyList(),
    val failure: DeviceDiscoveryFailure? = null,
)

interface DeviceDiscoveryRepository {
    val snapshot: StateFlow<DeviceDiscoverySnapshot>

    /** Observe P2P system state only while the application is foregrounded. */
    fun startMonitoring()

    /** Stop discovery/advertising and release system observers. */
    fun stopMonitoring()

    fun refreshAvailability()
    fun startDiscovery()
    fun stopDiscovery()
}

object WifiDirectPermissionPolicy {
    enum class Permission {
        NEARBY_WIFI_DEVICES,
        LEGACY_FINE_LOCATION,
    }

    fun forApiLevel(apiLevel: Int): Permission =
        if (apiLevel >= 33) {
            Permission.NEARBY_WIFI_DEVICES
        } else {
            Permission.LEGACY_FINE_LOCATION
        }
}

/**
 * Public, non-secret metadata used to filter compatible PEYVAND advertisements.
 * Matching this record is not authentication and must never authorize a connection.
 */
object PeyvandDiscoveryAdvertisement {
    const val INSTANCE_NAME_PREFIX = "PEYVAND-"
    const val INSTANCE_ID_LENGTH = 8
    const val SERVICE_TYPE = "_peyvand._tcp"
    const val APP_KEY = "app"
    const val APP_ID = "peyvand"
    const val DISCOVERY_VERSION_KEY = "discovery"
    const val DISCOVERY_VERSION = "1"

    fun isCompatibleInstanceName(instanceName: String): Boolean {
        if (!instanceName.startsWith(INSTANCE_NAME_PREFIX, ignoreCase = true)) return false
        val suffix = instanceName.substring(INSTANCE_NAME_PREFIX.length)
        return suffix.length == INSTANCE_ID_LENGTH &&
            suffix.all { character ->
                character in '0'..'9' || character.lowercaseChar() in 'a'..'f'
            }
    }

    fun isCompatible(record: Map<String, String>): Boolean =
        record[APP_KEY] == APP_ID &&
            record[DISCOVERY_VERSION_KEY] == DISCOVERY_VERSION
}
