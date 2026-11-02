package com.peyvand.core.domain.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectDiscoveryPolicyTest {
    @Test
    fun `Android 12L and earlier use fine location permission policy`() {
        assertEquals(
            WifiDirectPermissionPolicy.Permission.LEGACY_FINE_LOCATION,
            WifiDirectPermissionPolicy.forApiLevel(26),
        )
        assertEquals(
            WifiDirectPermissionPolicy.Permission.LEGACY_FINE_LOCATION,
            WifiDirectPermissionPolicy.forApiLevel(32),
        )
        assertEquals(
            listOf(
                WifiDirectPermissionPolicy.Permission.LEGACY_FINE_LOCATION,
                WifiDirectPermissionPolicy.Permission.LEGACY_COARSE_LOCATION,
            ),
            WifiDirectPermissionPolicy.permissionsForApiLevel(32),
        )
    }

    @Test
    fun `Android 13 and later use nearby Wi-Fi devices permission policy`() {
        assertEquals(
            WifiDirectPermissionPolicy.Permission.NEARBY_WIFI_DEVICES,
            WifiDirectPermissionPolicy.forApiLevel(33),
        )
        assertEquals(
            WifiDirectPermissionPolicy.Permission.NEARBY_WIFI_DEVICES,
            WifiDirectPermissionPolicy.forApiLevel(37),
        )
        assertEquals(
            listOf(WifiDirectPermissionPolicy.Permission.NEARBY_WIFI_DEVICES),
            WifiDirectPermissionPolicy.permissionsForApiLevel(37),
        )
    }

    @Test
    fun `BUSY retry policy uses bounded exponential delays`() {
        assertEquals(500L, WifiDirectBusyRetryPolicy.delayMillisForRetry(1))
        assertEquals(1_000L, WifiDirectBusyRetryPolicy.delayMillisForRetry(2))
        assertEquals(2_000L, WifiDirectBusyRetryPolicy.delayMillisForRetry(3))
        assertNull(WifiDirectBusyRetryPolicy.delayMillisForRetry(0))
        assertNull(WifiDirectBusyRetryPolicy.delayMillisForRetry(4))
    }

    @Test
    fun `service discovery rearm policy backs off and remains bounded`() {
        assertEquals(1_000L, WifiDirectServiceDiscoveryRearmPolicy.delayMillisForStopCount(0))
        assertEquals(1_000L, WifiDirectServiceDiscoveryRearmPolicy.delayMillisForStopCount(1))
        assertEquals(2_000L, WifiDirectServiceDiscoveryRearmPolicy.delayMillisForStopCount(2))
        assertEquals(4_000L, WifiDirectServiceDiscoveryRearmPolicy.delayMillisForStopCount(3))
        assertEquals(8_000L, WifiDirectServiceDiscoveryRearmPolicy.delayMillisForStopCount(4))
        assertEquals(15_000L, WifiDirectServiceDiscoveryRearmPolicy.delayMillisForStopCount(5))
        assertEquals(15_000L, WifiDirectServiceDiscoveryRearmPolicy.delayMillisForStopCount(20))
    }

    @Test
    fun `rearm requires an active browser and a confirmed stopped platform scan`() {
        assertTrue(
            WifiDirectServiceDiscoveryRearmPolicy.shouldRearm(
                activeRole = DiscoveryRole.BROWSE_ONLY,
                monitoring = true,
                cleanupInProgress = false,
                cleanupRequested = false,
                startInFlight = false,
                discoveryRequestAccepted = true,
                platformState = PlatformDiscoveryState.STOPPED,
            ),
        )
    }

    @Test
    fun `rearm is blocked for advertiser stopped unknown cleanup or pending start states`() {
        val valid = mapOf(
            "activeRole" to DiscoveryRole.BROWSE_ONLY,
            "monitoring" to true,
            "cleanupInProgress" to false,
            "cleanupRequested" to false,
            "startInFlight" to false,
            "discoveryRequestAccepted" to true,
            "platformState" to PlatformDiscoveryState.STOPPED,
        )

        fun allowed(
            activeRole: DiscoveryRole? = valid["activeRole"] as DiscoveryRole,
            monitoring: Boolean = valid["monitoring"] as Boolean,
            cleanupInProgress: Boolean = valid["cleanupInProgress"] as Boolean,
            cleanupRequested: Boolean = valid["cleanupRequested"] as Boolean,
            startInFlight: Boolean = valid["startInFlight"] as Boolean,
            discoveryRequestAccepted: Boolean = valid["discoveryRequestAccepted"] as Boolean,
            platformState: PlatformDiscoveryState = valid["platformState"] as PlatformDiscoveryState,
        ) = WifiDirectServiceDiscoveryRearmPolicy.shouldRearm(
            activeRole,
            monitoring,
            cleanupInProgress,
            cleanupRequested,
            startInFlight,
            discoveryRequestAccepted,
            platformState,
        )

        assertFalse(allowed(activeRole = DiscoveryRole.ADVERTISE_ONLY))
        assertFalse(allowed(monitoring = false))
        assertFalse(allowed(cleanupInProgress = true))
        assertFalse(allowed(cleanupRequested = true))
        assertFalse(allowed(startInFlight = true))
        assertFalse(allowed(discoveryRequestAccepted = false))
        assertFalse(allowed(platformState = PlatformDiscoveryState.RUNNING))
        assertFalse(allowed(platformState = PlatformDiscoveryState.UNKNOWN))
    }

    @Test
    fun `service instance name requires PEYVAND prefix and an eight digit hexadecimal suffix`() {
        assertTrue(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("PEYVAND-a1b2c3d4"))
        assertTrue(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("peyvand-0123ABCD"))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("PEYVAND"))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("PEYVAND-xyz"))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("OTHER-a1b2c3d4"))
    }

    @Test
    fun `DNS-SD registration type must match the Android local Bonjour domain`() {
        assertTrue(
            PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType(
                "_peyvand._tcp.local.",
            ),
        )
        assertTrue(
            PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType(
                "_PEYVAND._TCP.LOCAL",
            ),
        )
        assertFalse(
            PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType("_other._tcp.local."),
        )
        assertFalse(
            PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType("_peyvand._tcp"),
        )
        assertFalse(
            PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType(
                "prefix._peyvand._tcp.local.",
            ),
        )
    }

    @Test
    fun `TXT full domain must identify the same valid PEYVAND service instance`() {
        assertEquals(
            "PEYVAND-a1b2c3d4",
            PeyvandDiscoveryAdvertisement.instanceNameFromFullDomain(
                "PEYVAND-a1b2c3d4._peyvand._tcp.local.",
            ),
        )
        assertEquals(
            "peyvand-0123ABCD",
            PeyvandDiscoveryAdvertisement.instanceNameFromFullDomain(
                "peyvand-0123ABCD._PEYVAND._TCP.local",
            ),
        )
        assertNull(
            PeyvandDiscoveryAdvertisement.instanceNameFromFullDomain(
                "OTHER-a1b2c3d4._peyvand._tcp.local.",
            ),
        )
        assertNull(
            PeyvandDiscoveryAdvertisement.instanceNameFromFullDomain(
                "PEYVAND-a1b2c3d4._other._tcp.local.",
            ),
        )
        assertNull(
            PeyvandDiscoveryAdvertisement.instanceNameFromFullDomain(
                "PEYVAND-a1b2c3d4._peyvand._tcp.example.",
            ),
        )
    }

    @Test
    fun `advertisement accepts only the matching application and schema version`() {
        assertTrue(
            PeyvandDiscoveryAdvertisement.isCompatible(
                mapOf("app" to "peyvand", "discovery" to "1"),
            ),
        )
        assertFalse(
            PeyvandDiscoveryAdvertisement.isCompatible(
                mapOf("app" to "other", "discovery" to "1"),
            ),
        )
        assertFalse(
            PeyvandDiscoveryAdvertisement.isCompatible(
                mapOf("app" to "peyvand", "discovery" to "2"),
            ),
        )
        assertFalse(
            PeyvandDiscoveryAdvertisement.isCompatible(
                mapOf("app" to "peyvand"),
            ),
        )
    }

    @Test
    fun `validated service response publishes candidate without TXT metadata`() {
        val registry = DiscoveryCandidateRegistry()

        val peer = registry.recordServiceResponse(
            deviceAddress = "02:00:00:00:00:01",
            instanceName = "PEYVAND-a1b2c3d4",
            displayName = "Galaxy A12",
        )

        assertEquals("02:00:00:00:00:01", peer?.id)
        assertEquals("Galaxy A12", peer?.displayName)
        assertEquals(DiscoveryMetadataStatus.NOT_RECEIVED, peer?.metadataStatus)
        assertEquals(listOf(peer), registry.snapshot())
    }

    @Test
    fun `TXT received before service is correlated when service arrives`() {
        val registry = DiscoveryCandidateRegistry()

        assertNull(
            registry.recordTxtResponse(
                deviceAddress = "02:00:00:00:00:01",
                instanceName = "PEYVAND-a1b2c3d4",
                isCompatible = true,
            ),
        )

        val peer = registry.recordServiceResponse(
            deviceAddress = "02:00:00:00:00:01",
            instanceName = "PEYVAND-a1b2c3d4",
            displayName = "Receiver",
        )

        assertEquals(DiscoveryMetadataStatus.COMPATIBLE, peer?.metadataStatus)
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun `TXT received after service enriches existing candidate`() {
        val registry = DiscoveryCandidateRegistry()
        val address = "02:00:00:00:00:01"
        val instance = "PEYVAND-a1b2c3d4"

        registry.recordServiceResponse(address, instance, "Sender")
        val updated = registry.recordTxtResponse(address, instance, isCompatible = true)

        assertEquals(DiscoveryMetadataStatus.COMPATIBLE, updated?.metadataStatus)
        assertEquals(DiscoveryMetadataStatus.COMPATIBLE, registry.snapshot().single().metadataStatus)
    }

    @Test
    fun `incompatible TXT metadata does not remove service candidate`() {
        val registry = DiscoveryCandidateRegistry()
        val address = "02:00:00:00:00:01"
        val instance = "PEYVAND-a1b2c3d4"

        registry.recordServiceResponse(address, instance, "Peer")
        val updated = registry.recordTxtResponse(address, instance, isCompatible = false)

        assertEquals(DiscoveryMetadataStatus.INCOMPATIBLE, updated?.metadataStatus)
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun `TXT metadata from another source is never correlated to service candidate`() {
        val registry = DiscoveryCandidateRegistry()

        registry.recordTxtResponse(
            deviceAddress = "02:00:00:00:00:02",
            instanceName = "PEYVAND-a1b2c3d4",
            isCompatible = true,
        )
        val peer = registry.recordServiceResponse(
            deviceAddress = "02:00:00:00:00:01",
            instanceName = "PEYVAND-a1b2c3d4",
            displayName = "Peer",
        )

        assertEquals(DiscoveryMetadataStatus.NOT_RECEIVED, peer?.metadataStatus)
    }

    @Test
    fun `blank source address is rejected without publishing a candidate`() {
        val registry = DiscoveryCandidateRegistry()

        assertNull(
            registry.recordServiceResponse(
                deviceAddress = " ",
                instanceName = "PEYVAND-a1b2c3d4",
                displayName = "Peer",
            ),
        )
        assertNull(
            registry.recordTxtResponse(
                deviceAddress = "",
                instanceName = "PEYVAND-a1b2c3d4",
                isCompatible = true,
            ),
        )
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `duplicate callbacks update one candidate rather than duplicate it`() {
        val registry = DiscoveryCandidateRegistry()
        val address = "02:00:00:00:00:01"
        val instance = "PEYVAND-a1b2c3d4"

        registry.recordServiceResponse(address, instance, "Peer")
        registry.recordServiceResponse(address, instance, "Peer")
        registry.recordTxtResponse(address, instance, isCompatible = true)
        registry.recordTxtResponse(address, instance, isCompatible = true)

        assertEquals(1, registry.snapshot().size)
        assertEquals(DiscoveryMetadataStatus.COMPATIBLE, registry.snapshot().single().metadataStatus)
    }

    @Test
    fun `candidate storage and visible list remain bounded`() {
        val registry = DiscoveryCandidateRegistry(
            maxPendingServices = 1,
            maxPendingRecords = 1,
            maxVisiblePeers = 1,
        )

        registry.recordServiceResponse(
            "02:00:00:00:00:01",
            "PEYVAND-a1b2c3d4",
            "First",
        )
        registry.recordServiceResponse(
            "02:00:00:00:00:02",
            "PEYVAND-b1b2c3d4",
            "Second",
        )

        assertEquals(1, registry.snapshot().size)
        assertEquals("02:00:00:00:00:02", registry.snapshot().single().id)
    }

}
