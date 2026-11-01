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
    fun `service instance name requires PEYVAND prefix and an eight digit hexadecimal suffix`() {
        assertTrue(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("PEYVAND-a1b2c3d4"))
        assertTrue(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("peyvand-0123ABCD"))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("PEYVAND"))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("PEYVAND-xyz"))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleInstanceName("OTHER-a1b2c3d4"))
    }

    @Test
    fun `DNS-SD registration type must exactly match the PEYVAND service type`() {
        assertTrue(PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType("_peyvand._tcp"))
        assertTrue(PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType("_PEYVAND._TCP."))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType("_other._tcp"))
        assertFalse(PeyvandDiscoveryAdvertisement.isCompatibleRegistrationType("prefix._peyvand._tcp"))
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
}
