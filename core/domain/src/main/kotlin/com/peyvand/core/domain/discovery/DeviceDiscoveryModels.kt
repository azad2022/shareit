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
    ADVERTISING,
    DISCOVERING,
    FAILED,
}

/** Actual framework-level P2P scan state; distinct from an accepted API request. */
enum class PlatformDiscoveryState {
    UNKNOWN,
    RUNNING,
    STOPPED,
}

/** A scan has one explicit DNS-SD responsibility; send and receive are not symmetric. */
enum class DiscoveryRole {
    ADVERTISE_ONLY,
    BROWSE_ONLY,
}

/** TXT describes public compatibility metadata; it is not peer authentication. */
enum class DiscoveryMetadataStatus {
    NOT_RECEIVED,
    COMPATIBLE,
    INCOMPATIBLE,
}

/** Per-scan source counters use local pseudonyms (P1, P2...), never raw P2P addresses. */
data class DiscoverySourceDiagnostics(
    val token: String,
    val serviceResponseCount: Int = 0,
    val acceptedServiceResponseCount: Int = 0,
    val txtRecordResponseCount: Int = 0,
    val acceptedTxtDomainCount: Int = 0,
    val compatibleTxtRecordCount: Int = 0,
    val candidatePublished: Boolean = false,
)

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
 * Bounded retry delays for transient Android Wi-Fi P2P BUSY responses.
 * Retry attempts are numbered from one; attempt zero is the initial request.
 */
object WifiDirectBusyRetryPolicy {
    fun delayMillisForRetry(retryNumber: Int): Long? = when (retryNumber) {
        1 -> 500L
        2 -> 1_000L
        3 -> 2_000L
        else -> null
    }
}

/**
 * Delayed re-arm policy after Android reports that service discovery has stopped.
 * Consecutive stops back off to avoid tight restart loops on OEM/framework stacks.
 * A confirmed STARTED event resets the consecutive-stop counter.
 */
object WifiDirectServiceDiscoveryRearmPolicy {
    fun delayMillisForStopCount(consecutiveStops: Int): Long = when {
        consecutiveStops <= 1 -> 1_000L
        consecutiveStops == 2 -> 2_000L
        consecutiveStops == 3 -> 4_000L
        consecutiveStops == 4 -> 8_000L
        else -> 15_000L
    }
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
    val metadataStatus: DiscoveryMetadataStatus = DiscoveryMetadataStatus.NOT_RECEIVED,
)

data class DeviceDiscoverySnapshot(
    val availability: WifiDirectAvailability = WifiDirectAvailability.CHECKING,
    val status: DiscoveryStatus = DiscoveryStatus.IDLE,
    val peers: List<NearbyPeer> = emptyList(),
    val failure: DeviceDiscoveryFailure? = null,
    val failureOperation: DiscoveryOperation? = null,
    val serviceResponseCount: Int = 0,
    val acceptedServiceResponseCount: Int = 0,
    val txtRecordResponseCount: Int = 0,
    val acceptedTxtDomainCount: Int = 0,
    val compatibleTxtRecordCount: Int = 0,
    val localServiceRegistered: Boolean = false,
    val serviceRequestRegistered: Boolean = false,
    /** True only when discoverServices() was accepted by Android. */
    val discoveryStarted: Boolean = false,
    /** Current system scan state; unknown is never presented as an active scan. */
    val platformDiscoveryState: PlatformDiscoveryState = PlatformDiscoveryState.UNKNOWN,
    val discoveryStopCount: Int = 0,
    val discoveryRestartCount: Int = 0,
    val sourceDiagnostics: List<DiscoverySourceDiagnostics> = emptyList(),
    val missingSourceAddressCount: Int = 0,
    val untrackedSourceCount: Int = 0,
)

interface DeviceDiscoveryRepository {
    val snapshot: StateFlow<DeviceDiscoverySnapshot>

    /** Observe P2P system state only while the application is foregrounded. */
    fun startMonitoring()

    /** Stop discovery/advertising and release system observers. */
    fun stopMonitoring()

    fun refreshAvailability()
    fun startDiscovery(role: DiscoveryRole)
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
