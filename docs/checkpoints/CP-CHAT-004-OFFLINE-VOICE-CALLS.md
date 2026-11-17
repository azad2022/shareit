# Checkpoint CP-CHAT-004 — Offline One-to-One Voice Calls

- **ID:** CP-CHAT-004
- **Status:** IN_PROGRESS
- **Last updated:** 2026-11-17
- **Product approval:** Explicitly granted 2026-11-17
- **Implementation authorization:** Audio-only, one-to-one, full-duplex calls
- **Implementation branch:** `feature/cp-chat-004-offline-audio-calls`
- **Latest implementation source commit:** `cb0a2d5d7384cb324ef80ef2a96113b8c2a03e96`
- **Latest fully green CI before this source commit:** Run #533 (`44045018880`), exact head `091b539f4acd1c8132c684e2077591d224853b1a`
- **CI for latest source commit:** No exact-head run observed at checkpoint update; no build/test claim is made for `cb0a2d5`.
- **APK:** No debug APK artifact has been verified for `cb0a2d5`. The prior run #533 artifact belongs to the previous SHA and is not a test build for these changes.
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

## Implemented foundations

### Call-control and state

- Dedicated `CALL_CONTROL` logical channel over the existing authenticated TLS multiplexer.
- Version-1 signalling frames for invite/accept/reject/cancel/end/SDP/ICE, with bounded payload codec.
- One-call-per-authenticated-session state machine and bounded call-ID replay registry.
- State ordering requires remote acceptance before requesting microphone permission on outgoing calls.
- Remote rejection/cancellation handling and timeout rejection reason.
- Unit-test source covers channel routing, state transitions, call-ID replay, codec bounds and control exchange; new multiplexer regressions are added in source commit `cb0a2d5`, but have not yet been run by CI.
- New bounded pre-claim `CALL_CONTROL` queue: max 24 frames / 64 KiB. Early frames are replayed in order when the sole call-channel consumer claims the lease. Queue overflow disables calling only for that authenticated session; chat and file transfer remain operational.

### Backwards-compatible capability proof

- Signed UDP challenge/response is separate from TLS call-control frames.
- The TCP layer now retains the exact `WifiP2pGroup.getInterface()` name, local IPv4 address and prefix selected for the P2P TCP socket.
- The authenticated session verifies that its TLS local address belongs to that exact interface and that the authenticated peer address is in the same P2P subnet.
- The capability listener binds using the exact interface name and matching local address/prefix, not an interface inferred from an address lookup alone.
- Request/response bind a cryptographic nonce, current session UUID, protocol version and both persistent peer fingerprints.
- Both directions use the persistent Android Keystore peer-identity key and verify against the already trusted peer public key.
- `CALL_CONTROL` cannot be claimed until capability verification succeeds for the current session.
- Legacy peers that ignore the UDP datagram receive no unknown `CALL_*` TLS frame; chat/file TLS traffic remains untouched.
- Capability state, UDP listener, endpoint binding and multiplexer queues are cleared when the authenticated session ends.

### Media foundation

- Audited `io.github.webrtc-sdk:android-prefixed-stripped:150.7871.04`; published AAR reported as 30,526,217 bytes, compared with 49,068,339 bytes for the full variant. The stripped artifact removes software video codecs and uses the `livekit.org.webrtc` namespace.
- Added media dependency only to `:core:calling`.
- Added P2P ICE admission policy: numeric IPv4, UDP, host candidates only, exact local/peer P2P IP matching; rejects mDNS, IPv6, TCP, srflx and relay candidates.
- Added audio-only SDP admission: one audio media section, Opus, DTLS fingerprint, RTCP mux, trickle ICE, no embedded candidates and no SDES crypto.
- Added WebRTC runtime/session foundation: no STUN/TURN servers, TCP ICE disabled, one local call session per process audio device, audio track mute control, bounded remote candidate queue and explicit teardown.
- The WebRTC call-session factory now requires the named P2P interface and local prefix. The runtime revalidates that the interface is up, still owns that exact address/prefix, and shares the authenticated peer subnet before creating a call session; the same check runs before PeerConnection creation, local ICE admission and media activation.
- Capture and playout are explicitly disabled during setup. Activation waits for selected candidate-pair and DTLS/SRTP statistics to match the exact P2P endpoints, then confirms AudioRecord actually starts.
- Endpoints remain ephemeral, session-ID scoped, not persisted and not exposed in UI state.

