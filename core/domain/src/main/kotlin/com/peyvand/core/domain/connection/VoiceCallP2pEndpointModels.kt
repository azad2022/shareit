package com.peyvand.core.domain.connection

import java.net.Inet4Address
import java.util.UUID

/**
 * Ephemeral IPv4 endpoints observed on the authenticated Wi-Fi Direct TLS socket.
 *
 * This object is valid only for the exact authenticated session ID. It must remain
 * in memory, must not enter UI state or persistence, and is cleared when that session ends.
 */
data class VoiceCallP2pEndpoints(
    val sessionId: UUID,
    val localAddress: Inet4Address,
    val peerAddress: Inet4Address,
)

/** Internal application boundary for obtaining the current authenticated P2P media endpoints. */
interface VoiceCallP2pEndpointProvider {
    fun currentVoiceCallP2pEndpoints(): VoiceCallP2pEndpoints?
}
