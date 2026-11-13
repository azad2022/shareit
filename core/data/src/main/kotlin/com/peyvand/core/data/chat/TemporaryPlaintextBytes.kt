package com.peyvand.core.data.chat

/**
 * Best-effort cleanup for temporary plaintext byte arrays owned by this feature.
 *
 * This cannot erase immutable String instances, provider-internal copies, or copies made by
 * the runtime. Callers must still keep plaintext lifetime and references as short as possible.
 */
internal inline fun <T> ByteArray.useAndErase(block: (ByteArray) -> T): T {
    try {
        return block(this)
    } finally {
        fill(0)
    }
}
