# Checkpoint CP-CHAT-004 — Offline One-to-One Voice Calls

- **ID:** CP-CHAT-004
- **Status:** IN_PROGRESS
- **Last updated:** 2026-11-17
- **Product approval:** Explicitly granted 2026-11-17
- **Implementation authorization:** Audio-only, one-to-one, full-duplex calls
- **Implementation branch:** `feature/cp-chat-004-offline-audio-calls`
- **Current head at update:** `11854029ee5acc4f0f316562df4757de470a83e0`
- **Last fully green media-engine CI:** Run #526 (`44043724377`), exact head `53c99ccdec7cc9b4585a59e55b9c1c58127c9188`
- **Latest CI:** Run #531 (`44044872158`), current head `11854029ee5acc4f0f316562df4757de470a83e0`, IN_PROGRESS at checkpoint update
- **Video:** Deferred to a separate milestone; no camera/video implementation is included.

## Objective

Add private, direct, offline voice calls inside the existing one-to-one PEYVAND chat. Either peer may call. Calls are full-duplex and require explicit remote acceptance plus local Android microphone permission before capture starts.

## Approved behaviour

- Full-duplex one-to-one audio; not Push-to-Talk.
- Use only the active authenticated Wi-Fi Direct peer session.
- Invite, accept, reject, busy, cancel, timeout, SDP/ICE negotiation and hang-up are correlated to a unique call ID and authenticated session.
- No public Internet, backend, cloud, push service, public STUN/TURN or relay.
- No microphone capture before explicit call acceptance and local `RECORD_AUDIO` runtime permission.
- Audio only: no camera permission, video track, recording, transcription or screen sharing.

## Implemented

### Call-control and state

- Dedicated `CALL_CONTROL` logical channel over the existing authenticated TLS multiplexer.
- Version-1 signalling frames for invite/accept/reject/cancel/end/SDP/ICE, with bounded payload codec.
- One-call-per-authenticated-session state machine and bounded call-ID replay registry.
- State ordering requires remote acceptance before requesting microphone permission on outgoing calls.
- Added remote rejection/cancellation handling and timeout rejection reason.
- Unit tests cover channel routing, state transitions, call-ID replay, codec bounds and control exchange.

### Backwards-compatible capability proof

- Signed UDP challenge/response is separate from TLS call-control frames.
- Listener binds to the exact IPv4 address of the authenticated Wi-Fi Direct interface and sends only to the peer address of the authenticated TLS socket.
- Request/response bind a cryptographic nonce, current session UUID, protocol version and both persistent peer fingerprints.
- Both directions use the persistent Android Keystore peer-identity key and verify against the already trusted peer public key.
- `CALL_CONTROL` cannot be claimed until capability verification succeeds for the current session.
- Legacy peers that ignore the UDP datagram receive no unknown `CALL_*` TLS frame; chat/file TLS traffic remains untouched.
- Capability state and UDP listener are cleared when the authenticated session ends.

### Media foundation

- Audited `io.github.webrtc-sdk:android-prefixed-stripped:150.7871.04`; published AAR reported as 30,526,217 bytes, compared with 49,068,339 bytes for the full variant. The stripped artifact removes software video codecs and uses the `livekit.org.webrtc` namespace.
- Added media dependency only to `:core:calling`.
- Added P2P ICE admission policy: numeric IPv4, UDP, host candidates only, exact local/peer P2P IP matching; rejects mDNS, IPv6, TCP, srflx and relay candidates.
- Added audio-only SDP admission: one audio media section, Opus, DTLS fingerprint, RTCP mux, trickle ICE, no embedded candidates and no SDES crypto.
- Added WebRTC runtime/session foundation: no STUN/TURN servers, TCP ICE disabled, one local call session per process audio device, audio track mute control, bounded remote candidate queue and explicit teardown.
- Capture and playout are explicitly disabled during setup. Activation waits for the selected candidate pair and DTLS/SRTP statistics to match the exact P2P endpoints, then confirms AudioRecord actually starts.
- Added ephemeral P2P endpoint provider sourced from the authenticated TLS socket. Addresses remain in memory, are session-ID scoped, are not persisted or exposed in UI state, and are cleared on session teardown.

## Not implemented / not yet accepted

- No complete end-to-end call orchestrator yet connects invitation, permission result, SDP/ICE control, WebRTC lifecycle and state machine.
- No Android Core-Telecom integration or managed audio endpoint routing yet.
- No microphone foreground-service lifecycle, incoming-call notification or background/screen-off handling yet.
- No in-chat call UI or localization strings yet.
- No end-to-end physical-device voice call has been completed.
- No verified concurrent voice + large file transfer test has been run.
- No audio quality/latency/jitter/loss/CPU/RAM/battery/thermal results exist yet.

## Sub-checkpoints

| Sub-checkpoint | Scope | Status |
|---|---|---|
| CP-CHAT-004A | Call-control protocol, bounded codec, state machine, replay handling and legacy-safe capability proof | IN_PROGRESS |
| CP-CHAT-004B | WebRTC audio, P2P-only route, DTLS-SRTP, Opus and media lifecycle | IN_PROGRESS |
| CP-CHAT-004C | Core-Telecom, microphone permission, foreground lifecycle, UI and notifications | IN_PROGRESS |
| CP-CHAT-004D | Physical-device and concurrent file/audio acceptance | IN_PROGRESS |

## CI evidence

- Run #522 (`44041996814`) passed build/unit/lint and Android storage/mTLS instrumentation on API 26 and API 36 for the microphone-permission/state-machine correction.
- Run #523 (`44042714084`) passed all three jobs for the WebRTC dependency catalog and P2P candidate policy.
- Run #526 (`44043724377`) passed all three jobs for the audio-only WebRTC media engine on API 26 and API 36; debug APK artifact was uploaded.
- Runs #527–#530 were cancelled as subsequent commits superseded them; these are not reported as test failures.
- Run #531 (`44044872158`) is the current exact-head validation for session-scoped endpoint exposure and remote rejection/cancellation; not yet complete at this checkpoint update.

## Acceptance matrix

- [ ] Outgoing/incoming call invitation, accept, reject, busy, cancel, timeout and hang-up end-to-end
- [ ] Duplicate/replayed call IDs rejected for the whole authenticated session
- [ ] Signed capability proof verified on two devices; legacy peer keeps text/file service without teardown
- [ ] No microphone access before acceptance and runtime permission
- [ ] Full-duplex audio verified in both directions
- [ ] Selected ICE pair verified as UDP host endpoints on exact P2P addresses; DTLS and SRTP confirmed
- [ ] Earpiece/speaker/wired/Bluetooth routing through Telecom where supported
- [ ] Mute, audio focus, interruption, notifications, background and screen-off behaviour
- [ ] Voice remains intelligible during concurrent file transfer
- [ ] P2P loss, TLS failure, identity mismatch, permission revocation and process teardown release all resources
- [ ] Physical Galaxy A12 and ASUS X017DA tests with Android build/API and repetition counts recorded
- [ ] Latency, jitter, packet loss, CPU, memory, battery and thermal metrics recorded

## Current assessment

The project has a compiled and CI-validated WebRTC audio engine foundation, authenticated P2P capability proof, and session-scoped P2P endpoint plumbing. It does **not** yet provide a usable in-app voice-call feature. The next implementation milestone is the end-to-end call orchestrator and Core-Telecom/microphone lifecycle, followed by in-chat UI and physical-device validation.

Current checkpoint remains **IN_PROGRESS**. No successful phone-to-phone voice call is claimed.
