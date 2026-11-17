# Checkpoint CP-CHAT-004 — Offline One-to-One Voice Calls

- **ID:** CP-CHAT-004
- **Status:** IN_PROGRESS
- **Last updated:** 2026-11-17
- **Product approval:** Explicitly granted 2026-11-17
- **Implementation authorization:** Audio-only, one-to-one, full-duplex calls
- **Implementation branch:** `feature/cp-chat-004-offline-audio-calls`
- **Latest code implementation SHA:** `226b6ef574aa2af92ff24a5f354ed3a886746482`
- **Exact-head CI:** Run #563 (`44061209704`), targeting the implementation SHA above; status was `pending` when this checkpoint update was prepared. No exact-head APK artifact has been verified.
- **Last fully green CI for the older baseline:** Run #533 (`44045018880`), exact head `091b539f4acd1c8132c684e2077591d224853b1a`. Its APK artifact is not a build of the current implementation SHA.
- **Video:** Deferred to a separate milestone; no camera/video implementation is included.

## Objective

Add private, direct, offline voice calls inside the existing one-to-one PEYVAND chat. Either peer may initiate. Calls are full-duplex and require explicit remote acceptance plus local Android microphone permission before capture starts.

## Approved behaviour

- Full-duplex one-to-one audio; not Push-to-Talk.
- Use only the active authenticated Wi-Fi Direct peer session.
- Invite, accept, reject, busy, cancel, timeout, SDP/ICE negotiation and hang-up are correlated to a unique call ID and authenticated session.
- No public Internet, backend, cloud, push delivery, public STUN/TURN or relay.
- No microphone capture before explicit call acceptance and local `RECORD_AUDIO` runtime permission.
- Audio only: no camera permission, video track, recording, transcription or screen sharing.
- If exact P2P media routing and authenticated media path cannot be established, media activation fails closed.

## Implemented in the current code line

### Call control, capability and multiplexing

- A dedicated `CALL_CONTROL` logical channel is owned by the existing authenticated TLS multiplexer. The parent socket still has one reader and one serialized frame writer; audio media does not use the bulk file-transfer TCP channel.
- The session-scoped `VoiceCallControlSessionHost` automatically verifies call capability and claims exactly one call-control lease only after the signed proof succeeds. It owns one ordered signal reader and releases the lease on capability revocation or session teardown.
- The legacy-safe capability probe is a signed UDP challenge/response bound to a fresh nonce, authenticated session UUID, call-protocol version and both persistent peer identity fingerprints. A legacy peer that ignores the datagram receives no unknown `CALL_*` TLS frames.
- The multiplexer buffers at most 24 unclaimed call frames / 64 KiB while the call consumer is being attached. Frames are replayed in order. Queue overflow disables only calling for that authenticated session; it must not close the parent session or the CHAT / FILE_TRANSFER leases.
- The bounded signal codec, replay-aware state machine and call orchestrator cover incoming/outgoing signalling, acceptance, rejection, cancellation, hang-up, SDP/ICE exchange, permission result and media activation. The orchestrator is hosted at application scope and is invalidated with the authenticated peer session.

### Wi-Fi Direct and media admission

- The P2P TCP adapter retains the exact interface name from `WifiP2pGroup.getInterface()`, together with the local IPv4 address and prefix selected on that interface.
- The authenticated session verifies that its TLS local endpoint belongs to that exact interface and that the authenticated peer endpoint is in the corresponding P2P subnet. Endpoints are in-memory and session-scoped.
- The signed capability listener binds to the named interface/address. The WebRTC media factory and runtime revalidate interface ownership, address, prefix and peer subnet before PeerConnection creation, ICE admission and media activation.
- The pinned WebRTC build is audio-only in this integration. ICE servers are empty, TCP candidates are disabled, and candidate policy admits numeric IPv4 UDP host candidates only. mDNS, IPv6, TCP, server-reflexive and relay candidates are rejected.
- SDP admission is restricted to an audio-only Opus DTLS-SRTP session. The selected ICE pair is checked through WebRTC stats for host/UDP candidate type, exact local and peer P2P addresses, a successful nominated pair, connected DTLS and SRTP cipher.
- Capture and playout start disabled. Recording begins only after acceptance, Android permission, verified media path and successful AudioRecord start. Mute disables the outgoing track; Telecom inactive state disables capture and playout synchronously.
- This code-level interface and candidate validation is not physical proof that a particular handset routes media through its Wi-Fi Direct radio. Device-level route proof remains outstanding.

### Application, permission and Android Telecom integration

