package com.peyvand.core.calling

import com.peyvand.core.domain.call.VoiceCallStatus
import com.peyvand.core.domain.call.VoiceCallFailureStage
import com.peyvand.core.domain.connection.SecureSessionSnapshot
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.domain.connection.VoiceCallP2pEndpointProvider
import com.peyvand.core.domain.connection.VoiceCallP2pEndpoints
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallOrchestratorTest {

    @Test
    fun immediateIncomingInviteIsNotRejectedBeforeInitialStateFlowEmissionsAreConsumed() = runBlocking {
        val rig = Rig()
        val callId = UUID.randomUUID()
        try {
            rig.orchestrator.start()
            // Deliberately race the first session/control StateFlow emissions.
            rig.remote.send(VoiceCallSignal.Invite(callId))
            rig.awaitStatus(VoiceCallStatus.INCOMING_RINGING)
            assertTrue(rig.orchestrator.canCall.value)
            assertFalse(rig.sent.tryReceive().isSuccess)
        } finally {
            rig.close()
        }
    }

    @Test
    fun outgoingCallWaitsForRemoteAcceptanceAndMicPermissionBeforeMediaCreation() = runBlocking {
        val rig = Rig()
        try {
            rig.startAndAwaitReady()
            val callId = rig.orchestrator.startOutgoingCall()
            assertEquals(VoiceCallSignal.Invite(callId), rig.sent.receive())

            rig.remote.send(VoiceCallSignal.Accept(callId))
            rig.awaitStatus(VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION)
            assertEquals(0, rig.mediaFactory.createCount.get())

            rig.orchestrator.onMicrophonePermissionResult(callId, granted = true)
            assertEquals(VoiceCallSignal.SdpOffer(callId, "audio-offer"), rig.sent.receive())
            assertEquals(1, rig.mediaFactory.createCount.get())

            rig.remote.send(VoiceCallSignal.SdpAnswer(callId, "audio-answer"))
            rig.awaitStatus(VoiceCallStatus.ACTIVE)
            assertTrue(rig.mediaFactory.lastSession?.isActive == true)

            assertTrue(rig.orchestrator.setTelecomMediaActive(false))
            assertFalse(rig.mediaFactory.lastSession?.isActive == true)
            assertTrue(rig.orchestrator.setTelecomMediaActive(true))
            withTimeout(2_000) {
                while (rig.mediaFactory.lastSession?.isActive != true) delay(5)
            }
            assertTrue(rig.mediaFactory.lastSession?.isActive == true)

            rig.remote.send(VoiceCallSignal.End(callId, VoiceCallEndCause.NORMAL))
            rig.awaitStatus(VoiceCallStatus.ENDED)
            assertTrue(rig.mediaFactory.lastSession?.closed == true)
        } finally {
            rig.close()
        }
    }

    @Test
    fun incomingCallRequiresUserAcceptanceAndPermissionBeforeSendingAcceptOrCreatingMedia() = runBlocking {
        val rig = Rig()
        val callId = UUID.randomUUID()
        try {
            rig.orchestrator.start()
            rig.remote.send(VoiceCallSignal.Invite(callId))
            rig.awaitStatus(VoiceCallStatus.INCOMING_RINGING)
            assertEquals(0, rig.mediaFactory.createCount.get())

            rig.orchestrator.acceptIncomingCall(callId)
            rig.awaitStatus(VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION)
            assertFalse(rig.sent.tryReceive().isSuccess)
            assertEquals(0, rig.mediaFactory.createCount.get())

            rig.orchestrator.onMicrophonePermissionResult(callId, granted = true)
            assertEquals(VoiceCallSignal.Accept(callId), rig.sent.receive())
            assertEquals(1, rig.mediaFactory.createCount.get())

            rig.remote.send(VoiceCallSignal.SdpOffer(callId, "audio-offer"))
            assertEquals(VoiceCallSignal.SdpAnswer(callId, "audio-answer"), rig.sent.receive())
            rig.awaitStatus(VoiceCallStatus.ACTIVE)
        } finally {
            rig.close()
        }
    }

    @Test
    fun incomingPermissionDenialSendsRejectAndDoesNotCreateMedia() = runBlocking {
        val rig = Rig()
        val callId = UUID.randomUUID()
        try {
            rig.orchestrator.start()
            rig.remote.send(VoiceCallSignal.Invite(callId))
            rig.awaitStatus(VoiceCallStatus.INCOMING_RINGING)
            rig.orchestrator.acceptIncomingCall(callId)
            rig.orchestrator.onMicrophonePermissionResult(callId, granted = false)
            assertEquals(
                VoiceCallSignal.Reject(callId, VoiceCallRejectReason.MICROPHONE_PERMISSION_DENIED),
                rig.sent.receive(),
            )
            rig.awaitStatus(VoiceCallStatus.FAILED)
            assertEquals(0, rig.mediaFactory.createCount.get())
        } finally {
            rig.close()
        }
    }

    @Test
    fun callCannotBecomeActiveIfMediaPathVerificationFails() = runBlocking {
        val rig = Rig(mediaPathSucceeds = false)
        try {
            rig.orchestrator.start()
            val callId = rig.orchestrator.startOutgoingCall()
            rig.sent.receive()
            rig.remote.send(VoiceCallSignal.Accept(callId))
            rig.awaitStatus(VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION)
            rig.orchestrator.onMicrophonePermissionResult(callId, granted = true)
            rig.sent.receive()
            rig.remote.send(VoiceCallSignal.SdpAnswer(callId, "audio-answer"))
            rig.awaitStatus(VoiceCallStatus.FAILED)
            assertFalse(rig.orchestrator.snapshot.value.status == VoiceCallStatus.ACTIVE)
            assertEquals(
                VoiceCallFailureStage.P2P_MEDIA_VERIFICATION,
                rig.orchestrator.snapshot.value.failureStage,
            )
            assertTrue(rig.mediaFactory.lastSession?.closed == true)
        } finally {
            rig.close()
        }
    }

    @Test
    fun authenticatedSessionLossEndsCallAndReleasesMedia() = runBlocking {
        val rig = Rig()
        try {
            rig.orchestrator.start()
            val callId = rig.orchestrator.startOutgoingCall()
            rig.sent.receive()
            rig.remote.send(VoiceCallSignal.Accept(callId))
            rig.awaitStatus(VoiceCallStatus.AWAITING_MICROPHONE_PERMISSION)
            rig.orchestrator.onMicrophonePermissionResult(callId, granted = true)
            rig.sent.receive()
            rig.session.value = SecureSessionSnapshot(status = SecureSessionStatus.IDLE)
            rig.awaitStatus(VoiceCallStatus.ENDED)
            assertTrue(rig.mediaFactory.lastSession?.closed == true)
        } finally {
            rig.close()
        }
    }

    private class Rig(mediaPathSucceeds: Boolean = true) : AutoCloseable {
        val session = MutableStateFlow(
            SecureSessionSnapshot(
                status = SecureSessionStatus.AUTHENTICATED,
                peerIdentityId = "trusted-peer",
                peerDisplayName = "Peer",
            ),
        )
        val ready = MutableStateFlow(true)
        val remote = Channel<VoiceCallSignal>(capacity = 32)
        val sent = Channel<VoiceCallSignal>(capacity = 32)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mediaFactory = FakeMediaFactory(mediaPathSucceeds)
        val orchestrator = VoiceCallOrchestrator(
            secureSession = session,
            callControlReady = ready,
            incomingSignals = remote.receiveAsFlow(),
            endpointProvider = FixedEndpointProvider(),
            sendSignal = { sent.send(it) },
            mediaFactory = mediaFactory,
            scope = scope,
            callSetupTimeoutMillis = 5_000L,
        )

        suspend fun startAndAwaitReady() {
            orchestrator.start()
            withTimeout(2_000) { orchestrator.canCall.first { it } }
        }

        suspend fun awaitStatus(status: VoiceCallStatus) {
            withTimeout(2_000) { orchestrator.snapshot.first { it.status == status } }
        }

        override fun close() {
            orchestrator.close()
            scope.cancel()
            remote.close()
            sent.close()
        }
    }

    private class FixedEndpointProvider : VoiceCallP2pEndpointProvider {
        private val endpoint = VoiceCallP2pEndpoints(
            sessionId = UUID.fromString("dc1cc7db-6e1f-45d9-95c5-0f0e555be7c7"),
            interfaceName = "p2p-test",
            localAddress = "192.168.49.1".asIpv4Address(),
            localPrefixLength = 24,
            peerAddress = "192.168.49.2".asIpv4Address(),
        )
        override fun currentVoiceCallP2pEndpoints(): VoiceCallP2pEndpoints = endpoint
    }

    private class FakeMediaFactory(
        private val mediaPathSucceeds: Boolean,
    ) : VoiceCallMediaSessionFactory {
        val createCount = AtomicInteger()
        var lastSession: FakeMediaSession? = null

        override fun create(
            endpoints: VoiceCallP2pEndpoints,
            scope: CoroutineScope,
            onLocalIceCandidate: suspend (sdpMid: String?, sdpMLineIndex: Int, candidate: String) -> Unit,
            onFailure: (Throwable) -> Unit,
        ): VoiceCallMediaSession {
            createCount.incrementAndGet()
            return FakeMediaSession(mediaPathSucceeds).also { lastSession = it }
        }
    }

    private class FakeMediaSession(
        private val mediaPathSucceeds: Boolean,
    ) : VoiceCallMediaSession {
        @Volatile var closed = false
            private set
        private var muted = false
        private var telecomActive = mediaPathSucceeds
        override val isMuted: Boolean get() = muted
        override val isActive: Boolean get() = mediaPathSucceeds && telecomActive && !closed

        override suspend fun createOffer(): String = "audio-offer"

        override suspend fun acceptOfferAndCreateAnswer(remoteOfferSdp: String): String {
            check(remoteOfferSdp == "audio-offer")
            return "audio-answer"
        }

        override suspend fun applyRemoteAnswer(remoteAnswerSdp: String) {
            check(remoteAnswerSdp == "audio-answer")
        }

        override suspend fun addRemoteIceCandidate(
            sdpMid: String?,
            sdpMLineIndex: Int,
            candidateSdp: String,
        ): Boolean = true

        override suspend fun activateAfterVerifiedP2pPath(): Boolean {
            return mediaPathSucceeds && telecomActive
        }
        override fun setTelecomActive(active: Boolean): Boolean {
            telecomActive = active
            return true
        }
        override fun setMuted(value: Boolean) { muted = value }
        override fun close() { closed = true }
    }

}

private fun String.asIpv4Address(): Inet4Address =
    InetAddress.getByName(this) as Inet4Address

