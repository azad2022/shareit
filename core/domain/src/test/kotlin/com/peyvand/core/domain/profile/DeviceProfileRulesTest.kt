package com.peyvand.core.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceProfileRulesTest {
    @Test
    fun normalizesNamesToUnicodeNfc() {
        assertEquals("Åزاد", DeviceProfileRules.normalizeProfileName("A\u030Aزاد"))
    }

    @Test
    fun preservesPersianJoinersAndEmoji() {
        val label = "نام\u200Cکاربری\u200D🙂"
        assertEquals(label, DeviceProfileRules.normalizeDeviceName(label))
    }

    @Test
    fun blankNamesBecomeUnconfiguredValues() {
        assertNull(DeviceProfileRules.normalizeDeviceName("   "))
        assertNull(DeviceProfileRules.normalizeProfileName("\n\t"))
    }

    @Test
    fun rejectsNamesAboveUnicodeCodePointLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceProfileRules.normalizeProfileName("🙂".repeat(33))
        }
    }

    @Test
    fun acceptsExactlyThirtyTwoSupplementaryCodePoints() {
        val value = "🙂".repeat(32)
        assertEquals(value, DeviceProfileRules.normalizeDeviceName(value))
    }

    @Test
    fun rejectsControlsBidiOverridesAndUnpairedSurrogates() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceProfileRules.normalizeDeviceName("name\nother")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceProfileRules.normalizeProfileName("admin\u202Etxt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceProfileRules.normalizeProfileName("broken\uD800")
        }
    }

    @Test
    fun defaultDeviceNameUsesManufacturerAndModelWithSafeFallback() {
        assertEquals(
            "SAMSUNG SM-A125F",
            DeviceProfileRules.defaultDeviceName("SAMSUNG", "SM-A125F"),
        )
        assertEquals(
            "Android device",
            DeviceProfileRules.defaultDeviceName("unknown", ""),
        )
    }

    @Test
    fun validatesBoundedJpegEnvelopeAndRejectsOversizedOrNonJpegBytes() {
        DeviceProfileRules.validateAvatar(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            DeviceProfileRules.validateAvatar(ByteArray(DeviceProfileRules.MAX_AVATAR_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceProfileRules.validateAvatar(byteArrayOf(1, 2, 3, 4))
        }
    }
}