- The app now depends on the calling module and exposes the orchestrator to the chat presentation layer. The voice action is enabled only for a currently authenticated peer with verified voice capability.
- The conversation UI includes outgoing ringing/cancel, incoming accept/reject, microphone-permission, connecting, active, mute/unmute and hang-up states. User-visible copy added for this scope is currently Persian; other target-language bundles are not yet complete.
- AndroidX Core-Telecom is integrated through a dedicated `PeyvandVoiceCallService`. `CallsManager` is owned by this service, not by a ViewModel or Activity. CallStyle notifications provide incoming/ongoing call presentation and system actions.
- The service initially runs with the `phoneCall` foreground-service type. The `microphone` type is added only after explicit call acceptance and successful runtime `RECORD_AUDIO` permission. If the microphone foreground-service promotion fails or times out, the call path fails closed and the orchestrator is not told that microphone permission succeeded.
- Notification permission and notification-channel availability are checked before starting/accepting a call through the UI.
- Core-Telecom active/inactive callbacks do not wait for SDP/ICE negotiation. Telecom hold immediately disables capture/playout; media resume runs asynchronously and failure is surfaced to the call actor.
- The P2P connection is retained while an active call is running in the background. When the app is backgrounded with no active/pending call, local discovery/network monitoring is stopped. Incoming-call delivery is not promised when there is no active authenticated peer session.

## Not yet verified / not yet accepted

- No complete physical two-device voice call has been completed or independently evidenced.
- No physical tests have validated both initiator directions, ringing/accept/reject/busy/cancel/end/timeout or the full-duplex audio path.
- No physical test has proven that the selected WebRTC candidate pair uses the exact Wi-Fi Direct interface on the target phones.
- No Android device tests have validated earpiece, speaker, wired and Bluetooth routing; audio focus; competing cellular calls; screen-off; notification interaction; process recreation; permission revocation; or OEM-specific foreground-service behaviour.
- No concurrent voice-call plus large-file-transfer stress test has been run.
- No measured latency, jitter, packet loss, CPU, RAM, battery or thermal results exist.
- No exact-head APK artifact has been verified for SHA `226b6ef574aa2af92ff24a5f354ed3a886746482`. The old #533 APK is not a valid substitute.

## Sub-checkpoints

| Sub-checkpoint | Scope | Status |
|---|---|---|
| CP-CHAT-004A | Call-control protocol, bounded codec, state machine, replay handling, legacy-safe capability proof and channel lifecycle | IN_PROGRESS |
| CP-CHAT-004B | WebRTC audio, exact P2P-interface admission, DTLS-SRTP, Opus and media lifecycle | IN_PROGRESS |
| CP-CHAT-004C | Core-Telecom, microphone permission, foreground lifecycle, UI and notifications | IN_PROGRESS |
| CP-CHAT-004D | Physical-device, route and concurrent file/audio acceptance | IN_PROGRESS |

## CI evidence

- Run #522 (`44041996814`) passed build/unit/lint and Android storage/mTLS instrumentation on API 26 and API 36 for earlier microphone-permission/state-machine changes.
- Run #523 (`44042714084`) passed all three jobs for the WebRTC dependency catalog and P2P candidate policy.
- Run #526 (`44043724377`) passed all three jobs for the earlier audio-only WebRTC media-engine baseline; its APK is not the current code.
- Run #533 (`44045018880`) passed build/unit/lint and Android storage/mTLS instrumentation on API 26 and API 36 for exact head `091b539f4acd1c8132c684e2077591d224853b1a`.
- Run #556 (`44059671482`) failed on an earlier ancestor of the current source line. Its logs identified duplicate availability methods and related Kotlin compile errors plus a call-control queue test that used a malformed 4-byte INVITE payload. The duplicate methods and smart-cast issue were removed, the WebRTC call-site was brought into line with the media API, and the regression test now uses the protocol's required 16-byte call UUID payload.
- Run #563 (`44061209704`) is the exact-code-head validation for SHA `226b6ef574aa2af92ff24a5f354ed3a886746482`; it was pending when this document was updated. The result and APK artifact must be read back before reporting a successful build or sharing an APK.
- Cancelled superseded runs are not reported as test failures.

## Acceptance matrix

- [ ] Outgoing/incoming call invitation, accept, reject, busy, cancel, timeout and hang-up on two physical devices
- [ ] Duplicate/replayed call IDs rejected for the whole authenticated session
- [ ] Signed capability proof verified on two devices; a legacy peer keeps text/file service without teardown
- [ ] No microphone capture before remote acceptance / local user acceptance as appropriate and runtime permission
- [ ] Full-duplex audio verified in both directions
- [ ] Selected ICE pair verified as UDP host endpoints on the exact P2P interface; DTLS and SRTP confirmed
- [ ] Earpiece/speaker/wired/Bluetooth routing through Telecom where supported
- [ ] Mute, audio focus, interruptions, notifications, background and screen-off behaviour
- [ ] Voice remains intelligible during concurrent file transfer
- [ ] P2P loss, TLS failure, identity mismatch, permission revocation and process teardown release all resources
- [ ] Physical Galaxy A12 and ASUS X017DA tests with Android build/API, roles and repetition counts recorded
- [ ] Latency, jitter, packet loss, CPU, memory, battery and thermal metrics recorded

## Current assessment

The current branch contains the signalling host, orchestrator, WebRTC media foundation, P2P interface admission, chat call UI, microphone permission flow and Core-Telecom / foreground-service integration. These are implementation facts, not yet verified phone-to-phone behaviour. The current exact-code-head CI result and artifact are not yet verified; physical route, audio and lifecycle tests remain outstanding.

Checkpoint status remains **IN_PROGRESS**. No successful phone-to-phone voice call is claimed.
