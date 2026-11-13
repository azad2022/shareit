package com.peyvand.core.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatMessageRulesTest {
    @Test
    fun peerFingerprintIsNormalizedToLowercase() {
        val identity = PeerIdentityId.fromSha256Fingerprint("A".repeat(64))

        assertEquals("a".repeat(64), identity.sha256Fingerprint)
    }

    @Test
    fun peerFingerprintRejectsInvalidLengthAndNonHexCharacters() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityId.fromSha256Fingerprint("a".repeat(63))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityId.fromSha256Fingerprint("g".repeat(64))
        }
    }

    @Test
    fun acceptsPersianAndMultilineMessagesWithinUtf8Limit() {
        ChatMessageRules.validateBody("سلام پیوند\nاین یک پیام آزمایشی است")
    }

    @Test
    fun rejectsBlankMessages() {
        val error = assertThrows(InvalidChatMessageException::class.java) {
            ChatMessageRules.validateBody(" \n\t ")
        }

        assertEquals(ChatMessageValidationError.EMPTY, error.reason)
    }

    @Test
    fun acceptsMessagesAtExactUtf8ByteLimit() {
        ChatMessageRules.validateBody("پ".repeat(ChatMessageRules.MAX_MESSAGE_UTF8_BYTES / 2))
        ChatMessageRules.validateBody("🙂".repeat(ChatMessageRules.MAX_MESSAGE_UTF8_BYTES / 4))
        ChatMessageRules.validateBody("a".repeat(ChatMessageRules.MAX_MESSAGE_UTF8_BYTES))
    }

    @Test
    fun rejectsMessageAboveUtf8ByteLimit() {
        val error = assertThrows(InvalidChatMessageException::class.java) {
            ChatMessageRules.validateBody(
                "پ".repeat((ChatMessageRules.MAX_MESSAGE_UTF8_BYTES / 2) + 1),
            )
        }

        assertEquals(ChatMessageValidationError.TOO_LARGE, error.reason)
    }

    @Test
    fun rejectsNullAndForbiddenControlCharacters() {
        val error = assertThrows(InvalidChatMessageException::class.java) {
            ChatMessageRules.validateBody("hello\u0000world")
        }

        assertEquals(
            ChatMessageValidationError.FORBIDDEN_CONTROL_CHARACTER,
            error.reason,
        )
    }

    @Test
    fun rejectsUnpairedSurrogatesInsteadOfSilentlyReplacingThem() {
        val error = assertThrows(InvalidChatMessageException::class.java) {
            ChatMessageRules.validateBody("broken\uD800")
        }

        assertEquals(ChatMessageValidationError.INVALID_UNICODE, error.reason)
    }
}
