package com.peyvand.core.domain.network

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRoutePolicyTest {
    @Test
    fun vpnTransportOnAppDefaultNetworkIsActive() {
        assertEquals(VpnRouteStatus.ACTIVE_FOR_APP, VpnRoutePolicy.classify(true))
    }

    @Test
    fun nonVpnAppDefaultNetworkIsInactive() {
        assertEquals(VpnRouteStatus.NOT_ACTIVE_FOR_APP, VpnRoutePolicy.classify(false))
    }

    @Test
    fun missingCapabilitiesAreUnknown() {
        assertEquals(VpnRouteStatus.UNKNOWN, VpnRoutePolicy.classify(null))
    }
}
