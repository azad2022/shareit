# ADR-0020 — Offline One-to-One Voice Calls over Wi-Fi Direct

- **Status:** Accepted for implementation; app-side integration present, physical acceptance pending
- **Decision date:** 2026-11-17
- **Owner:** Product owner
- **Scope:** CP-CHAT-004 audio-only calling
- **Branch:** `feature/cp-chat-004-offline-audio-calls`
- **Baseline:** `ea47156c1750cf62a1865b46bbc13d366db715aa`
- **Current code line at last update:** `226b6ef574aa2af92ff24a5f354ed3a886746482`
- **Related:** ADR-0017 peer identity/mTLS; ADR-0018 session-scoped chat and attachment consent; ADR-0019 channel ownership and protocol framing

## Context and accepted behaviour

The owner approved direct, offline, bidirectional voice calls inside one-to-one chat. Either device may initiate. Calls are full-duplex, not Push-to-Talk. The other device explicitly accepts. Permission to auto-receive files is not consent to a call and does not grant microphone access.

This milestone is audio-only. No camera permission, video track, video UI, video codec, call recording, transcription, group call or screen sharing is permitted in this branch.

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

### Backward-compatible peer capability gate

- Do not send `CALL_INVITE`, SDP, ICE or any `CALL_*` frame until the remote peer's audio-call capability has been positively established for this exact authenticated peer session.
- DNS-SD TXT records and application version strings are unauthenticated compatibility hints only. They never authorize call control, microphone access or media setup.
- The accepted proof is a bounded UDP challenge/response on the active Wi-Fi Direct group, signed by each side's existing persistent Android Keystore peer-identity key. It binds a fresh nonce, current session ID, both persistent peer identity fingerprints and the call-protocol version. Reject stale, replayed, wrong-session or wrong-peer proofs.
- A legacy peer that ignores the UDP datagram receives no unknown `CALL_*` TLS frame; its existing text/file TLS session remains untouched.
- The proof state is scoped to the authenticated session. Session teardown clears the capability listener and proof state. `CALL_CONTROL` cannot be claimed or used before proof succeeds.

### Session-owned signalling lease and bounded pre-claim queue

- `VoiceCallControlSessionHost` monitors the authenticated session, performs the capability check, claims exactly one `CALL_CONTROL` lease and owns one ordered signal reader. It releases the lease on session loss, capability revocation or control failure.
- The TLS multiplexer absorbs the race between the first incoming call frame and the consumer claim using a queue capped at 24 complete frames / 64 KiB. Queued frames are delivered in order to the single consumer.
- On queue overflow, the queue is discarded and calling is disabled for that authenticated session only. The parent TLS session and CHAT / FILE_TRANSFER leases must remain operational. The regression test uses a valid 16-byte UUID payload so it tests queue overflow, not malformed-frame rejection.

### Real-time media plane

- The selected media engine is the pinned audio-only WebRTC Android artifact. It is used for RTP/RTCP, Opus, jitter/loss handling and DTLS-SRTP rather than a hand-rolled media protocol.
- ICE server list is empty. There is no STUN, TURN or relay. TCP ICE is disabled.
- SDP admission permits only a single audio media section using Opus and DTLS-SRTP. Video media, video tracks and camera permission are excluded.
- WebRTC's Wi-Fi Direct network-monitor path is explicitly enabled through `NetworkMonitorAutoDetect.setIncludeWifiDirect(true)`.
- The TCP adapter retains `WifiP2pGroup.getInterface()`, the exact local IPv4 address and prefix. The authenticated session validates the TLS socket's local address against that interface and verifies the peer address is within the P2P subnet.
- The signed capability listener binds to the named P2P interface and the same local address/prefix. The WebRTC media factory and runtime revalidate interface ownership, address, prefix and peer subnet before PeerConnection creation, local candidate admission and media activation.
- Admit only numeric IPv4 UDP host candidates on the expected P2P addresses. Reject server-reflexive, peer-reflexive, relay, TCP, mDNS/DNS-name and non-P2P candidates.
- Before activating audio, inspect WebRTC stats for a successful nominated host/UDP selected candidate pair, exact local/peer P2P addresses, connected DTLS and a negotiated SRTP cipher. The code-level check is fail-closed.
- Exact interface/candidate admission is not physical proof that a particular handset routes media through its Wi-Fi Direct radio. Physical device verification remains mandatory. Never fall back to cellular, VPN or a public Wi-Fi uplink.
- Do not send live media on the file-transfer TCP stream: head-of-line blocking and bulk-transfer backpressure are unacceptable for real-time speech. Measure voice quality under concurrent file transfer before enabling broad release claims.

