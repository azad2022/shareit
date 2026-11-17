package com.peyvand.core.calling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallSdpPolicyTest {
    private val validSdp = """
        v=0
        o=- 1 1 IN IP4 192.168.49.1
        s=-
        t=0 0
        a=group:BUNDLE 0
        a=msid-semantic: WMS peyvand-audio
        m=audio 50000 UDP/TLS/RTP/SAVPF 111
        c=IN IP4 192.168.49.1
        a=ice-ufrag:abcd
        a=ice-pwd:abcdefghijklmnopqrstuvwxyz
        a=ice-options:trickle
        a=fingerprint:sha-256 AA:BB:CC:DD
        a=setup:actpass
        a=mid:0
        a=rtcp-mux
        a=rtpmap:111 opus/48000/2
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun acceptsAudioOnlyOpusDtlsSrtpSdpWithDefaultSendReceive() {
        assertTrue(VoiceCallSdpPolicy.isValidAudioOnlySdp(validSdp))
    }

    @Test
    fun rejectsVideoApplicationAndMultipleMediaSections() {
        assertFalse(
            VoiceCallSdpPolicy.isValidAudioOnlySdp(
                validSdp.replace(
                    "m=audio 50000 UDP/TLS/RTP/SAVPF 111",
                    "m=audio 50000 UDP/TLS/RTP/SAVPF 111\r\nm=video 50002 UDP/TLS/RTP/SAVPF 96",
                ),
            ),
        )
        assertFalse(
            VoiceCallSdpPolicy.isValidAudioOnlySdp(
                validSdp.replace(
                    "m=audio 50000 UDP/TLS/RTP/SAVPF 111",
                    "m=application 50000 UDP/DTLS/SCTP webrtc-datachannel",
                ),
            ),
        )
    }

    @Test
    fun rejectsEmbeddedCandidatesAndSdesCrypto() {
        assertFalse(
            VoiceCallSdpPolicy.isValidAudioOnlySdp(
                validSdp.replace(
                    "a=mid:0",
                    "a=mid:0\r\na=candidate:1 1 udp 1 192.168.49.1 50000 typ host",
                ),
            ),
        )
        assertFalse(
            VoiceCallSdpPolicy.isValidAudioOnlySdp(
                validSdp.replace(
                    "a=mid:0",
                    "a=mid:0\r\na=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:abcd",
                ),
            ),
        )
    }

    @Test
    fun rejectsNonOpusNonDtlsOrReceiveOnlySdp() {
        assertFalse(VoiceCallSdpPolicy.isValidAudioOnlySdp(validSdp.replace("opus/48000/2", "PCMU/8000")))
        assertFalse(VoiceCallSdpPolicy.isValidAudioOnlySdp(validSdp.replace("UDP/TLS/RTP/SAVPF", "RTP/AVP")))
        assertFalse(VoiceCallSdpPolicy.isValidAudioOnlySdp(validSdp.replace("a=setup:actpass", "a=setup:invalid")))
        assertFalse(VoiceCallSdpPolicy.isValidAudioOnlySdp(validSdp.replace("a=rtcp-mux", "a=rtcp:9")))
        assertFalse(VoiceCallSdpPolicy.isValidAudioOnlySdp(validSdp.replace("a=mid:0", "a=mid:0\r\na=recvonly")))
    }
}
