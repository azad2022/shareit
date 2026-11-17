package com.peyvand.core.calling

/**
 * Restricts remote/local negotiation to one audio-only DTLS-SRTP media section.
 *
 * Trickle ICE is mandatory: embedding candidates in SDP bypasses the candidate admission
 * path and would allow non-P2P addresses to enter ICE without validation.
 */
object VoiceCallSdpPolicy {
    fun isValidAudioOnlySdp(sdp: String): Boolean {
        if (sdp.isBlank() ||
            sdp.toByteArray(Charsets.UTF_8).size > VoiceCallSignalCodec.MAX_SDP_BYTES
        ) return false

        val lines = sdp.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val mediaLines = lines.filter { it.startsWith("m=") }
        if (mediaLines.size != 1) return false

        val audioLine = mediaLines.single().split(WHITESPACE)
        if (audioLine.size < 4 || audioLine[0] != "m=audio") return false
        if (!audioLine[2].equals(DTLS_RTP_PROFILE, ignoreCase = true)) return false

        if (lines.any { it.startsWith("a=candidate:", ignoreCase = true) }) return false
        if (lines.any { it.startsWith("a=crypto:", ignoreCase = true) }) return false

        val fingerprint = lines.firstOrNull { it.startsWith("a=fingerprint:", ignoreCase = true) }
            ?: return false
        if (!fingerprint.startsWith("a=fingerprint:sha-256 ", ignoreCase = true)) return false

        val setup = lines.firstOrNull { it.startsWith("a=setup:", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.lowercase()
        if (setup !in VALID_DTLS_SETUP_VALUES) return false

        val hasOpus = lines.any {
            it.startsWith("a=rtpmap:", ignoreCase = true) &&
                it.contains(" opus/48000", ignoreCase = true)
        }
        if (!hasOpus) return false
        if (lines.none { it.equals("a=rtcp-mux", ignoreCase = true) }) return false

        // SDP defaults to sendrecv when no direction attribute exists. Reject every
        // restrictive direction, but do not require a redundant explicit sendrecv line.
        if (lines.any { line ->
                line.equals("a=sendonly", ignoreCase = true) ||
                    line.equals("a=recvonly", ignoreCase = true) ||
                    line.equals("a=inactive", ignoreCase = true)
            }
        ) return false

        return true
    }

    private val WHITESPACE = Regex("\\s+")
    private val VALID_DTLS_SETUP_VALUES = setOf("actpass", "active", "passive")
    private const val DTLS_RTP_PROFILE = "UDP/TLS/RTP/SAVPF"
}
