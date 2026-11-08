package com.peyvand.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class OptionalCursorLongTest {
    @Test
    fun nullColumnValueRemainsUnknownAndDoesNotReadZero() {
        var valueRead = false
        val value = readOptionalNonNegativeLong(
            columnIndex = 2,
            isNull = { true },
            getLong = {
                valueRead = true
                0L
            },
        )

        assertNull(value)
        assertFalse(valueRead)
    }

    @Test
    fun missingColumnRemainsUnknownWithoutCallingCursor() {
        val value = readOptionalNonNegativeLong(
            columnIndex = -1,
            isNull = { error("isNull must not be called for a missing column") },
            getLong = { error("getLong must not be called for a missing column") },
        )

        assertNull(value)
    }

    @Test
    fun validZeroByteFileRetainsZeroSize() {
        val value = readOptionalNonNegativeLong(
            columnIndex = 0,
            isNull = { false },
            getLong = { 0L },
        )

        assertEquals(0L, value)
    }

    @Test
    fun negativeSizeIsRejectedAsUnknown() {
        val value = readOptionalNonNegativeLong(
            columnIndex = 0,
            isNull = { false },
            getLong = { -1L },
        )

        assertNull(value)
    }

    @Test
    fun positiveSizeIsPreserved() {
        assertEquals(
            12_345L,
            readOptionalNonNegativeLong(1, { false }, { 12_345L }),
        )
    }
}