## Not implemented / not yet accepted

- No complete end-to-end call orchestrator yet connects invitation, permission result, SDP/ICE control, WebRTC lifecycle and state machine.
- `:app` does not yet depend on `:core:calling`; no application service owns the call runtime.
- No Android Core-Telecom integration or managed audio endpoint routing yet.
- No microphone foreground-service lifecycle, incoming-call notification or background/screen-off handling yet.
- The manifest still does not declare `RECORD_AUDIO` / `MANAGE_OWN_CALLS` or the call foreground-service permissions; microphone permission is not wired to an app-level runtime request.
- The in-chat voice-call button remains disabled; no call UI or localization strings exist.
- No end-to-end physical-device voice call has been completed.
- No verified concurrent voice + large file transfer test has been run.
- No audio quality/latency/jitter/loss/CPU/RAM/battery/thermal results exist yet.

## Sub-checkpoints

| Sub-checkpoint | Scope | Status |
|---|---|---|
| CP-CHAT-004A | Call-control protocol, bounded codec, state machine, replay handling, legacy-safe capability proof and channel lifecycle | IN_PROGRESS |
| CP-CHAT-004B | WebRTC audio, exact P2P-interface admission, DTLS-SRTP, Opus and media lifecycle | IN_PROGRESS |
| CP-CHAT-004C | Core-Telecom, microphone permission, foreground lifecycle, UI and notifications | IN_PROGRESS |
| CP-CHAT-004D | Physical-device and concurrent file/audio acceptance | IN_PROGRESS |

## CI evidence

- Run #522 (`44041996814`) passed build/unit/lint and Android storage/mTLS instrumentation on API 26 and API 36 for the microphone-permission/state-machine correction.
- Run #523 (`44042714084`) passed all three jobs for the WebRTC dependency catalog and P2P candidate policy.
- Run #526 (`44043724377`) passed all three jobs for the audio-only WebRTC media engine on API 26 and API 36; debug APK artifact was uploaded.
- Run #533 (`44045018880`) passed build/unit/lint and Android storage/mTLS instrumentation on API 26 and API 36 for exact source head `091b539f4acd1c8132c684e2077591d224853b1a`.
- Runs #527–#532 were cancelled as subsequent commits superseded them; these are not reported as test failures.
- No exact-head CI result or APK artifact for source commit `cb0a2d5d7384cb324ef80ef2a96113b8c2a03e96` is recorded yet.

## Acceptance matrix

- [ ] Outgoing/incoming call invitation, accept, reject, busy, cancel, timeout and hang-up end-to-end
- [ ] Duplicate/replayed call IDs rejected for the whole authenticated session
- [ ] Signed capability proof verified on two devices; legacy peer keeps text/file service without teardown
- [ ] No microphone access before acceptance and runtime permission
- [ ] Full-duplex audio verified in both directions
- [ ] Selected ICE pair verified as UDP host endpoints on the exact P2P interface; DTLS and SRTP confirmed
- [ ] Earpiece/speaker/wired/Bluetooth routing through Telecom where supported
- [ ] Mute, audio focus, interruption, notifications, background and screen-off behaviour
- [ ] Voice remains intelligible during concurrent file transfer
- [ ] P2P loss, TLS failure, identity mismatch, permission revocation and process teardown release all resources
- [ ] Physical Galaxy A12 and ASUS X017DA tests with Android build/API and repetition counts recorded
- [ ] Latency, jitter, packet loss, CPU, memory, battery and thermal metrics recorded

## Current assessment

The branch has a call-control protocol/state-machine foundation, a signed backward-compatible capability probe, a WebRTC audio engine foundation, bounded pre-claim call signalling, and stricter exact-interface P2P admission. It still does **not** provide a usable in-app voice-call feature. The end-to-end orchestrator, Core-Telecom/permission/foreground integration, in-chat controls and physical-device validation are outstanding.

Checkpoint status remains **IN_PROGRESS**. No successful phone-to-phone voice call is claimed.
