package com.peyvand.core.calling

import com.peyvand.core.domain.connection.VoiceCallP2pEndpoints
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope

/**
 * Narrow media contract consumed by the call orchestrator. Implementations must not create
 * microphone capture until explicit remote-call acceptance and local RECORD_AUDIO grant.
 * Activation succeeds only after the selected Wi-Fi Direct host/UDP pair and DTLS-SRTP
 * have been verified by the concrete media engine.
 */
interface VoiceCallMediaSession : Closeable {
    suspend fun createOffer(): String
    suspend fun acceptOfferAndCreateAnswer(remoteOfferSdp: String): String
    suspend fun applyRemoteAnswer(remoteAnswerSdp: String)
    suspend fun addRemoteIceCandidate(
        sdpMid: String?,
        sdpMLineIndex: Int,
        candidateSdp: String,
    ): Boolean
    suspend fun activateAfterVerifiedP2pPath(): Boolean
    fun setMuted(value: Boolean)
    val isMuted: Boolean
    val isActive: Boolean
}

/** Creates one media session bound to the exact authenticated Wi-Fi Direct endpoints. */
interface VoiceCallMediaSessionFactory {
    fun create(
        endpoints: VoiceCallP2pEndpoints,
        scope: CoroutineScope,
        onLocalIceCandidate: suspend (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): VoiceCallMediaSession
}
