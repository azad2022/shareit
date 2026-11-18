# CP-CHAT-004E — Stage-specific voice-call failure diagnostics

- **ID:** CP-CHAT-004E
- **Status:** IN_PROGRESS
- **Opened:** 2026-11-18
- **Owner authorization:** Explicitly granted 2026-11-18 as the first implementation slice after the product-scope expansion.
- **Implementation branch:** `feature/pey-vand-product-scope-and-call-diagnostics`
- **Base:** `feature/cp-chat-004-offline-audio-calls`
- **Latest implementation commit:** `b876d0082aa224a84061585162c3a8962975b0ee`
- **Product evidence:** Owner reproduced call failure in both initiator directions on Samsung Galaxy A12 (Android 12) and ASUS X017DA (Android 8/9). Receiver sees the in-app incoming-call dialog, accepts, grants first-time microphone permission, then both devices show an error/end state after about 5–15 seconds. Speakerphone and media/call volume were checked. Text chat continues to work after call failure. Exact error text and microphone privacy indicator are unknown.

## Objective

Make the existing one-to-one audio-call state machine retain a safe, machine-readable failure stage so the next physical reproduction can distinguish call-control, permission, media setup, SDP, ICE, P2P media verification, audio-engine and timeout failures. Do not weaken the existing authenticated-session, P2P-only, DTLS-SRTP or microphone-consent policy.

## First implementation slice

- Added `VoiceCallFailureStage` to the domain call snapshot.
- Added an optional failure-stage argument to the call state machine's terminal failure transition.
- Classified orchestrator failures from ICE exchange, SDP signalling, media-engine callbacks, P2P media activation and setup timeout.
- Added state-machine and orchestrator unit assertions that a media verification failure retains the `P2P_MEDIA_VERIFICATION` stage.
- Kept failure metadata to enums only. Do not store exception messages, SDP, ICE credentials/candidates, raw addresses, device labels, audio data, message contents or peer fingerprints in diagnostic output.

## Important limitation

This slice records the stage in `VoiceCallSnapshot`; it does not yet prove the root cause, expose a user-facing diagnostics panel, or add physical-device evidence. It also does not yet distinguish every Android Telecom/foreground-service/audio-device failure. Follow-up work must add stage instrumentation at the Android service and concrete WebRTC media adapter boundaries without logging sensitive payloads.

## Acceptance criteria

1. Unit tests cover failure-stage persistence for P2P media verification and setup timeout.
2. Orchestrator tests verify that failed media activation and media-engine callbacks produce the expected stage.
3. A failed call leaves the authenticated text chat usable; no call failure may tear down the parent chat session unless the parent authenticated session itself is lost.
4. No failure stage or diagnostic includes SDP, ICE secrets/candidates, raw IP/MAC, device name, peer fingerprint, message body or audio bytes.
5. CI passes on the exact implementation head.
6. Physical reproduction on both Galaxy A12 and ASUS X017DA records APK SHA-256, Android/API/build, call initiator, stage, elapsed time, and whether text chat remains active.

## Validation

- The state-machine test and orchestrator test were extended to assert `P2P_MEDIA_VERIFICATION` is retained.
- **No local Gradle command was executed in this environment.**
- **No CI result has yet been observed for implementation head `b876d0082aa224a84061585162c3a8962975b0ee`.**
- **No physical-device retest has been performed.**
- Existing CI #567 is historical and predates the latest integrated CP-004 source; it is not evidence for this branch.

## Remaining work

- Add tests for orchestrator mapping of `MediaActivated`, `MediaFailure`, SDP, ICE and timeout events.
- Trace Android Telecom service registration, incoming answer, microphone foreground-service promotion, and audio-route callbacks.
- Trace concrete WebRTC initialization, ICE candidate admission, selected pair, DTLS-SRTP verification and AudioRecord/AudioTrack startup using bounded, privacy-safe stage codes.
- Expose the stage to a debug-only diagnostics surface or an exportable redacted report, with no sensitive network/media material.
- Run CI and physical tests on the two owner-provided devices in both call directions.
- Keep the status `IN_PROGRESS` until all applicable acceptance evidence is recorded.
