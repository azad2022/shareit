package com.peyvand.core.calling

import com.peyvand.core.domain.call.VoiceCallDirection
import com.peyvand.core.domain.call.VoiceCallFailure
import com.peyvand.core.domain.call.VoiceCallFailureStage
import com.peyvand.core.domain.call.VoiceCallSnapshot
import com.peyvand.core.domain.call.VoiceCallStatus
import com.peyvand.core.domain.connection.SecureSessionSnapshot
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.domain.connection.VoiceCallP2pEndpointProvider
import com.peyvand.core.domain.connection.VoiceCallP2pEndpoints
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Single-actor coordinator for one-to-one voice signalling and media negotiation.
 *
 * The application must display incoming-call consent and request RECORD_AUDIO while an
 * Activity is visible. Calling acceptIncomingCall records the user's explicit acceptance;
 * the separate permission result is the only event that allows media-session creation.
 * This class never requests Android permissions or starts Telecom on its own.
 */
class VoiceCallOrchestrator(
    private val secureSession: StateFlow<SecureSessionSnapshot>,
    private val callControlReady: StateFlow<Boolean>,
    private val incomingSignals: Flow<VoiceCallSignal>,
    private val endpointProvider: VoiceCallP2pEndpointProvider,
    private val sendSignal: suspend (VoiceCallSignal) -> Unit,
    private val mediaFactory: VoiceCallMediaSessionFactory,
    private val scope: CoroutineScope,
    private val callSetupTimeoutMillis: Long = DEFAULT_CALL_SETUP_TIMEOUT_MILLIS,
) : Closeable {

    private sealed interface Event {
        data class SessionChanged(val value: SecureSessionSnapshot) : Event
        data class ControlReadyChanged(val ready: Boolean) : Event
        data class RemoteSignal(val signal: VoiceCallSignal) : Event
        data class StartOutgoing(val reply: CompletableDeferred<UUID>) : Event
        data class AcceptIncoming(val callId: UUID, val reply: CompletableDeferred<Unit>) : Event
        data class RejectIncoming(val callId: UUID, val reply: CompletableDeferred<Unit>) : Event
        data class PermissionResult(
            val callId: UUID,
            val granted: Boolean,
            val reply: CompletableDeferred<Unit>,
        ) : Event
        data class CancelOutgoing(val callId: UUID, val reply: CompletableDeferred<Unit>) : Event
        data class HangUp(val callId: UUID, val reply: CompletableDeferred<Unit>) : Event
        data class SetMuted(val muted: Boolean) : Event
        data class TelecomMediaActive(val active: Boolean) : Event
        data class LocalIce(
            val callId: UUID,
            val generation: Long,
            val mid: String?,
            val lineIndex: Int,
            val candidate: String,
        ) : Event
        data class MediaFailure(val callId: UUID, val generation: Long, val cause: Throwable) : Event
        data class MediaActivated(
            val callId: UUID,
            val generation: Long,
            val succeeded: Boolean,
            val cause: Throwable?,
        ) : Event
        data class TimedOut(val callId: UUID) : Event
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val events = Channel<Event>(capacity = MAX_QUEUED_EVENTS)
    private val mutableSnapshot = MutableStateFlow(VoiceCallSnapshot())
    val snapshot: StateFlow<VoiceCallSnapshot> = mutableSnapshot.asStateFlow()
    private val mutableCanCall = MutableStateFlow(false)
    val canCall: StateFlow<Boolean> = mutableCanCall.asStateFlow()
    private val mutableMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = mutableMuted.asStateFlow()

    private var machine = VoiceCallStateMachine()
    private var activeSessionId: UUID? = null
    private var sessionAuthenticated = false
    private var controlReady = false
    private var mediaGeneration = 0L
    @Volatile private var mediaSession: VoiceCallMediaSession? = null
    private var actorJob: Job? = null
    private var sessionJob: Job? = null
    private var controlJob: Job? = null
    private var signalJob: Job? = null
    private var timeoutJob: Job? = null
    private var activationJob: Job? = null
    private var activationStartedFor: UUID? = null
    @Volatile private var telecomMediaActive = true

    init {
        require(callSetupTimeoutMillis in MIN_CALL_SETUP_TIMEOUT_MILLIS..MAX_CALL_SETUP_TIMEOUT_MILLIS)
    }

    fun start() {
        check(!closed.get()) { "Voice call orchestrator is closed." }
        check(started.compareAndSet(false, true)) { "Voice call orchestrator already started." }

        actorJob = scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    handleUnexpectedFailure(event, exception)
                }
            }
        }
        sessionJob = scope.launch {
            secureSession.collect { events.send(Event.SessionChanged(it)) }
        }
        controlJob = scope.launch {
            callControlReady.collect { events.send(Event.ControlReadyChanged(it)) }
        }
        signalJob = scope.launch {
            incomingSignals.collect { events.send(Event.RemoteSignal(it)) }
        }
    }

    suspend fun startOutgoingCall(): UUID {
        val reply = CompletableDeferred<UUID>()
        submit(Event.StartOutgoing(reply))
        return reply.await()
    }

    /** Must be called only after the user has explicitly tapped Accept. */
    suspend fun acceptIncomingCall(callId: UUID) {
        val reply = CompletableDeferred<Unit>()
        submit(Event.AcceptIncoming(callId, reply))
        reply.await()
    }

    suspend fun rejectIncomingCall(callId: UUID) {
        val reply = CompletableDeferred<Unit>()
        submit(Event.RejectIncoming(callId, reply))
        reply.await()
    }

    /** The permission result must come from the Android runtime-permission flow. */
    suspend fun onMicrophonePermissionResult(callId: UUID, granted: Boolean) {
        val reply = CompletableDeferred<Unit>()
        submit(Event.PermissionResult(callId, granted, reply))
        reply.await()
    }

    suspend fun cancelOutgoingCall(callId: UUID) {
        val reply = CompletableDeferred<Unit>()
        submit(Event.CancelOutgoing(callId, reply))
        reply.await()
    }

    suspend fun hangUp(callId: UUID) {
        val reply = CompletableDeferred<Unit>()
        submit(Event.HangUp(callId, reply))
        reply.await()
    }

    fun setMuted(muted: Boolean) {
        if (!closed.get()) events.trySend(Event.SetMuted(muted))
    }

    /**
     * Called from Android Telecom's 5-second callbacks. Updates the media gate synchronously
     * and posts only follow-up actor work; it never waits behind SDP/ICE negotiation.
     */
    fun setTelecomMediaActive(active: Boolean): Boolean {
        if (closed.get() || !started.get()) return false
        val current = snapshot.value
        if (current.isTerminal || current.status == VoiceCallStatus.IDLE) return !active

        telecomMediaActive = active
        val media = mediaSession
        if (media != null && !runCatching { media.setTelecomActive(active) }.getOrDefault(false)) {
            current.callId?.let { callId ->
                events.trySend(
                    Event.MediaFailure(
                        callId = callId,
                        generation = mediaGeneration,
                        cause = IllegalStateException("Telecom media state could not be applied."),
                    ),
                )
            }
            return false
        }
        return events.trySend(Event.TelecomMediaActive(active)).isSuccess
    }

    private suspend fun submit(event: Event) {
        check(!closed.get()) { "Voice call orchestrator is closed." }
        check(started.get()) { "Voice call orchestrator has not been started." }
        events.send(event)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.SessionChanged -> handleSessionChanged(event.value)
            is Event.ControlReadyChanged -> handleControlReadyChanged(event.ready)
            is Event.RemoteSignal -> handleRemoteSignal(event.signal)
            is Event.StartOutgoing -> handleStartOutgoing(event)
            is Event.AcceptIncoming -> handleAcceptIncoming(event)
            is Event.RejectIncoming -> handleRejectIncoming(event)
            is Event.PermissionResult -> handlePermissionResult(event)
            is Event.CancelOutgoing -> handleCancelOutgoing(event)
            is Event.HangUp -> handleHangUp(event)
            is Event.SetMuted -> handleSetMuted(event.muted)
            is Event.TelecomMediaActive -> handleTelecomMediaActive(event)
            is Event.LocalIce -> handleLocalIce(event)
            is Event.MediaFailure -> handleMediaFailure(event)
            is Event.MediaActivated -> handleMediaActivated(event)
            is Event.TimedOut -> handleTimeout(event.callId)
        }
    }

    /**
     * StateFlow collectors and the incoming signal stream are independent coroutines.
     * A peer can send INVITE immediately after CALL_CONTROL becomes ready, before this
     * actor has consumed the initial StateFlow emissions. Reconcile from the source of
     * truth before acting on a call command or remote invitation.
     */
    private fun synchronizeExternalState() {
        handleSessionChanged(secureSession.value)
        handleControlReadyChanged(callControlReady.value)
    }

    private fun handleSessionChanged(value: SecureSessionSnapshot) {
        val endpoints = if (value.status == SecureSessionStatus.AUTHENTICATED) {
            endpointProvider.currentVoiceCallP2pEndpoints()
        } else {
            null
        }
        val nextSessionId = endpoints?.sessionId
        if (nextSessionId != activeSessionId) {
            val current = machine.snapshot
            if (current.callId != null && !current.isTerminal) {
                machine.sessionLost()
                cleanupMedia()
                cancelTimeout()
                publish()
            }
            if (nextSessionId != null) {
                // The replay registry is deliberately new for each authenticated session.
                machine = VoiceCallStateMachine()
                mutableSnapshot.value = machine.snapshot
                mutableMuted.value = false
            }
            activeSessionId = nextSessionId
        }
        sessionAuthenticated =
            value.status == SecureSessionStatus.AUTHENTICATED && nextSessionId != null
        refreshAvailability()
    }

    private fun handleControlReadyChanged(ready: Boolean) {
        val previouslyReady = controlReady
        controlReady = ready
        if (previouslyReady && !ready) {
            val current = machine.snapshot
            val callId = current.callId
            if (callId != null && !current.isTerminal) {
                failCurrentCall(
                    callId,
                    VoiceCallFailure.AUTHENTICATED_SESSION_LOST,
                    sendEnd = false,
                )
            }
        }
        refreshAvailability()
    }

    private fun refreshAvailability() {
        val current = endpointProvider.currentVoiceCallP2pEndpoints()
        mutableCanCall.value =
            !closed.get() &&
            sessionAuthenticated &&
            controlReady &&
            activeSessionId != null &&
            current?.sessionId == activeSessionId
    }

    private fun ensureAvailable() {
        check(mutableCanCall.value) {
            "Voice calls require an authenticated peer with verified call capability."
        }
    }

    private suspend fun handleStartOutgoing(event: Event.StartOutgoing) {
        synchronizeExternalState()
        ensureAvailable()
        val callId = UUID.randomUUID()
        telecomMediaActive = true
        machine.beginOutgoing(callId)
        publish()
        scheduleTimeout(callId)
        try {
            sendSignal(VoiceCallSignal.Invite(callId))
            event.reply.complete(callId)
        } catch (exception: Exception) {
            failCurrentCall(callId, VoiceCallFailure.SIGNALING_FAILED, sendEnd = false)
            event.reply.completeExceptionally(exception)
        }
    }

    private fun handleAcceptIncoming(event: Event.AcceptIncoming) {
        requireCurrentCall(event.callId, VoiceCallStatus.INCOMING_RINGING)
        machine.beginAcceptIncoming(event.callId)
        publish()
        scheduleTimeout(event.callId)
        event.reply.complete(Unit)
    }

    private suspend fun handleRejectIncoming(event: Event.RejectIncoming) {
        requireCurrentCall(event.callId, VoiceCallStatus.INCOMING_RINGING)
        machine.rejectIncoming(event.callId)
        cancelTimeout()
        sendSignal(VoiceCallSignal.Reject(event.callId, VoiceCallRejectReason.USER_DECLINED))
        cleanupMedia()
        publish()
        event.reply.complete(Unit)
    }

    private suspend fun handlePermissionResult(event: Event.PermissionResult) {
        val current = requireCurrentCall(
            event.callId,
            VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION,
        )
        if (!event.granted) {
            if (current.direction == VoiceCallDirection.INCOMING) {
                machine.incomingMicrophonePermissionDenied(event.callId)
            } else {
                machine.outgoingMicrophonePermissionDenied(event.callId)
            }
            cancelTimeout()
            cleanupMedia()
            publish()
            sendSignal(
                VoiceCallSignal.Reject(
                    event.callId,
                    VoiceCallRejectReason.MICROPHONE_PERMISSION_DENIED,
                ),
            )
            event.reply.complete(Unit)
            return
        }

        if (current.direction == VoiceCallDirection.INCOMING) {
            machine.incomingMicrophonePermissionGranted(event.callId)
            publish()
            sendSignal(VoiceCallSignal.Accept(event.callId))
        } else {
            machine.outgoingMicrophonePermissionGranted(event.callId)
            publish()
        }

        createMediaSession(event.callId)
        if (current.direction == VoiceCallDirection.OUTGOING) {
            val offer = mediaSession?.createOffer()
                ?: throw IOException("The voice media session was not created.")
            sendSignal(VoiceCallSignal.SdpOffer(event.callId, offer))
        }
        scheduleTimeout(event.callId)
        event.reply.complete(Unit)
    }

    private suspend fun handleCancelOutgoing(event: Event.CancelOutgoing) {
        requireCurrentCall(
            event.callId,
            VoiceCallStatus.OUTGOING_RINGING,
            VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION,
        )
        machine.cancelOutgoing(event.callId)
        cancelTimeout()
        sendSignal(VoiceCallSignal.Cancel(event.callId))
        cleanupMedia()
        publish()
        event.reply.complete(Unit)
    }

    private suspend fun handleHangUp(event: Event.HangUp) {
        requireCurrentCall(
            event.callId,
            VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION,
            VoiceCallStatus.NEGOTIATING,
            VoiceCallStatus.ACTIVE,
        )
        machine.endLocally(event.callId)
        cancelTimeout()
        sendSignal(VoiceCallSignal.End(event.callId, VoiceCallEndCause.NORMAL))
        cleanupMedia()
        publish()
        event.reply.complete(Unit)
    }

    private fun handleSetMuted(muted: Boolean) {
        mediaSession?.setMuted(muted)
        mutableMuted.value = mediaSession?.isMuted ?: false
    }

    private fun handleTelecomMediaActive(event: Event.TelecomMediaActive) {
        if (!event.active) return
        val current = machine.snapshot
        val media = mediaSession ?: return
        val callId = current.callId ?: return
        if (current.status == VoiceCallStatus.ACTIVE && !media.isActive) {
            // The synchronous callback already reopened the gate. Resume RTP/audio outside
            // Telecom's deadline and keep the signalling actor responsive.
            activationStartedFor = null
            startMediaActivation(callId, media)
        }
    }

    private suspend fun handleRemoteSignal(signal: VoiceCallSignal) {
        synchronizeExternalState()
        when (signal) {
            is VoiceCallSignal.Invite -> {
                if (!mutableCanCall.value || !machine.receiveInvite(signal.callId)) {
                    sendSignal(VoiceCallSignal.Reject(signal.callId, VoiceCallRejectReason.BUSY))
                    return
                }
                telecomMediaActive = true
                publish()
                scheduleTimeout(signal.callId)
            }
            is VoiceCallSignal.Accept -> {
                if (signal.callId != machine.snapshot.callId) return
                machine.remoteAccepted(signal.callId)
                publish()
                scheduleTimeout(signal.callId)
            }
            is VoiceCallSignal.Reject -> {
                if (signal.callId != machine.snapshot.callId) return
                machine.rejectOutgoingRemotely(signal.callId)
                cancelTimeout()
                cleanupMedia()
                publish()
            }
            is VoiceCallSignal.Cancel -> {
                if (signal.callId != machine.snapshot.callId) return
                machine.cancelRemotely(signal.callId)
                cancelTimeout()
                cleanupMedia()
                publish()
            }
            is VoiceCallSignal.End -> {
                if (signal.callId != machine.snapshot.callId) return
                machine.endRemotely(signal.callId)
                cancelTimeout()
                cleanupMedia()
                publish()
            }
            is VoiceCallSignal.SdpOffer -> handleRemoteOffer(signal)
            is VoiceCallSignal.SdpAnswer -> handleRemoteAnswer(signal)
            is VoiceCallSignal.IceCandidate -> handleRemoteIce(signal)
        }
    }

    private suspend fun handleRemoteOffer(signal: VoiceCallSignal.SdpOffer) {
        val current = machine.snapshot
        if (signal.callId != current.callId ||
            current.status != VoiceCallStatus.NEGOTIATING ||
            current.direction != VoiceCallDirection.INCOMING
        ) return
        val media = mediaSession ?: throw IOException("An SDP offer arrived before media setup.")
        val answer = media.acceptOfferAndCreateAnswer(signal.sdp)
        sendSignal(VoiceCallSignal.SdpAnswer(signal.callId, answer))
        startMediaActivation(signal.callId, media)
    }

    private suspend fun handleRemoteAnswer(signal: VoiceCallSignal.SdpAnswer) {
        val current = machine.snapshot
        if (signal.callId != current.callId ||
            current.status != VoiceCallStatus.NEGOTIATING ||
            current.direction != VoiceCallDirection.OUTGOING
        ) return
        val media = mediaSession ?: throw IOException("An SDP answer arrived before media setup.")
        media.applyRemoteAnswer(signal.sdp)
        startMediaActivation(signal.callId, media)
    }

    private suspend fun handleRemoteIce(signal: VoiceCallSignal.IceCandidate) {
        if (signal.callId != machine.snapshot.callId) return
        val media = mediaSession ?: throw IOException("An ICE candidate arrived before media setup.")
        if (!media.addRemoteIceCandidate(signal.sdpMid, signal.sdpMLineIndex, signal.candidate)) {
            throw IOException("The remote ICE candidate was rejected by the P2P media policy.")
        }
    }

    private suspend fun handleLocalIce(event: Event.LocalIce) {
        if (event.generation != mediaGeneration ||
            event.callId != machine.snapshot.callId ||
            machine.snapshot.isTerminal
        ) return
        sendSignal(
            VoiceCallSignal.IceCandidate(
                callId = event.callId,
                sdpMid = event.mid,
                sdpMLineIndex = event.lineIndex,
                candidate = event.candidate,
            ),
        )
    }

    private fun createMediaSession(callId: UUID) {
        val endpoints = endpointProvider.currentVoiceCallP2pEndpoints()
            ?: throw IOException("The exact Wi-Fi Direct call endpoints are no longer available.")
        if (endpoints.sessionId != activeSessionId) {
            throw IOException("The media endpoint belongs to another authenticated session.")
        }
        check(mediaSession == null) { "A voice media session is already active." }
        mediaGeneration += 1L
        val generation = mediaGeneration
        val created = mediaFactory.create(
            endpoints = endpoints,
            scope = scope,
            onLocalIceCandidate = { mid, lineIndex, candidate ->
                events.send(Event.LocalIce(callId, generation, mid, lineIndex, candidate))
            },
            onFailure = { cause ->
                scope.launch {
                    events.send(Event.MediaFailure(callId, generation, cause))
                }
            },
        )
        if (!created.setTelecomActive(telecomMediaActive)) {
            created.close()
            throw IOException("The media session rejected the current Android Telecom state.")
        }
        mediaSession = created
    }

    private fun startMediaActivation(callId: UUID, media: VoiceCallMediaSession) {
        if (activationStartedFor == callId) return
        activationStartedFor = callId
        val generation = mediaGeneration
        activationJob?.cancel()
        activationJob = scope.launch {
            try {
                val active = media.activateAfterVerifiedP2pPath()
                events.send(Event.MediaActivated(callId, generation, active, null))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                events.send(Event.MediaActivated(callId, generation, false, exception))
            }
        }
    }

    private fun handleMediaActivated(event: Event.MediaActivated) {
        val current = machine.snapshot
        if (event.generation != mediaGeneration ||
            event.callId != current.callId ||
            current.status !in setOf(VoiceCallStatus.NEGOTIATING, VoiceCallStatus.ACTIVE)
        ) return
        if (event.succeeded) {
            if (current.status == VoiceCallStatus.NEGOTIATING) {
                machine.mediaConnected(event.callId)
                cancelTimeout()
            }
            mutableMuted.value = mediaSession?.isMuted ?: false
            publish()
        } else {
            failCurrentCall(
                event.callId,
                VoiceCallFailure.MEDIA_NEGOTIATION_FAILED,
                sendEnd = true,
                failureStage = VoiceCallFailureStage.P2P_MEDIA_VERIFICATION,
            )
        }
    }

    private fun handleMediaFailure(event: Event.MediaFailure) {
        if (event.generation != mediaGeneration ||
            event.callId != machine.snapshot.callId ||
            machine.snapshot.isTerminal
        ) return
        failCurrentCall(
            event.callId,
            VoiceCallFailure.MEDIA_NEGOTIATION_FAILED,
            sendEnd = true,
            failureStage = VoiceCallFailureStage.AUDIO_ENGINE,
        )
    }

    private suspend fun handleTimeout(callId: UUID) {
        val current = machine.snapshot
        if (current.callId != callId || current.isTerminal) return
        when (current.status) {
            VoiceCallStatus.INCOMING_RINGING -> {
                machine.rejectIncoming(callId)
                sendSignal(VoiceCallSignal.Reject(callId, VoiceCallRejectReason.TIMED_OUT))
            }
            VoiceCallStatus.OUTGOING_RINGING -> {
                machine.cancelOutgoing(callId)
                sendSignal(VoiceCallSignal.Cancel(callId))
            }
            VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION -> {
                machine.fail(callId, VoiceCallFailure.TIMEOUT, VoiceCallFailureStage.SETUP_TIMEOUT)
                sendSignal(VoiceCallSignal.Reject(callId, VoiceCallRejectReason.TIMED_OUT))
            }
            VoiceCallStatus.NEGOTIATING -> {
                machine.fail(callId, VoiceCallFailure.TIMEOUT, VoiceCallFailureStage.SETUP_TIMEOUT)
                sendSignal(VoiceCallSignal.End(callId, VoiceCallEndCause.INTERNAL_FAILURE))
            }
            else -> return
        }
        cleanupMedia()
        publish()
    }

    private fun handleMediaFailure(callId: UUID, cause: Throwable) {
        if (callId != machine.snapshot.callId || machine.snapshot.isTerminal) return
        failCurrentCall(callId, VoiceCallFailure.MEDIA_NEGOTIATION_FAILED, sendEnd = true)
    }

    private fun failCurrentCall(
        callId: UUID,
        failure: VoiceCallFailure,
        sendEnd: Boolean,
        failureStage: VoiceCallFailureStage? = null,
    ) {
        val current = machine.snapshot
        if (current.callId != callId || current.isTerminal) return
        machine.fail(callId, failure, failureStage)
        cancelTimeout()
        cleanupMedia()
        publish()
        if (sendEnd && controlReady) {
            scope.launch {
                runCatching {
                    sendSignal(VoiceCallSignal.End(callId, VoiceCallEndCause.INTERNAL_FAILURE))
                }
            }
        }
    }

    private fun scheduleTimeout(callId: UUID) {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(callSetupTimeoutMillis)
            events.send(Event.TimedOut(callId))
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun cleanupMedia() {
        activationJob?.cancel()
        activationJob = null
        activationStartedFor = null
        mediaGeneration += 1L
        runCatching { mediaSession?.close() }
        mediaSession = null
        mutableMuted.value = false
    }

    private fun requireCurrentCall(
        callId: UUID,
        vararg allowed: VoiceCallStatus,
    ): VoiceCallSnapshot {
        val current = machine.snapshot
        require(current.callId == callId) { "Call command belongs to another call." }
        check(current.status in allowed) { "Invalid call-state transition." }
        return current
    }

    private fun publish() {
        mutableSnapshot.value = machine.snapshot
        refreshAvailability()
    }

    private fun handleUnexpectedFailure(event: Event, exception: Exception) {
        when (event) {
            is Event.StartOutgoing -> event.reply.completeExceptionally(exception)
            is Event.AcceptIncoming -> event.reply.completeExceptionally(exception)
            is Event.RejectIncoming -> event.reply.completeExceptionally(exception)
            is Event.PermissionResult -> event.reply.completeExceptionally(exception)
            is Event.CancelOutgoing -> event.reply.completeExceptionally(exception)
            is Event.HangUp -> event.reply.completeExceptionally(exception)
            else -> Unit
        }

        val callId = when (event) {
            is Event.RemoteSignal -> event.signal.callId
            is Event.LocalIce -> event.callId
            is Event.MediaFailure -> event.callId
            is Event.MediaActivated -> event.callId
            is Event.TimedOut -> event.callId
            is Event.AcceptIncoming -> event.callId
            is Event.RejectIncoming -> event.callId
            is Event.PermissionResult -> event.callId
            is Event.CancelOutgoing -> event.callId
            is Event.HangUp -> event.callId
            else -> null
        }
        if (callId != null && callId == machine.snapshot.callId) {
            val failure = when (event) {
                is Event.LocalIce -> VoiceCallFailure.SIGNALING_FAILED
                is Event.RemoteSignal -> VoiceCallFailure.PROTOCOL_ERROR
                is Event.MediaFailure,
                is Event.MediaActivated -> VoiceCallFailure.MEDIA_NEGOTIATION_FAILED
                else -> VoiceCallFailure.RESOURCE_UNAVAILABLE
            }
            val stage = when (event) {
                is Event.LocalIce -> VoiceCallFailureStage.ICE_EXCHANGE
                is Event.RemoteSignal -> when (event.signal) {
                    is VoiceCallSignal.SdpOffer,
                    is VoiceCallSignal.SdpAnswer -> VoiceCallFailureStage.SDP_NEGOTIATION
                    is VoiceCallSignal.IceCandidate -> VoiceCallFailureStage.ICE_EXCHANGE
                    else -> VoiceCallFailureStage.CALL_CONTROL
                }
                is Event.MediaFailure -> VoiceCallFailureStage.AUDIO_ENGINE
                is Event.MediaActivated -> VoiceCallFailureStage.P2P_MEDIA_VERIFICATION
                is Event.PermissionResult -> VoiceCallFailureStage.MEDIA_SESSION_SETUP
                else -> VoiceCallFailureStage.CALL_CONTROL
            }
            failCurrentCall(callId, failure, sendEnd = true, failureStage = stage)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionJob?.cancel()
        controlJob?.cancel()
        signalJob?.cancel()
        actorJob?.cancel()
        cancelTimeout()
        activationJob?.cancel()
        cleanupMedia()
        events.close()
        mutableCanCall.value = false
    }

    private companion object {
        const val MAX_QUEUED_EVENTS = 128
        const val DEFAULT_CALL_SETUP_TIMEOUT_MILLIS = 30_000L
        const val MIN_CALL_SETUP_TIMEOUT_MILLIS = 5_000L
        const val MAX_CALL_SETUP_TIMEOUT_MILLIS = 120_000L
    }
}
