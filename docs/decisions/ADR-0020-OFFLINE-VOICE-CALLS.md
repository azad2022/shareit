# ADR-0020 — Offline One-to-One Voice Calls over Wi-Fi Direct

- **Status:** Accepted for implementation
- **Decision date:** 2026-11-17
- **Owner:** Product owner
- **Scope:** CP-CHAT-004 audio-only calling
- **Branch:** `feature/cp-chat-004-offline-audio-calls`
- **Baseline:** `ea47156c1750cf62a1865b46bbc13d366db715aa`
- **Related:** ADR-0017 peer identity/mTLS; ADR-0018 session-scoped chat and attachment consent; ADR-0019 channel ownership and protocol framing

## Context and accepted behaviour

The owner approved direct, offline, bidirectional voice calls inside one-to-one chat. Either device may initiate. Calls are full-duplex, not Push-to-Talk. The other device explicitly accepts. Permission to auto-receive files is not consent to a call and does not grant microphone access.

This milestone is audio-only. No camera permission, video track, video UI, or video codec is permitted in this branch.

## Architecture

Separate call control from real-time media.

### Call-control plane

- Reuse the existing authenticated, mutually verified TLS peer session.
- Claim one dedicated `CALL_CONTROL` logical channel from `AuthenticatedPeerChannelMultiplexer`.
- Keep exactly one reader of the parent TLS socket and its existing serialized, complete-frame writer. Do not create another socket reader/writer.
- Bind every control frame to the header session ID and a cryptographically random call UUID.
- Control includes invite, accept, reject, cancel, end, SDP offer/answer and trickled ICE candidates. Enforce strict payload bounds and UTF-8 validation before parsing.
- Retain call IDs for the full authenticated session lifetime with a bounded registry. Reject replayed/duplicate IDs, wrong-session frames, unknown IDs and invalid state transitions.
- Call state must not become ACTIVE on invite acceptance or SDP success alone. The media adapter may mark ACTIVE only after the Wi-Fi Direct route, DTLS-SRTP and audio devices are verified.

### Real-time media plane — selected candidate, not yet implemented

- Evaluate a maintained WebRTC native media stack to avoid inventing RTP/RTCP, Opus, congestion/loss recovery, jitter buffers, DTLS-SRTP and acoustic processing.
- Use host ICE candidates only and an empty ICE server list. No public STUN/TURN and no relay.
- Keep DTLS-SRTP enabled. Do not use encryption-disable configuration.
- Bind the DTLS certificate fingerprint exchanged in SDP to the authenticated PEYVAND peer/session over the TLS-protected CALL_CONTROL channel. ICE host candidates and ICE success are not peer authentication.
- Pin media sockets to the exact Wi-Fi Direct network/interface and validate the selected candidate pair against the current P2P group. If the selected route cannot be proven to be Wi-Fi Direct, fail closed. Never fall back to cellular, VPN, public Wi-Fi uplink, STUN or TURN.
- Validate WebRTC's Android Wi-Fi Direct network-monitor behaviour on physical devices. Library support for a P2P interface does not prove that a particular handset routes its media through it.
- Do not send live media on the file-transfer TCP stream: head-of-line blocking and bulk transfer backpressure are unacceptable for real-time speech.
- Measure call quality under simultaneous file transfer. Rate-limit/schedule bulk transfers if they starve audio.

### Android audio lifecycle — selected candidate, not yet implemented

- Evaluate AndroidX Core-Telecom for self-managed VoIP lifecycle, audio focus, endpoint routing and call concurrency. Confirm current stable release, min API, manifest permissions, foreground-service and notification rules before pinning dependencies.
- Request `RECORD_AUDIO` just in time. No microphone track or capture before explicit call acceptance and local permission.
- Start from a visible user action. Respect background microphone restrictions, foreground-service types, notifications, screen-off, process recreation, permission revocation and vendor behaviour.
- Mute must stop outgoing media at the media engine. Hang-up, P2P loss, TLS/session teardown, identity mismatch, permission revocation or media failure releases microphone, audio route, tracks, PeerConnection, ICE resources, CALL_CONTROL lease and Telecom ownership.
- Support earpiece, speaker, wired and Bluetooth endpoints where supported by Android/device. Do not compete with Telecom by manually overriding its selected route.
- No incoming call delivery exists when the authenticated peer session is absent. No push server or background Internet listener is permitted.

## Privacy and excluded scope

- No public Internet, backend, accounts, cloud, public call server, relay or recording.
- No transcription, contacts integration, group/conference calling, screen sharing, video or camera in CP-CHAT-004.
- Do not persist audio media in the chat database. A future call-history retention policy needs its own decision.
- File auto-receive consent never substitutes for call acceptance or microphone permission.

## Acceptance gates

1. Invite, accept, reject, busy, cancel, end, timeout and duplicate/replayed call IDs.
2. No mic access before explicit acceptance and local `RECORD_AUDIO` permission.
3. Full-duplex Opus audio in both directions with DTLS-SRTP verified.
4. Media path proven to use Wi-Fi Direct only; no STUN/TURN, relay, cellular, VPN or public uplink.
5. Earpiece/speaker/wired/Bluetooth routing where supported; mute, focus, interruptions and competing cellular calls.
6. Echo cancellation, noise suppression, gain control, jitter, loss, CPU, memory, battery and thermal metrics.
7. Background/screen-off operation only within Android Telecom and foreground-service policy.
8. Concurrent file transfer does not starve voice; transfer scheduling is measured.
9. Deterministic release on hang-up, P2P loss, permission revocation, session teardown and process/lifecycle failure.
10. Physical Galaxy A12 and ASUS X017DA runs, both connection initiator roles, exact API/build and repetition counts.

Compilation or emulator tests do not pass this checkpoint. Report measured latency, jitter, packet loss, CPU, memory, battery and thermal data with device/API and radio conditions.

## Implementation sequence

- **CP-CHAT-004A:** call control protocol, CALL_CONTROL lease, bounded codec, call state machine, replay protection, unit tests. No live microphone/media yet.
- **CP-CHAT-004B:** WebRTC audio adapter, Wi-Fi Direct-only ICE/network binding, DTLS-SRTP identity binding, Opus, AEC/NS/AGC and quality telemetry; dependency/ABI/license/size audit.
- **CP-CHAT-004C:** Core-Telecom, microphone permission, foreground lifecycle, in-chat call UI and notification.
- **CP-CHAT-004D:** physical-device acceptance, concurrent file/audio stress, security and performance tuning.
- **Future separate milestone:** video calling. No video/camera implementation in CP-CHAT-004.
