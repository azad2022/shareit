package com.peyvand.core.calling.android

import com.peyvand.core.calling.VoiceCallMediaSession
import com.peyvand.core.calling.VoiceCallMediaSessionFactory
import com.peyvand.core.domain.connection.VoiceCallP2pEndpoints
import kotlinx.coroutines.CoroutineScope

class AndroidWebRtcVoiceCallMediaSessionFactory(
    private val runtime: WebRtcVoiceMediaRuntime,
) : VoiceCallMediaSessionFactory {

    override fun create(
        endpoints: VoiceCallP2pEndpoints,
        scope: CoroutineScope,
        onLocalIceCandidate: suspend (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): VoiceCallMediaSession = runtime.createCallSession(
        p2pInterfaceName = endpoints.interfaceName,
        localP2pAddress = endpoints.localAddress,
        localP2pPrefixLength = endpoints.localPrefixLength,
        authenticatedPeerP2pAddress = endpoints.peerAddress,
        scope = scope,
        onLocalIceCandidate = onLocalIceCandidate,
        onFailure = { failure -> onFailure(IllegalStateException(failure.name)) },
    )
}