### Android audio and Telecom lifecycle

- AndroidX Core-Telecom is used for the self-managed audio-call lifecycle, audio focus and system call state. `CallsManager` is owned by `PeyvandVoiceCallService`, not by an Activity or ViewModel.
- The service declares the `phoneCall` and `microphone` foreground-service types. It starts with `phoneCall` only; the `microphone` type is enabled only after explicit call acceptance and successful runtime `RECORD_AUDIO` permission. Failure or timeout while promoting the microphone foreground-service type fails closed.
- The manifest declares the required phone-call/microphone foreground-service permissions and `MANAGE_OWN_CALLS`. The app checks notification permission and the call-notification channel before starting or accepting a call through the in-chat UI.
- Incoming and ongoing call notifications use Android call-style presentation. User actions route back through the authenticated call state machine. Notification actions are not substitutes for explicit call acceptance or microphone permission.
- Core-Telecom active/inactive callbacks must finish within the platform callback deadline. They do not wait for SDP/ICE. Inactive synchronously disables microphone capture and remote playout; media resume is asynchronous and reports failure back to the call orchestrator.
- The app retains the local P2P session while a live call is foregrounded by Telecom, including screen-off/background operation after the call has been accepted. When the app is backgrounded without a live/pending call, local discovery/network monitoring is stopped.
- There is no incoming call delivery when no authenticated peer session exists. No persistent background Internet listener, push service or server is permitted.
- The WebRTC audio source and audio track are not activated before remote acceptance and local microphone permission. Hang-up, permission failure, P2P/session loss, Telecom failure or media failure must release media resources and call ownership.

## Privacy and excluded scope

- No public Internet, backend, accounts, cloud, public call server, relay or recording.
- No transcription, contacts integration, group/conference calling, screen sharing, video or camera in CP-CHAT-004.
- Do not persist audio media in the chat database. A future call-history retention policy needs its own decision.
- File auto-receive consent never substitutes for call acceptance or microphone permission.

## Acceptance gates

1. Invite, accept, reject, busy, cancel, end, timeout and duplicate/replayed call IDs on two physical phones.
2. No mic access before explicit acceptance and runtime permission; the microphone foreground-service type is promoted only after both gates.
3. Full-duplex Opus audio in both directions with DTLS-SRTP verified.
4. Media path proven to use the exact Wi-Fi Direct interface only; no STUN/TURN, relay, cellular, VPN or public uplink.
5. Earpiece/speaker/wired/Bluetooth routing where supported; mute, focus, interruptions and competing cellular calls.
6. Echo cancellation, noise suppression, gain control, jitter, loss, CPU, memory, battery and thermal metrics.
7. Background/screen-off behaviour within Android Telecom and foreground-service policy; no incoming delivery promise without an active authenticated session.
8. Concurrent file transfer does not starve voice; transfer scheduling is measured.
9. Deterministic release on hang-up, P2P loss, permission revocation, session teardown and process/lifecycle failure.
10. Physical Galaxy A12 and ASUS X017DA runs, both connection initiator roles, exact API/build and repetition counts.

Compilation and emulator tests do not pass this checkpoint. Report measured latency, jitter, packet loss, CPU, memory, battery and thermal data with device/API and radio conditions.

## Implementation sequence

- **CP-CHAT-004A:** signalling, CALL_CONTROL lease, bounded codec/queue, state machine, replay protection and authenticated legacy-safe capability proof. Code is present; exact-head CI and two-device tests still gate acceptance.
- **CP-CHAT-004B:** WebRTC audio adapter, exact P2P-interface candidate filtering, selected-pair verification, DTLS-SRTP, Opus and media lifecycle. Code-level foundation is present; physical route/media proof remains outstanding.
- **CP-CHAT-004C:** Core-Telecom, runtime microphone permission, phoneCall/microphone foreground-service lifecycle, in-chat call UI and notifications. App-side integration is present; device/OEM and lifecycle validation remains outstanding.
- **CP-CHAT-004D:** physical-device acceptance, concurrent file/audio stress, security and performance tuning.
- **Future separate milestone:** video calling. No video/camera implementation in CP-CHAT-004.
