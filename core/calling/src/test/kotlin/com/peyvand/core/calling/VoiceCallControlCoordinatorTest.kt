package com.peyvand.core.calling

import com.peyvand.core.domain.connection.AuthenticatedPeerChannelPurpose
import com.peyvand.core.transfer.session.AuthenticatedPeerChannelMultiplexer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallControlCoordinatorTest {
    @Test
    fun sendsAndReceivesSignalsOnDedicatedAuthenticatedLogicalChannel() = runBlocking {
        val sessionId = UUID.randomUUID()
        val firstInput = PipedInputStream(64 * 1024)
        val secondOutput = PipedOutputStream(firstInput)
        val secondInput = PipedInputStream(64 * 1024)
        val firstOutput = PipedOutputStream(secondInput)
        val first = AuthenticatedPeerChannelMultiplexer(
            sessionId, firstInput, firstOutput,
            closeUnderlying = {
                runCatching { firstInput.close() }
                runCatching { firstOutput.close() }
            },
        )
        val second = AuthenticatedPeerChannelMultiplexer(
            sessionId, secondInput, secondOutput,
            closeUnderlying = {
                runCatching { secondInput.close() }
                runCatching { secondOutput.close() }
            },
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val senderChannel = first.openChannel(AuthenticatedPeerChannelPurpose.CALL_CONTROL)!!
        val receiverChannel = second.openChannel(AuthenticatedPeerChannelPurpose.CALL_CONTROL)!!
        val received = CompletableDeferred<VoiceCallSignal>()
        val sender = VoiceCallControlCoordinator(senderChannel, firstScope, onSignal = {})
        val receiver = VoiceCallControlCoordinator(
            receiverChannel, secondScope, onSignal = { received.complete(it) },
        )
        val invite = VoiceCallSignal.Invite(UUID.randomUUID())

        try {
            receiver.start()
            sender.send(invite)
            assertEquals(invite, withTimeout(2_000) { received.await() })
        } finally {
            sender.close()
            receiver.close()
            first.close()
            second.close()
            firstScope.cancel()
            secondScope.cancel()
        }
    }
}
