# Checkpoint CP-CHAT-004 — Offline One-to-One Voice Calls

- **ID:** CP-CHAT-004
- **Status:** IN_PROGRESS
- **Last updated:** 2026-11-17
- **Product approval:** Explicitly granted 2026-11-17
- **Implementation authorization:** Audio-only, one-to-one, full-duplex calls
- **Implementation branch:** `feature/cp-chat-004-offline-audio-calls`
- **Latest code implementation SHA:** `06b617cca6e317c8706fc44bfb1d9f22dac16f3d`
- **Latest completed CI before this correction:** Run #566 (`44065683391`), head `08cead9d9f3652f888ab4c654e9e0afff4c41877`; build/unit/lint failed for two independent reasons: the calling unit test fake incorrectly coupled Telecom-state application to media-path success, and app Lint reported `MissingPermission` at `NotificationManagerCompat.notify`. The API 26 and API 36 storage/mTLS instrumentation jobs passed; they did not test voice calling. The only APK artifacts were Room instrumentation APKs, not the app.
- **Correction commit:** `5853dff350fb7c8d60f2f86e5dca8a4bf9e15e8b` separates the fake Telecom gate from P2P media verification, strengthens cleanup assertion after media-path failure, and explicitly handles notification-permission revocation between check and notify.
- **Last successful exact-head validation before the current integration:** Run #567 (`44088466989`) passed all 3 jobs on validation head `7e922e03aa578264444187497dd3ec331d329675`: build/unit tests/lint/debug APK, plus Android storage publication and mTLS instrumentation on API 26 and API 36. This run predates profile restoration, avatar hardening and ABI split changes in `06b617...`; it is historical evidence only, not validation of the latest source.
- **Previous universal Debug APK:** artifact `14329833541`, produced from validation head `7e922e03aa578264444187497dd3ec331d329675`; 45,104,972 bytes; SHA-256 `0c515f9980bc1e5adadb180265d400b99103a5ed240751ad45b4d03e97e02295`. This is an earlier call build, not the profile-restored/ABI-split build.
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
- A debug APK is now available for the exact validated source head in Run #567 (SHA `7e922e03aa578264444187497dd3ec331d329675`); its extracted SHA-256 is recorded above. This build artifact is a debug/test build, not a release build, and does not constitute physical-call acceptance.


## Device profile restoration (integrated 2026-11-17)

- CP-PROFILE-001's separate implementation was absent from the CP-CHAT-004 branch used for the previous APK. It has now been ported without replacing CP-CHAT-004's call and attachment code.
- The chat-home header exposes the profile editor. The local user can edit the PEYVAND-only device label, optional profile name, optional avatar and peer-sharing preference. Profile name/avatar render beside the device label in the chat list and conversation header.
- The existing Room database is upgraded from schema version 2 to 3. Migration 2→3 adds the local profile row and optional trusted-peer profile fields while retaining chat data.
- Device label/profile exchange uses identity HELLO v2 inside the existing post-SAS, pinned-mTLS handshake. It remains bound to the existing persistent peer fingerprint; no new socket, reader, channel, backend or relay was added. Both devices must run compatible HELLO v2 builds. Profile changes synchronize on the next secure connection, not mid-session.
- Incoming avatar validation checks bounded JPEG bytes, SHA-256, decoded MIME type, exact 256×256 dimensions and successful bitmap decode before Room caching. An instrumentation test rejects a non-canonical 128×128 peer avatar; combined-branch test execution is pending.
- See [CP-PROFILE-001](CP-PROFILE-001-DEVICE-PROFILE.md) and [ADR-0022](../decisions/ADR-0022-DEVICE-LABEL-AND-USER-PROFILE.md).

## APK size analysis and ABI-specific packaging

- The previous universal APK measured **45,104,972 bytes**. ZIP inspection found `libjingle_peerconnection_so.so` copies for `arm64-v8a` (7,686,440 bytes), `armeabi-v7a` (4,471,732), `x86` (8,770,716) and `x86_64` (8,530,048), totaling **29,458,936 bytes** before other small native libraries.
- The 40+ MB size is expected for a universal WebRTC APK that bundles four native ABIs; it is not evidence of duplicate Kotlin code or unnecessary UI features. Removing WebRTC would remove the audio-call capability and is not an acceptable optimization.
- The app now configures Gradle ABI splits for all four ABIs and keeps a universal APK. CI is configured to publish the universal APK plus dedicated `arm64-v8a` and `armeabi-v7a` debug APKs. The x86/x86_64 splits remain built for architecture coverage.
- Based on the previous universal APK's native ZIP entries, a single-ABI output is estimated around 23.3 MB for arm64-v8a and 20.1 MB for armeabi-v7a before packaging/signature differences. These are estimates only; actual sizes and checksums must be read from successful CI artifacts.
- The universal APK intentionally retains all architectures; users should install the ABI-matched APK. All feature code and the WebRTC native library are retained for each supported ABI.
- **Current integrated source SHA:** `06b617cca6e317c8706fc44bfb1d9f22dac16f3d`. The combined-head CI and actual split APK sizes have not yet been verified. Do not infer success from Run #567.

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
- Run #564 (`44061917498`) targeted exact source head `64bc3f8efa38c39e1af540658824ba2e87762178`. Its app Kotlin compilation failed because of a missing coroutine import, a missing notification vector resource (and consequent notification-builder resolution errors), a local function referenced before its declaration, and test helper/import errors. API 26 and API 36 storage/mTLS instrumentation jobs both passed; this does not validate voice-call media.
- Commit `76777abaad0547f501f6b87480e3a8814a713e4d` applies the initial compile fixes; later Run #566 surfaced the notification Lint and test-double issues described above.
- Commit `5853dff350fb7c8d60f2f86e5dca8a4bf9e15e8b` fixes those Run #566 findings.
- Run #567 (`44088466989`) passed all 3 jobs for exact validation head `7e922e03aa578264444187497dd3ec331d329675`. The `peyvand-debug-apk` artifact (ID `14329833541`) was uploaded successfully. Extracted `app-debug.apk` SHA-256: `0c515f9980bc1e5adadb180265d400b99103a5ed240751ad45b4d03e97e02295`.
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

The current branch contains the signalling host, orchestrator, WebRTC media foundation, P2P interface admission, call UI and Telecom lifecycle, and now also the restored local device/profile editor, authenticated HELLO v2 profile exchange, avatar validation, and ABI-specific debug APK packaging. The last green CI (#567) validates the predecessor code only; integrated source SHA `06b617cca6e317c8706fc44bfb1d9f22dac16f3d` has not yet been validated. Physical route, bidirectional audio, Telecom routing/lifecycle, concurrent transfer, profile interoperability and actual split APK size/installation checks remain outstanding.

Checkpoint status remains **IN_PROGRESS**. No successful phone-to-phone voice call is claimed.
