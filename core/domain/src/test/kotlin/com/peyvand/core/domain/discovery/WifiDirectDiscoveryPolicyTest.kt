package com.peyvand.core.domain.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
