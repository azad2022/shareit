package com.peyvand.core.transfer.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class PairingShortAuthenticationStringTest {
    private val sessionId = UUID.fromString("ba8772f5-4ce3-4c06-b41c-c012413b0a02")

    private fun sender(nonceOffset: Int = 0) = PairingEndpointMaterial(
        certificateFingerprintSha256 = ByteArray(32) { (it + 1).toByte() },
        nonce = ByteArray(32) { (it + nonceOffset).toByte() },
        role = PairingParticipantRole.SENDER,
    )

    private fun receiver(nonceOffset: Int = 0) = PairingEndpointMaterial(
        certificateFingerprintSha256 = ByteArray(32) { (it + 80).toByte() },
        nonce = ByteArray(32) { (it + 100 + nonceOffset).toByte() },
        role = PairingParticipantRole.RECEIVER,
    )

    @Test
    fun `both endpoints derive the same ten digit code independent of argument order`() {
        val sender = sender()
        val receiver = receiver()

        val fromSender = PairingShortAuthenticationString.derive(sessionId, sender, receiver)
        val fromReceiver = PairingShortAuthenticationString.derive(sessionId, receiver, sender)

        assertEquals(10, fromSender.length)
        assertTrue(fromSender.all(Char::isDigit))
        assertEquals(fromSender, fromReceiver)
    }

    @Test
    fun `fresh session nonce changes the authentication code`() {
        val initial = PairingShortAuthenticationString.derive(sessionId, sender(), receiver())
        val changed = PairingShortAuthenticationString.derive(sessionId, sender(), receiver(1))
        assertNotEquals(initial, changed)
    }

    @Test
    fun `same product role at both endpoints is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingShortAuthenticationString.derive(sessionId, sender(), sender(5))
        }
    }
}