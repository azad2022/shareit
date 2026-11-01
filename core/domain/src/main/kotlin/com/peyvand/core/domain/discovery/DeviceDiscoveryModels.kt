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

/** Identifies which asynchronous framework operation failed during discovery. */
enum class DiscoveryOperation {
    CLEAR_SERVICE_REQUESTS,
    CLEAR_LOCAL_SERVICES,
    ADD_LOCAL_SERVICE,
    ADD_SERVICE_REQUEST,
    DISCOVER_SERVICES,
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
    val failureOperation: DiscoveryOperation? = null,
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
        LEGACY_COARSE_LOCATION,
    }

    fun permissionsForApiLevel(apiLevel: Int): List<Permission> =
        if (apiLevel >= 33) {
            listOf(Permission.NEARBY_WIFI_DEVICES)
        } else {
            listOf(
                Permission.LEGACY_FINE_LOCATION,
                Permission.LEGACY_COARSE_LOCATION,
            )
        }

    /** The permission that must be granted before Wi-Fi Direct discovery can start. */
    fun forApiLevel(apiLevel: Int): Permission = permissionsForApiLevel(apiLevel).first()
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

    fun isCompatibleRegistrationType(registrationType: String): Boolean =
        registrationType.trim().trimEnd('.').equals(
            "$SERVICE_TYPE.local",
            ignoreCase = true,
        )

    /**
     * Extracts the instance name only from the expected DNS-SD service domain.
     * Android reports domains in the form: <instance>._peyvand._tcp.local.
     */
    fun instanceNameFromFullDomain(fullDomain: String): String? {
        val serviceDomainSuffix = ".$SERVICE_TYPE.local"
        val normalizedDomain = fullDomain.trim().trimEnd('.')
        if (!normalizedDomain.endsWith(serviceDomainSuffix, ignoreCase = true)) return null

        val instanceName = normalizedDomain.dropLast(serviceDomainSuffix.length)
        return instanceName.takeIf(::isCompatibleInstanceName)
    }

    fun isCompatible(record: Map<String, String>): Boolean =
        record[APP_KEY] == APP_ID &&
            record[DISCOVERY_VERSION_KEY] == DISCOVERY_VERSION
}
