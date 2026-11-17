package com.peyvand.core.calling.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import com.peyvand.core.calling.P2pIceCandidatePolicy
import com.peyvand.core.calling.VoiceCallMediaSession
import com.peyvand.core.calling.VoiceCallSdpPolicy
import com.peyvand.core.domain.connection.P2pIpv4InterfaceAddress
import com.peyvand.core.domain.connection.P2pTcpEndpointPolicy
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.NetworkMonitorAutoDetect
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RTCStatsReport
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.JavaAudioDeviceModule

enum class WebRtcAudioCaptureState { STOPPED, STARTED, FAILED }

enum class WebRtcVoiceMediaFailure {
    ICE_FAILED,
    MEDIA_STATS_UNAVAILABLE,
    AUDIO_CAPTURE_FAILED,
    LOCAL_CANDIDATE_SEND_FAILED,
}

/**
 * Process-scoped WebRTC factory and audio device module. Construct once from Application.
 * Construction does not create a PeerConnection or start microphone recording.
 */
class WebRtcVoiceMediaRuntime(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val captureMutable = MutableStateFlow(WebRtcAudioCaptureState.STOPPED)
    internal val captureState: StateFlow<WebRtcAudioCaptureState> = captureMutable.asStateFlow()
    private val closed = AtomicBoolean(false)
    private val sessionLock = Any()
    private var activeSession: WebRtcAudioCallSession? = null

    private val audioDeviceModule: JavaAudioDeviceModule
    internal val factory: PeerConnectionFactory

    init {
        synchronized(PROCESS_LOCK) {
            check(!processRuntimeCreated) {
                "Only one WebRtcVoiceMediaRuntime may be created in this application process."
            }
            processRuntimeCreated = true
        }

        try {
            // Public M150 Android API; enables WebRTC's Wi-Fi Direct network monitor.
            NetworkMonitorAutoDetect.setIncludeWifiDirect(true)
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions(),
            )

            val adm = JavaAudioDeviceModule.builder(appContext)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseLowLatency(true)
                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                    override fun onWebRtcAudioRecordStart() {
                        captureMutable.value = WebRtcAudioCaptureState.STARTED
                    }

                    override fun onWebRtcAudioRecordStop() {
                        captureMutable.value = WebRtcAudioCaptureState.STOPPED
                    }
                })
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                        captureMutable.value = WebRtcAudioCaptureState.FAILED
                    }

                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                        errorMessage: String,
                    ) {
                        captureMutable.value = WebRtcAudioCaptureState.FAILED
                    }

                    override fun onWebRtcAudioRecordError(errorMessage: String) {
                        captureMutable.value = WebRtcAudioCaptureState.FAILED
                    }
                })
                .createAudioDeviceModule()

            try {
                val options = PeerConnectionFactory.Options().apply {
                    // Retain Wi-Fi (including its P2P delegate); mask unrelated adapter classes.
                    // Candidate and selected-pair IP validation remains mandatory.
                    networkIgnoreMask =
                        PeerConnectionFactory.Options.ADAPTER_TYPE_ETHERNET or
                            PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                            PeerConnectionFactory.Options.ADAPTER_TYPE_VPN or
                            PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
                    disableEncryption = false
                    disableNetworkMonitor = false
                }
                audioDeviceModule = adm
                factory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(adm)
                    .createPeerConnectionFactory()
            } catch (exception: Exception) {
                runCatching { adm.release() }
                throw exception
            }
        } catch (exception: Exception) {
            synchronized(PROCESS_LOCK) { processRuntimeCreated = false }
            throw IllegalStateException("WebRTC audio runtime initialization failed.", exception)
        }
    }

    fun createCallSession(
        p2pInterfaceName: String,
        localP2pAddress: Inet4Address,
        localP2pPrefixLength: Int,
        authenticatedPeerP2pAddress: Inet4Address,
        scope: CoroutineScope,
        onLocalIceCandidate: suspend (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit,
        onFailure: (WebRtcVoiceMediaFailure) -> Unit,
    ): WebRtcAudioCallSession {
        check(!closed.get()) { "WebRTC runtime is closed." }
        require(isExactP2pInterfaceUsable(
            interfaceName = p2pInterfaceName,
            localAddress = localP2pAddress,
            prefixLength = localP2pPrefixLength,
            peerAddress = authenticatedPeerP2pAddress,
        )) { "Voice media must use the exact active Wi-Fi Direct interface." }
        synchronized(sessionLock) {
            check(activeSession == null) { "The local audio device is already owned by another call." }
            return WebRtcAudioCallSession(
                context = appContext,
                runtime = this,
                p2pInterfaceName = p2pInterfaceName,
                localP2pAddress = localP2pAddress,
                localP2pPrefixLength = localP2pPrefixLength,
                peerP2pAddress = authenticatedPeerP2pAddress,
                scope = scope,
                onLocalIceCandidate = onLocalIceCandidate,
                onFailure = onFailure,
                onClosed = { session ->
                    synchronized(sessionLock) {
                        if (activeSession === session) activeSession = null
                    }
                },
            ).also { activeSession = it }
        }
    }

    internal fun isExactP2pInterfaceUsable(
        interfaceName: String,
        localAddress: Inet4Address,
        prefixLength: Int,
        peerAddress: Inet4Address,
    ): Boolean = runCatching {
        if (interfaceName.isBlank() || prefixLength !in 1..30) return@runCatching false
        val networkInterface = NetworkInterface.getByName(interfaceName) ?: return@runCatching false
        if (!networkInterface.isUp || networkInterface.isLoopback) return@runCatching false
        val ownsAddressAndPrefix = networkInterface.interfaceAddresses.any { item ->
            val address = item.address as? Inet4Address
            address != null &&
                address.address.contentEquals(localAddress.address) &&
                item.networkPrefixLength.toInt() == prefixLength
        }
        ownsAddressAndPrefix && P2pTcpEndpointPolicy.isOnSameSubnet(
            P2pIpv4InterfaceAddress(localAddress, prefixLength),
            peerAddress,
        )
    }.getOrDefault(false)

    internal fun resetCaptureState() {
        captureMutable.value = WebRtcAudioCaptureState.STOPPED
    }

    internal suspend fun awaitCaptureStart(timeoutMillis: Long): Boolean {
        val state = withTimeoutOrNull(timeoutMillis) {
            captureState.first { it != WebRtcAudioCaptureState.STOPPED }
        } ?: return false
        return state == WebRtcAudioCaptureState.STARTED
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val session = synchronized(sessionLock) { activeSession.also { activeSession = null } }
        runCatching { session?.close() }
        runCatching { factory.dispose() }
        runCatching { audioDeviceModule.release() }
    }

    private companion object {
        val PROCESS_LOCK = Any()
        var processRuntimeCreated = false
    }
}

