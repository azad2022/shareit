package com.peyvand.core.domain.discovery

import java.util.Locale

/**
 * Correlates independent Wi-Fi Direct DNS-SD service and TXT callbacks.
 *
 * A validated service callback is sufficient to publish an unverified candidate.
 * TXT records only enrich the candidate with public compatibility metadata.
 * This class deliberately has no Android framework dependency.
 */
class DiscoveryCandidateRegistry(
    private val maxPendingServices: Int = DEFAULT_MAX_PENDING,
    private val maxPendingRecords: Int = DEFAULT_MAX_PENDING,
    private val maxVisiblePeers: Int = DEFAULT_MAX_VISIBLE,
) {
    private data class PeerServiceKey(
        val deviceAddress: String,
        val normalizedInstanceName: String,
    )

    private val services = linkedMapOf<PeerServiceKey, String?>()
    private val records = linkedMapOf<PeerServiceKey, DiscoveryMetadataStatus>()
    private val visiblePeers = linkedMapOf<String, NearbyPeer>()
    private val visiblePeerKeys = linkedMapOf<String, PeerServiceKey>()

    init {
        require(maxPendingServices > 0)
        require(maxPendingRecords > 0)
        require(maxVisiblePeers > 0)
    }

    /**
     * Records a service callback after the caller has validated the instance name
     * and Bonjour registration type. The returned candidate does not imply trust.
     */
    fun recordServiceResponse(
        deviceAddress: String,
        instanceName: String,
        displayName: String?,
    ): NearbyPeer? {
        if (deviceAddress.isBlank()) return null

        val key = key(deviceAddress, instanceName)
        val previousPeer = visiblePeers[deviceAddress]
            ?.takeIf { visiblePeerKeys[deviceAddress] == key }
        val resolvedDisplayName = displayName?.takeIf(String::isNotBlank)
            ?: previousPeer?.displayName
        putBounded(services, key, resolvedDisplayName, maxPendingServices)

        val metadataStatus = records[key]
            ?: previousPeer?.metadataStatus
            ?: DiscoveryMetadataStatus.NOT_RECEIVED

        return NearbyPeer(
            id = deviceAddress,
            displayName = resolvedDisplayName,
            metadataStatus = metadataStatus,
        ).also { upsertVisiblePeer(key, it) }
    }

    /**
     * Records a TXT callback after the caller has validated its full DNS-SD domain.
     * If the corresponding service callback has not arrived yet, the bounded
     * metadata is retained and applied when that service callback arrives.
     */
    fun recordTxtResponse(
        deviceAddress: String,
        instanceName: String,
        isCompatible: Boolean,
    ): NearbyPeer? {
        if (deviceAddress.isBlank()) return null

        val key = key(deviceAddress, instanceName)
        val metadataStatus = if (isCompatible) {
            DiscoveryMetadataStatus.COMPATIBLE
        } else {
            DiscoveryMetadataStatus.INCOMPATIBLE
        }
        putBounded(records, key, metadataStatus, maxPendingRecords)

        val serviceWasSeen = services.containsKey(key)
        val existingPeer = visiblePeers[deviceAddress]
            ?.takeIf { visiblePeerKeys[deviceAddress] == key }
        if (!serviceWasSeen && existingPeer == null) return null

        val updatedPeer = NearbyPeer(
            id = deviceAddress,
            displayName = services[key] ?: existingPeer?.displayName,
            metadataStatus = metadataStatus,
        )
        upsertVisiblePeer(key, updatedPeer)
        return updatedPeer
    }

    /** Returns visible candidates once each, ordered by their display name. */
    fun snapshot(): List<NearbyPeer> = visiblePeers.values
        .sortedBy { it.displayName?.lowercase(Locale.ROOT).orEmpty() }

    fun clear() {
        services.clear()
        records.clear()
        visiblePeers.clear()
        visiblePeerKeys.clear()
    }

    private fun key(deviceAddress: String, instanceName: String) = PeerServiceKey(
        deviceAddress = deviceAddress,
        normalizedInstanceName = instanceName.lowercase(Locale.ROOT),
    )

    private fun upsertVisiblePeer(key: PeerServiceKey, peer: NearbyPeer) {
        visiblePeers.remove(peer.id)
        visiblePeerKeys.remove(peer.id)
        visiblePeers[peer.id] = peer
        visiblePeerKeys[peer.id] = key

        while (visiblePeers.size > maxVisiblePeers) {
            val oldestAddress = visiblePeers.keys.firstOrNull() ?: break
            visiblePeers.remove(oldestAddress)
            visiblePeerKeys.remove(oldestAddress)
        }
    }

    private fun <T> putBounded(
        target: LinkedHashMap<PeerServiceKey, T>,
        key: PeerServiceKey,
        value: T,
        maximum: Int,
    ) {
        if (!target.containsKey(key) && target.size >= maximum) {
            val oldestKey = target.keys.firstOrNull()
            if (oldestKey != null) target.remove(oldestKey)
        }
        target[key] = value
    }

    private companion object {
        const val DEFAULT_MAX_PENDING = 128
        const val DEFAULT_MAX_VISIBLE = 64
    }
}
