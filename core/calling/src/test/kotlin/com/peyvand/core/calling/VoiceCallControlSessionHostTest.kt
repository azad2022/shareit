package com.peyvand.core.calling

import com.peyvand.core.domain.connection.AuthenticatedPeerChannel
import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.domain.connection.SecureSessionSnapshot
import com.peyvand.core.domain.connection.SecureSessionStatus
import com.peyvand.core.domain.connection.VoiceCallP2pEndpointProvider
import com.peyvand.core.domain.connection.VoiceCallP2pEndpoints
import com.peyvand.core.transfer.protocol.TransferFrameCodec
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallControlSessionHostTest {
    @Test
    fun automaticallyClaimsControlAndReceivesAnInviteAfterCapabilityProof() = runBlocking {
        val sessionId = UUID.randomUUID()
        val session = MutableStateFlow(
            SecureSessionSnapshot(
                status = SecureSessionStatus.AUTHENTICATED,
                peerIdentityId = "trusted-peer",
            ),
        )
        val capability = MutableStateFlow(false)
        val channel = TestChannel(sessionId)
        val claimCount = AtomicInteger()
        val verified = AtomicInteger()
        val received = CompletableDeferred<VoiceCallSignal>()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = VoiceCallControlSessionHost(
            secureSession = session,
            capabilityVerified = capability,
            verifyCapability = {
                verified.incrementAndGet()
                capability.value = true
                true
            },
            endpointProvider = FixedEndpointProvider(sessionId),
            claimAuthenticatedChannel = { purpose ->
                assertEquals(AuthenticatedPeerChannelPurpose.CALL_CONTROL, purpose)
                claimCount.incrementAndGet()
                channel
            },
            scope = scope,
            onSignal = { received.complete(it) },
        )

        val invite = VoiceCallSignal.Invite(UUID.randomUUID())
        try {
            host.start()
            withTimeout(2_000) { host.isReady.first { it } }
            assertEquals(1, verified.get())
            assertEquals(1, claimCount.get())

            TransferFrameCodec.write(
                VoiceCallSignalCodec.encode(sessionId, invite),
                channel.peerOutput,
            )
            channel.peerOutput.flush()
            assertEquals(invite, withTimeout(2_000) { received.await() })
        } finally {
            host.close()
            channel.close()
            scope.cancel()
        }
    }

    @Test
    fun unsupportedPeerNeverClaimsCallControlAndDoesNotAffectParentSession() = runBlocking {
        val sessionId = UUID.randomUUID()
        val session = MutableStateFlow(
            SecureSessionSnapshot(
                status = SecureSessionStatus.AUTHENTICATED,
                peerIdentityId = "legacy-peer",
            ),
        )
        val capability = MutableStateFlow(false)
        val claimCount = AtomicInteger()
        val probeFinished = CompletableDeferred<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = VoiceCallControlSessionHost(
            secureSession = session,
            capabilityVerified = capability,
            verifyCapability = {
                probeFinished.complete(Unit)
                false
            },
            endpointProvider = FixedEndpointProvider(sessionId),
            claimAuthenticatedChannel = {
                claimCount.incrementAndGet()
                null
            },
            scope = scope,
            onSignal = {},
        )

        try {
            host.start()
            withTimeout(2_000) { probeFinished.await() }
            assertFalse(host.isReady.value)
            assertEquals(0, claimCount.get())
            assertEquals(SecureSessionStatus.AUTHENTICATED, session.value.status)
        } finally {
            host.close()
            scope.cancel()
        }
    }

    @Test
    fun capabilityProofRevocationClosesTheCallLeaseAndDisablesSending() = runBlocking {
        val sessionId = UUID.randomUUID()
        val session = MutableStateFlow(
            SecureSessionSnapshot(
                status = SecureSessionStatus.AUTHENTICATED,
                peerIdentityId = "trusted-peer",
            ),
        )
        val capability = MutableStateFlow(true)
        val channel = TestChannel(sessionId)
        val failures = AtomicInteger()
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = VoiceCallControlSessionHost(
            secureSession = session,
            capabilityVerified = capability,
            verifyCapability = { false },
            endpointProvider = FixedEndpointProvider(sessionId),
            claimAuthenticatedChannel = { channel },
            scope = scope,
            onSignal = {},
            onFailure = { failures.incrementAndGet() },
        )

        try {
            host.start()
            withTimeout(2_000) { host.isReady.first { it } }
            capability.value = false
            withTimeout(2_000) {
                while (!channel.isClosed) kotlinx.coroutines.delay(10)
            }
            assertFalse(host.isReady.value)
            assertEquals(1, failures.get())
            val sendResult = runCatching { host.send(VoiceCallSignal.Invite(UUID.randomUUID())) }
            assertTrue(sendResult.isFailure)
        } finally {
            host.close()
            channel.close()
            scope.cancel()
        }
    }

    @Test
    fun authenticatedSessionLossReleasesOnlyTheCallControlLease() = runBlocking {
        val sessionId = UUID.randomUUID()
        val session = MutableStateFlow(
            SecureSessionSnapshot(
                status = SecureSessionStatus.AUTHENTICATED,
                peerIdentityId = "trusted-peer",
            ),
        )
        val capability = MutableStateFlow(true)
        val channel = TestChannel(sessionId)
        val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = VoiceCallControlSessionHost(
            secureSession = session,
            capabilityVerified = capability,
            verifyCapability = { true },
            endpointProvider = FixedEndpointProvider(sessionId),
            claimAuthenticatedChannel = { channel },
            scope = scope,
            onSignal = {},
        )

        try {
            host.start()
            withTimeout(2_000) { host.isReady.first { it } }
            session.value = SecureSessionSnapshot(status = SecureSessionStatus.IDLE)
            withTimeout(2_000) {
                while (!channel.isClosed) kotlinx.coroutines.delay(10)
            }
            assertFalse(host.isReady.value)
            assertEquals(SecureSessionStatus.IDLE, session.value.status)
        } finally {
            host.close()
            channel.close()
            scope.cancel()
        }
    }

    private class FixedEndpointProvider(sessionId: UUID) : VoiceCallP2pEndpointProvider {
        private val endpoints = VoiceCallP2pEndpoints(
            sessionId = sessionId,
            interfaceName = "p2p-test",
            localAddress = "192.168.49.1".asIpv4Address(),
            localPrefixLength = 24,
            peerAddress = "192.168.49.2".asIpv4Address(),
        )

        override fun currentVoiceCallP2pEndpoints(): VoiceCallP2pEndpoints = endpoints
    }

    private class TestChannel(
        override val sessionId: UUID,
    ) : AuthenticatedPeerChannel {
        private val pipe = PipedInputStream(64 * 1024)
        val peerOutput = PipedOutputStream(pipe)
        private val sent = ByteArrayOutputStream()
        override val inputStream = pipe
        override val outputStream = sent
        @Volatile var isClosed = false
            private set

        override fun close() {
            isClosed = true
            runCatching { pipe.close() }
            runCatching { peerOutput.close() }
        }
    }

}

private fun String.asIpv4Address(): Inet4Address =
    InetAddress.getByName(this) as Inet4Address