/**
 * One audio-only PeerConnection bound to exact local/remote IPv4 endpoints from the
 * authenticated P2P TLS socket. It never configures STUN/TURN or a relay.
 */
class WebRtcAudioCallSession internal constructor(
    private val context: Context,
    private val runtime: WebRtcVoiceMediaRuntime,
    private val p2pInterfaceName: String,
    private val localP2pAddress: Inet4Address,
    private val localP2pPrefixLength: Int,
    private val peerP2pAddress: Inet4Address,
    private val scope: CoroutineScope,
    private val onLocalIceCandidate: suspend (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit,
    private val onFailure: (WebRtcVoiceMediaFailure) -> Unit,
    private val onClosed: (WebRtcAudioCallSession) -> Unit,
) : VoiceCallMediaSession {
    private val closed = AtomicBoolean(false)
    private val signallingMutex = Mutex()
    private val lock = Any()
    private val queuedRemoteCandidates = ArrayDeque<IceCandidate>()

    private var remoteDescriptionApplied = false
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var muted = false
    private var activated = false

    override suspend fun createOffer(): String = signallingMutex.withLock {
        ensureMicrophonePermission()
        val connection = ensurePeerConnection()
        val offer = createDescription(connection, isOffer = true)
        setLocalDescription(connection, offer)
        requireValidSdp(offer.description)
        offer.description
    }

    override suspend fun acceptOfferAndCreateAnswer(remoteOfferSdp: String): String = signallingMutex.withLock {
        ensureMicrophonePermission()
        val connection = ensurePeerConnection()
        requireValidSdp(remoteOfferSdp)
        setRemoteDescription(connection, SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp))
        flushRemoteCandidates(connection)
        val answer = createDescription(connection, isOffer = false)
        setLocalDescription(connection, answer)
        requireValidSdp(answer.description)
        answer.description
    }

    override suspend fun applyRemoteAnswer(remoteAnswerSdp: String) = signallingMutex.withLock {
        ensureNotClosed()
        requireValidSdp(remoteAnswerSdp)
        val connection = synchronized(lock) { peerConnection }
            ?: error("Create the local offer before applying a remote answer.")
        setRemoteDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp))
        flushRemoteCandidates(connection)
    }

    override suspend fun addRemoteIceCandidate(
        sdpMid: String?,
        sdpMLineIndex: Int,
        candidateSdp: String,
    ): Boolean = signallingMutex.withLock {
        ensureNotClosed()
        if (sdpMLineIndex != 0 ||
            !P2pIceCandidatePolicy.isAllowedRemoteCandidate(candidateSdp, peerP2pAddress)
        ) {
            return@withLock false
        }

        val candidate = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidateSdp)
        val connection = synchronized(lock) { peerConnection }
        if (connection == null || !remoteDescriptionApplied) {
            if (queuedRemoteCandidates.size >= MAX_QUEUED_REMOTE_CANDIDATES) return@withLock false
            queuedRemoteCandidates.addLast(candidate)
            return@withLock true
        }
        connection.addIceCandidate(candidate)
    }

    /**
     * Starts recording/playout only after stats prove the selected host/UDP pair and
     * DTLS-SRTP are active on the exact P2P IP addresses.
     */
    override suspend fun activateAfterVerifiedP2pPath(
        timeoutMillis: Long,
    ): Boolean = signallingMutex.withLock {
        ensureNotClosed()
        ensureMicrophonePermission()
        if (!hasExactP2pInterface()) {
            onFailure(WebRtcVoiceMediaFailure.MEDIA_STATS_UNAVAILABLE)
            return@withLock false
        }
        val alreadyActive = synchronized(lock) { activated }
        if (alreadyActive) return@withLock true

        val connection = synchronized(lock) { peerConnection } ?: return@withLock false
        val track = synchronized(lock) { localTrack } ?: return@withLock false
        activateAudioPath(connection, track, timeoutMillis)
    }

    override suspend fun setTelecomActive(active: Boolean): Boolean {
        if (active) return activateAfterVerifiedP2pPath(MEDIA_PATH_TIMEOUT_MILLIS)
        return signallingMutex.withLock {
            ensureNotClosed()
            val connection = synchronized(lock) { peerConnection } ?: return@withLock true
            val track = synchronized(lock) { localTrack }
            runCatching { track?.setEnabled(false) }
            runCatching { connection.setAudioRecording(false) }
            runCatching { connection.setAudioPlayout(false) }
            synchronized(lock) { activated = false }
            true
        }
    }

    private suspend fun activateAudioPath(
        connection: PeerConnection,
        track: AudioTrack,
        timeoutMillis: Long,
    ): Boolean {
        if (!awaitVerifiedP2pMediaPath(connection, timeoutMillis)) {
            onFailure(WebRtcVoiceMediaFailure.MEDIA_STATS_UNAVAILABLE)
            return false
        }

        runtime.resetCaptureState()
        connection.setAudioRecording(true)
        if (!runtime.awaitCaptureStart(RECORDING_START_TIMEOUT_MILLIS) || closed.get()) {
            connection.setAudioRecording(false)
            connection.setAudioPlayout(false)
            onFailure(WebRtcVoiceMediaFailure.AUDIO_CAPTURE_FAILED)
            return false
        }

        track.setEnabled(!muted)
        connection.setAudioPlayout(true)
        synchronized(lock) { activated = true }
        return true
    }

    /** Mute disables the outgoing WebRTC AudioTrack. */
    override fun setMuted(value: Boolean) {
        synchronized(lock) {
            muted = value
            localTrack?.setEnabled(activated && !value)
        }
    }

    override val isMuted: Boolean
        get() = synchronized(lock) { muted }

    override val isActive: Boolean
        get() = synchronized(lock) { activated && !closed.get() }

    private fun ensurePeerConnection(): PeerConnection {
        ensureNotClosed()
        check(hasExactP2pInterface()) {
            "The active Wi-Fi Direct interface no longer matches the authenticated session."
        }
        synchronized(lock) { peerConnection?.let { return it } }
        ensureMicrophonePermission()

        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            iceServers = emptyList()
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            disableIPv6OnWifi = true
            enableIceGatheringOnAnyAddressPorts = false
        }

        val connection = runtime.factory.createPeerConnection(
            configuration,
            object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    if (newState == PeerConnection.IceConnectionState.FAILED ||
                        newState == PeerConnection.IceConnectionState.CLOSED
                    ) {
                        if (!closed.get()) onFailure(WebRtcVoiceMediaFailure.ICE_FAILED)
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

                override fun onIceCandidate(candidate: IceCandidate) {
                    if (!hasExactP2pInterface() ||
                        !P2pIceCandidatePolicy.isAllowedLocalCandidate(candidate.sdp, localP2pAddress)
                    ) return
                    scope.launch(Dispatchers.IO) {
                        try {
                            onLocalIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            if (!closed.get()) onFailure(WebRtcVoiceMediaFailure.LOCAL_CANDIDATE_SEND_FAILED)
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit

                override fun onDataChannel(dataChannel: DataChannel) {
                    dataChannel.close()
                    dataChannel.dispose()
                }

                override fun onRenegotiationNeeded() = Unit
            },
        ) ?: throw IllegalStateException("WebRTC could not create an audio peer connection.")

        connection.setAudioRecording(false)
        connection.setAudioPlayout(false)

        val source = runtime.factory.createAudioSource(MediaConstraints())
        val track = runtime.factory.createAudioTrack("peyvand-audio-${UUID.randomUUID()}", source)
        track.setEnabled(false)

        if (connection.addTrack(track, listOf(AUDIO_STREAM_ID)) == null) {
            connection.dispose()
            track.dispose()
            source.dispose()
            throw IllegalStateException("WebRTC could not attach the local audio track.")
        }

        synchronized(lock) {
            if (closed.get()) {
                connection.dispose()
                track.dispose()
                source.dispose()
                throw IllegalStateException("Voice call closed during media initialization.")
            }
            if (peerConnection != null) {
                connection.dispose()
                track.dispose()
                source.dispose()
                return peerConnection!!
            }
            peerConnection = connection
            audioSource = source
            localTrack = track
        }
        return connection
    }

    private suspend fun createDescription(
        connection: PeerConnection,
        isOffer: Boolean,
    ): SessionDescription = withTimeout(SDP_OPERATION_TIMEOUT_MILLIS) {
        val result = CompletableDeferred<SessionDescription>()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { result.complete(sdp) }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) {
                result.completeExceptionally(IllegalStateException("WebRTC SDP creation failed."))
            }
            override fun onSetFailure(error: String) = Unit
        }
        if (isOffer) connection.createOffer(observer, MediaConstraints())
        else connection.createAnswer(observer, MediaConstraints())
        result.await()
    }

    private suspend fun setLocalDescription(connection: PeerConnection, description: SessionDescription) {
        withTimeout(SDP_OPERATION_TIMEOUT_MILLIS) {
            val result = CompletableDeferred<Unit>()
            connection.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onSetSuccess() { result.complete(Unit) }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    result.completeExceptionally(IllegalStateException("WebRTC local SDP was rejected."))
                }
            }, description)
            result.await()
        }
    }

    private suspend fun setRemoteDescription(connection: PeerConnection, description: SessionDescription) {
        withTimeout(SDP_OPERATION_TIMEOUT_MILLIS) {
            val result = CompletableDeferred<Unit>()
            connection.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = Unit
                override fun onSetSuccess() { result.complete(Unit) }
                override fun onCreateFailure(error: String) = Unit
                override fun onSetFailure(error: String) {
                    result.completeExceptionally(IllegalStateException("WebRTC remote SDP was rejected."))
                }
            }, description)
            result.await()
        }
        synchronized(lock) { remoteDescriptionApplied = true }
    }

    private suspend fun flushRemoteCandidates(connection: PeerConnection) {
        val candidates = synchronized(lock) {
            queuedRemoteCandidates.toList().also { queuedRemoteCandidates.clear() }
        }
        candidates.forEach { candidate ->
            if (!connection.addIceCandidate(candidate)) {
                throw IllegalStateException("WebRTC rejected a previously validated P2P candidate.")
            }
        }
    }

    private suspend fun awaitVerifiedP2pMediaPath(
        connection: PeerConnection,
        timeoutMillis: Long,
    ): Boolean = withTimeoutOrNull(timeoutMillis) {
        while (!closed.get()) {
            val report = awaitStats(connection)
            if (report != null && isVerifiedSelectedP2pPair(report)) return@withTimeoutOrNull true
            delay(STATS_POLL_INTERVAL_MILLIS)
        }
        false
    } ?: false

    private suspend fun awaitStats(connection: PeerConnection): RTCStatsReport? =
        withTimeoutOrNull(STATS_REQUEST_TIMEOUT_MILLIS) {
            val result = CompletableDeferred<RTCStatsReport>()
            connection.getStats { report -> result.complete(report) }
            result.await()
        }

    private fun isVerifiedSelectedP2pPair(report: RTCStatsReport): Boolean {
        if (!hasExactP2pInterface()) return false
        val stats = report.statsMap.values
        val transport = stats.firstOrNull { it.type == "transport" } ?: return false
        val transportMembers = transport.members
        if (!transportMembers["dtlsState"].toString().equals("connected", ignoreCase = true)) return false
        if ((transportMembers["srtpCipher"] as? String).isNullOrBlank()) return false

        val selectedPairId = transportMembers["selectedCandidatePairId"] as? String ?: return false
        val pair = stats.firstOrNull { it.id == selectedPairId && it.type == "candidate-pair" } ?: return false
        if (!pair.members["state"].toString().equals("succeeded", ignoreCase = true)) return false
        if (pair.members["nominated"] != true) return false

        val localId = pair.members["localCandidateId"] as? String ?: return false
        val remoteId = pair.members["remoteCandidateId"] as? String ?: return false
        val localCandidate = stats.firstOrNull { it.id == localId } ?: return false
        val remoteCandidate = stats.firstOrNull { it.id == remoteId } ?: return false

        return P2pIceCandidatePolicy.isAllowedSelectedCandidate(
            localCandidate.members["candidateType"] as? String,
            localCandidate.members["protocol"] as? String,
            localCandidate.members["address"] as? String,
            localP2pAddress,
        ) && P2pIceCandidatePolicy.isAllowedSelectedCandidate(
            remoteCandidate.members["candidateType"] as? String,
            remoteCandidate.members["protocol"] as? String,
            remoteCandidate.members["address"] as? String,
            peerP2pAddress,
        )
    }

    private fun requireValidSdp(sdp: String) {
        require(VoiceCallSdpPolicy.isValidAudioOnlySdp(sdp)) {
            "Only audio-only Opus DTLS-SRTP SDP with trickled ICE is accepted."
        }
    }

    private fun hasExactP2pInterface(): Boolean =
        runtime.isExactP2pInterfaceUsable(
            interfaceName = p2pInterfaceName,
            localAddress = localP2pAddress,
            prefixLength = localP2pPrefixLength,
            peerAddress = peerP2pAddress,
        )

    private fun ensureMicrophonePermission() {
        check(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) {
            "RECORD_AUDIO runtime permission must be granted before creating an audio source."
        }
    }

    private fun ensureNotClosed() {
        check(!closed.get()) { "WebRTC audio session is closed." }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val (connection, track, source) = synchronized(lock) {
            Triple(peerConnection, localTrack, audioSource).also {
                peerConnection = null
                localTrack = null
                audioSource = null
                queuedRemoteCandidates.clear()
                activated = false
            }
        }
        runCatching { connection?.setAudioRecording(false) }
        runCatching { connection?.setAudioPlayout(false) }
        runCatching { track?.setEnabled(false) }
        runCatching { connection?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        onClosed(this)
    }

    private companion object {
        const val AUDIO_STREAM_ID = "peyvand-audio-stream"
        const val MAX_QUEUED_REMOTE_CANDIDATES = 64
        const val SDP_OPERATION_TIMEOUT_MILLIS = 8_000L
        const val MEDIA_PATH_TIMEOUT_MILLIS = 8_000L
        const val STATS_REQUEST_TIMEOUT_MILLIS = 1_000L
        const val RECORDING_START_TIMEOUT_MILLIS = 3_000L
        const val STATS_POLL_INTERVAL_MILLIS = 250L
    }
}
