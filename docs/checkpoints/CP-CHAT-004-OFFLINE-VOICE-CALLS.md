# Checkpoint CP-CHAT-004 — Offline One-to-One Voice Calls

- **ID:** CP-CHAT-004
- **Status:** IN_PROGRESS
- **Opened:** 2026-11-17
- **Product approval:** Explicitly granted 2026-11-17
- **Implementation authorization:** Audio-only, one-to-one, full-duplex calls
- **Implementation branch:** `feature/cp-chat-004-offline-audio-calls`
- **Baseline:** `ea47156c1750cf62a1865b46bbc13d366db715aa`
- **Baseline CI:** Run #512 (`44035860815`) passed all three jobs on the exact baseline commit.
- **Video:** Deferred to a separate milestone. No video/camera work is included.

## Objective

Add private, direct, offline voice calls inside one-to-one PEYVAND chat. Either peer may call. Both endpoints operate simultaneously. The receiving peer explicitly accepts before call negotiation or microphone capture begins.

## Approved behaviour

- Full-duplex voice, not Push-to-Talk.
- Use the active authenticated one-to-one Wi-Fi Direct session.
- Invite, accept, reject, cancel, negotiation and hang-up are correlated to the authenticated session and a unique call ID.
- No public Internet, backend, cloud, push service, public STUN/TURN or relay.
- No microphone capture before explicit acceptance and local `RECORD_AUDIO` runtime permission.
- Audio only: no camera permission, video track, recording, transcription or screen sharing.

## Current implementation

- Dedicated `CALL_CONTROL` logical channel added to the existing authenticated TLS multiplexer.
- Version-1 call-control frame types added for invite/accept/reject/cancel/end/SDP/ICE.
- Added Android-free call domain models and one-call-per-session state machine with bounded replay registry.
- Added bounded call signal codec and a control-channel coordinator.
- Added unit tests for channel routing, state transitions, call ID replay, codec validation and control exchange.
- CI workflow now explicitly runs `:core:calling:testDebugUnitTest` and `:core:calling:lintDebug`.
- Live media, microphone capture, UI, Android Telecom and physical-device calling are not implemented yet.

## Technical direction

- CALL_CONTROL is a distinct logical channel over the existing authenticated TLS multiplexer; exactly one reader owns the parent TLS socket.
- Live media must not use the bulk FILE_TRANSFER TCP stream. WebRTC with host-only ICE candidates bound to the Wi-Fi Direct interface and DTLS-SRTP is the candidate, subject to dependency, route-binding and device validation.
- Core-Telecom is the Android call lifecycle candidate, subject to current API/policy validation.
- The media dependency is not approved until binary size, ABI coverage, licence, security maintenance and API 26+ compatibility are audited.
- Call state becomes ACTIVE only after the verified encrypted media path and audio devices are operational.

## Sub-checkpoints

| Sub-checkpoint | Scope | Status |
|---|---|---|
| CP-CHAT-004A | Call-control protocol, dedicated channel, bounded codec, strict state machine and replay tests | IN_PROGRESS |
| CP-CHAT-004B | WebRTC audio, P2P-only route, DTLS-SRTP, Opus, AEC/NS/AGC and telemetry | IN_PROGRESS |
| CP-CHAT-004C | Core-Telecom, mic permission, foreground lifecycle, in-chat UI and notification | IN_PROGRESS |
| CP-CHAT-004D | Physical-device and concurrent file/audio acceptance | IN_PROGRESS |

## Acceptance matrix

- [ ] Outgoing/incoming invite; accept, reject, busy, cancel, timeout and hang-up
- [ ] Duplicate/replayed call IDs rejected for the whole authenticated session
- [ ] No mic access before explicit acceptance and runtime permission
- [ ] Simultaneous two-way audio (full duplex)
- [ ] DTLS-SRTP verified and bound to the authenticated peer/session
- [ ] Only Wi-Fi Direct host candidates selected; no public or alternate network path
- [ ] Earpiece/speaker/wired/Bluetooth routing where supported
- [ ] Mute, audio focus, interruption, notification, background and screen-off behaviour
- [ ] Voice remains intelligible during concurrent file transfer
- [ ] P2P loss, TLS failure, identity mismatch, permission revocation and lifecycle teardown release all resources
- [ ] Physical Galaxy A12 and ASUS X017DA tests with exact Android build/API and repetition counts
- [ ] Latency, jitter, loss, CPU, memory, battery and thermal metrics reported

## Evidence log

- Baseline exact-head CI: Run #512 passed build/unit/lint and Android storage publication + mTLS instrumentation on API 26 and API 36.
- CP-CHAT-004A implementation commit: `dcd3289a7d73945d1763d72d6caa6b034aa19316` (initial implementation; later documentation/CI updates may add commits).
- CP-CHAT-004A CI: pending for the latest exact feature head.
- WebRTC dependency, ABI, licence and packaged-size audit: pending.
- Physical-device tests: not run.
- Current checkpoint: IN_PROGRESS. No usable audio call is claimed.
