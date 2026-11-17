package com.peyvand.core.calling

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.domain.connection.SecureSessionSnapshot
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.domain.connection.VoiceCallP2pEndpointProvider
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Holds the single CALL_CONTROL reader for the lifetime of one authenticated P2P session.
 *
 * The host performs the backwards-compatible capability probe as soon as a usable
 * authenticated session appears. Once both the signed capability proof and exact
 * session-scoped P2P endpoints are available, it claims CALL_CONTROL immediately rather
 * than waiting for a user to tap the call button. This is what lets the app receive an
 * incoming INVITE while the conversation is idle.
 *
 * The host owns only the CALL_CONTROL child lease. It never reads/writes the parent TLS
 * socket itself and closing this host never disconnects CHAT or FILE_TRANSFER.
 */
class VoiceCallControlSessionHost(
    private val secureSession: StateFlow<SecureSessionSnapshot>,
    private val capabilityVerified: StateFlow<Boolean>,
    private val verifyCapability: suspend () -> Boolean,
    private val endpointProvider: VoiceCallP2pEndpointProvider,
    private val claimAuthenticatedChannel: (AuthenticatedPeerChannelPurpose) -> AuthenticatedPeerChannel?,
    private val scope: CoroutineScope,
    private val onSignal: suspend (VoiceCallSignal) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) : Closeable {

    private data class SignalEnvelope(
        val sessionId: UUID,
        val leaseToken: UUID,
        val signal: VoiceCallSignal,
    )

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val queuedSignals = Channel<SignalEnvelope>(capacity = MAX_QUEUED_SIGNALS)
    private val mutableReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = mutableReady.asStateFlow()

    private var currentSessionId: UUID? = null
    private var activeLeaseToken: UUID? = null
    private var probeStartedFor: UUID? = null
    private var claimStartedFor: UUID? = null
    private var activeCoordinator: VoiceCallControlCoordinator? = null
    private var sessionMonitorJob: Job? = null
    private var capabilityMonitorJob: Job? = null
    private var signalConsumerJob: Job? = null
    private var capabilityProbeJob: Job? = null

    /** Start once; monitoring then follows authenticated-session and capability state. */
    fun start() {
        check(!closed.get()) { "Voice call control session host is closed." }
        check(started.compareAndSet(false, true)) { "Voice call control session host already started." }

        signalConsumerJob = scope.launch {
            for (envelope in queuedSignals) {
                val belongsToCurrentSession = synchronized(lock) {
                    !closed.get() && currentSessionId == envelope.sessionId &&
                        activeLeaseToken == envelope.leaseToken && activeCoordinator != null
                }
                if (!belongsToCurrentSession) continue
                try {
                    onSignal(envelope.signal)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    failCurrentSession(envelope.sessionId, exception)
                }
            }
        }

        sessionMonitorJob = scope.launch {
            secureSession.collect { reconcileSession() }
        }
        capabilityMonitorJob = scope.launch {
            capabilityVerified.collect { reconcileSession() }
        }
    }

    /**
     * Sends one call signal through the already-claimed, session-owned control lease.
     * Calling code must still enforce call ID and state-machine transitions.
     */
    suspend fun send(signal: VoiceCallSignal) {
        val coordinator = synchronized(lock) {
            check(!closed.get()) { "Voice call control session host is closed." }
            check(capabilityVerified.value) { "Voice-call capability proof is not currently valid." }
            activeCoordinator
        } ?: throw IOException("The authenticated peer has not negotiated voice-call capability.")
        try {
            coordinator.send(signal)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            failCurrentSessionForCoordinator(coordinator, exception)
            throw exception
        }
    }

    private fun reconcileSession() {
        if (closed.get()) return

        val authenticated = secureSession.value.status == SecureSessionStatus.AUTHENTICATED
        val endpoints = if (authenticated) endpointProvider.currentVoiceCallP2pEndpoints() else null
        val desiredSessionId = endpoints?.sessionId

        var startProbeFor: UUID? = null
        var claimFor: UUID? = null
        var capabilityRevoked = false
        synchronized(lock) {
            if (closed.get()) return
            if (currentSessionId != desiredSessionId) {
                activeCoordinator?.close()
                activeCoordinator = null
                activeLeaseToken = null
                currentSessionId = desiredSessionId
                probeStartedFor = null
                claimStartedFor = null
                capabilityProbeJob?.cancel()
                capabilityProbeJob = null
                mutableReady.value = false
            }

            val sessionId = desiredSessionId ?: return
            if (capabilityVerified.value) {
                if (activeCoordinator == null && claimStartedFor != sessionId) {
                    claimStartedFor = sessionId
                    claimFor = sessionId
                }
            } else {
                if (activeCoordinator != null) {
                    activeCoordinator?.close()
                    activeCoordinator = null
                    activeLeaseToken = null
                    claimStartedFor = null
                    mutableReady.value = false
                    capabilityRevoked = true
                }
                if (probeStartedFor != sessionId) {
                    probeStartedFor = sessionId
                    startProbeFor = sessionId
                }
            }
        }

        if (capabilityRevoked) {
            runCatching {
                onFailure(IOException("Voice-call capability proof is no longer valid for this session."))
            }
        }
        startProbeFor?.let(::launchCapabilityProbe)
        claimFor?.let(::claimControlLease)
    }

    private fun launchCapabilityProbe(sessionId: UUID) {
        val job = scope.launch {
            val verified = try {
                verifyCapability()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            val stillCurrent = synchronized(lock) {
                !closed.get() && currentSessionId == sessionId &&
                    secureSession.value.status == SecureSessionStatus.AUTHENTICATED
            }
            if (verified && stillCurrent) reconcileSession()
        }
        synchronized(lock) {
            if (!closed.get() && currentSessionId == sessionId) {
                capabilityProbeJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun claimControlLease(sessionId: UUID) {
        val channel = try {
            claimAuthenticatedChannel(AuthenticatedPeerChannelPurpose.CALL_CONTROL)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            failCurrentSession(sessionId, exception)
            return
        }

        if (channel == null) {
            failCurrentSession(
                sessionId,
                IOException("The authenticated CALL_CONTROL lease could not be claimed."),
            )
            return
        }

        val leaseToken = UUID.randomUUID()
        lateinit var coordinator: VoiceCallControlCoordinator
        coordinator = VoiceCallControlCoordinator(
            channel = channel,
            scope = scope,
            onSignal = { signal ->
                val result = queuedSignals.trySend(SignalEnvelope(sessionId, leaseToken, signal))
                if (result.isFailure) {
                    failCurrentSessionForCoordinator(
                        coordinator,
                        IOException("The bounded incoming call-signal queue is full."),
                    )
                }
            },
            onFailure = { exception ->
                failCurrentSessionForCoordinator(coordinator, exception)
            },
        )

        val accepted = synchronized(lock) {
            if (!closed.get() && currentSessionId == sessionId &&
                secureSession.value.status == SecureSessionStatus.AUTHENTICATED &&
                capabilityVerified.value && activeCoordinator == null
            ) {
                activeCoordinator = coordinator
                activeLeaseToken = leaseToken
                mutableReady.value = true
                true
            } else {
                false
            }
        }

        if (!accepted) {
            coordinator.close()
            return
        }

        try {
            coordinator.start()
        } catch (exception: Exception) {
            failCurrentSessionForCoordinator(coordinator, exception)
        }
    }

    private fun failCurrentSessionForCoordinator(
        coordinator: VoiceCallControlCoordinator,
        exception: Throwable,
    ) {
        val sessionId = synchronized(lock) {
            if (activeCoordinator !== coordinator) return
            currentSessionId
        } ?: return
        failCurrentSession(sessionId, exception)
    }

    private fun failCurrentSession(sessionId: UUID, exception: Throwable) {
        val failed = synchronized(lock) {
            if (closed.get() || currentSessionId != sessionId) {
                false
            } else {
                activeCoordinator?.close()
                activeCoordinator = null
                activeLeaseToken = null
                claimStartedFor = sessionId
                mutableReady.value = false
                true
            }
        }
        if (failed) runCatching { onFailure(exception) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionMonitorJob?.cancel()
        capabilityMonitorJob?.cancel()
        signalConsumerJob?.cancel()
        capabilityProbeJob?.cancel()
        synchronized(lock) {
            activeCoordinator?.close()
            activeCoordinator = null
            activeLeaseToken = null
            currentSessionId = null
            mutableReady.value = false
        }
        queuedSignals.close()
    }

    private companion object {
        const val MAX_QUEUED_SIGNALS = 64
    }
}
