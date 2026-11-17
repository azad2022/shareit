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

### Backward-compatible peer capability gate

- The new `CALL_*` frame codes are unknown to older PEYVAND builds; an older multiplexer may treat them as a fatal protocol error and close the entire authenticated session.
- Never send `CALL_INVITE`, SDP, ICE, or any `CALL_*` frame until the remote peer's audio-call capability has been positively established for this exact authenticated peer session.
- DNS-SD TXT records and application version strings are unauthenticated compatibility hints only. They must not authorize call control, microphone access, or media setup.
- The capability proof must remain backward-compatible: a legacy peer that does not implement calls must ignore the probe without its existing text/file session being closed. The proof must bind a fresh nonce, current session ID, both persistent peer identities, and the call-protocol version, and must be authenticated against the trusted peer identity keys. Reject replayed, stale, wrong-session, or wrong-peer proofs.
- A candidate design is a bounded UDP challenge/response on the active Wi-Fi Direct group, signed by the existing trusted peer identity keys. This is a design candidate, not an accepted wire protocol; threat-model it and record a dedicated ADR before implementation. If the proof is absent or invalid, preserve text chat and file transfer and disable voice calling.
- Claiming or emitting on `CALL_CONTROL` is gated on the positive capability proof. This is a hard acceptance requirement, not a best-effort fallback.
- Implementation update (2026-11-17, source commit `abd050b`): `VoiceCallControlSessionHost` now probes an authenticated session automatically and claims exactly one `CALL_CONTROL` lease as soon as signed capability proof is true and the exact session-scoped P2P endpoint is available. It starts one reader immediately, so an incoming INVITE can be delivered while the conversation screen is idle. Signals are forwarded in order through a bounded 64-message queue. Session loss, proof revocation, or control-channel failure closes only the call lease; sends are rejected when the proof is false. Unit-test source covers the host lifecycle and revocation; exact-head CI evidence remains pending.
- Implementation update (2026-11-17, source commit `cb0a2d5d7384cb324ef80ef2a96113b8c2a03e96`): the multiplexer keeps at most 24 unclaimed call frames / 64 KiB in memory to absorb the race between capability verification and claiming the control lease. The frames are replayed in order to the one `CALL_CONTROL` lease when claimed. On overflow, it clears the queue and disables `CALL_CONTROL` for that session only; the authenticated parent TLS session and `CHAT`/`FILE_TRANSFER` leases remain open. This is bounded and covered by new regression tests, pending CI execution.

### Real-time media plane — selected candidate, not yet implemented

- Evaluate a maintained WebRTC native media stack to avoid inventing RTP/RTCP, Opus, congestion/loss recovery, jitter buffers, DTLS-SRTP and acoustic processing.
- Use host ICE candidates only and an empty ICE server list. No public STUN/TURN and no relay.
- Keep DTLS-SRTP enabled. Do not use encryption-disable configuration.
- Bind the DTLS certificate fingerprint exchanged in SDP to the authenticated PEYVAND peer/session over the TLS-protected CALL_CONTROL channel. ICE host candidates and ICE success are not peer authentication.
- Enable WebRTC's `IncludeWifiDirect` Android network-monitor field trial when supported by the pinned build; upstream Android WebRTC includes its Wi-Fi Direct delegate only when that trial is enabled.
- Use `WifiP2pGroup.getInterface()` and the addresses/prefixes on that exact OS interface as the authoritative local P2P interface input. A generic `networkIgnoreMask` filters broad adapter types; it does not identify one Wi-Fi Direct interface.
- Implementation update (2026-11-17, source commit `cb0a2d5d7384cb324ef80ef2a96113b8c2a03e96`): the TCP adapter retains the exact interface name, local IPv4 address and prefix from the group interface; the authenticated session revalidates them against the TLS socket; the signed UDP capability probe binds to that named interface and exact prefix; the WebRTC runtime revalidates interface ownership and peer subnet before PeerConnection creation, local candidate admission, and media activation. This is code-level route admission, not physical proof of the selected radio route; device validation remains mandatory.
- Admit only numeric UDP host candidates that match an address on the local P2P interface. Remote host candidates must match the active P2P group subnet. Reject server-reflexive, peer-reflexive, relay, TCP, mDNS/DNS-name and non-P2P interface candidates.
- Verify the selected ICE candidate pair through WebRTC stats (local/remote candidate type, protocol and addresses) before marking the call active. A candidate being present in SDP or ICE reporting connected is insufficient on its own.
- Bind media sockets to the exact Wi-Fi Direct network where the runtime exposes a bindable `Network`; do not assume every `WifiP2pGroup` has a public `ConnectivityManager.Network`. If the selected route cannot be proven to be Wi-Fi Direct, fail closed. Never fall back to cellular, VPN, public Wi-Fi uplink, STUN or TURN.
- Validate the WebRTC Wi-Fi Direct network-monitor behaviour and selected-pair stats on physical devices. Library support for a P2P interface does not prove that a particular handset routes its media through it.
- Do not send live media on the file-transfer TCP stream: head-of-line blocking and bulk transfer backpressure are unacceptable for real-time speech.
- Measure call quality under simultaneous file transfer. Rate-limit/schedule bulk transfers if they starve audio.

### Android audio lifecycle — selected candidate, not yet implemented

- Evaluate AndroidX Core-Telecom for self-managed VoIP lifecycle, audio focus, endpoint routing and call concurrency. AndroidX Core-Telecom 1.1.0 is the current stable release as of 2026-11-17 and its CallsManager supports API 26+; still audit exact manifest permissions, target-SDK foreground-service requirements and notification policy before pinning dependencies.
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

- **CP-CHAT-004A:** call-control protocol, CALL_CONTROL lease, bounded codec, call state machine, replay protection, unit tests, and the backward-compatible authenticated peer-capability proof. No `CALL_*` frame may be sent before that proof. No live microphone/media yet.
- **CP-CHAT-004B:** WebRTC audio adapter, P2P-interface candidate filtering and selected-pair verification, DTLS-SRTP identity binding, Opus, AEC/NS/AGC and quality telemetry; dependency/ABI/license/size audit.
- **CP-CHAT-004C:** Core-Telecom, microphone permission, foreground lifecycle, in-chat call UI and notification.
- **CP-CHAT-004D:** physical-device acceptance, concurrent file/audio stress, security and performance tuning.
- **Future separate milestone:** video calling. No video/camera implementation in CP-CHAT-004.
