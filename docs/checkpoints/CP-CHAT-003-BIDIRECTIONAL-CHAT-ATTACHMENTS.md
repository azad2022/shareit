# Checkpoint CP-CHAT-003 — Bidirectional In-Chat Attachments

- **ID:** CP-CHAT-003
- **Status:** IN_PROGRESS
- **Opened:** 2026-11-17
- **Product behavior:** Owner-approved 2026-11-17
- **Implementation authorization:** Explicitly granted by the product owner on 2026-11-17
- **Implementation branch:** feature/cp-chat-003-bidirectional-attachments
- **Implementation baseline branch head:** 813016d422cd33c54433e897b72fa79cb976690b
- **CP-CHAT-002 source baseline:** 8e25b56951ef981dcf85ade834edd94881b655a1 (the later 813016d... head contains documentation-only commits on top)
- **Prerequisite evidence:** CP-CHAT-002 remains IN_PROGRESS pending a fully recorded physical-device acceptance matrix. Source SHA 8e25b569... is covered by successful three-job CI runs [44027746525](https://github.com/azad2022/shareit/actions/runs/44027746525) and [44028513484](https://github.com/azad2022/shareit/actions/runs/44028513484) on documentation head 813016d...; each passed build/unit/lint plus API 26 and API 36 instrumentation. The older run 43623779996 was cancelled and is superseded by those later successful runs. The owner reports text and emoji exchange working, but handset model/API, exact installed APK SHA and repeat count have not yet been recorded.

## Objective and approved behavior

After one device requests connection and the other accepts, both devices can send and automatically receive files within the same authenticated one-to-one conversation. There are no permanent sender/receiver roles, separate Prepare to Receive action, or per-file/per-batch consent prompts. The automatic-reception grant ends with that exact session.

Users select outgoing items using native Android pickers. PEYVAND does not build its own gallery or general file manager. Selection, transfer progress and results stay inside the conversation timeline.

## Target design

- Preserve the existing direct Wi-Fi Direct TLS session, peer identity and multiplexer.
- A peer-session supervisor owns CHAT and FILE_TRANSFER channels together.
- Lease FILE_TRANSFER for the active conversation and use exactly one inbound frame dispatcher.
- Replace role-fixed transfer sessions with transfer-ID-correlated state machines; either peer can initiate.
- Permit bounded simultaneous activity in both directions, independent progress and item outcomes.
- Auto-accept valid inbound offers from the authenticated peer only while the session grant is active and quotas/storage checks pass.
- Use private staging, bounded streaming, backpressure, SHA-256 verification and safe publication.
- Store typed attachment references; never put file bytes in the chat database or text channel.
- Re-authenticate the same peer before resuming an interrupted transfer in a later session.

## Target UX

1. One attachment action presents Photos & Videos or Files.
2. Photos/videos use Android Photo Picker; arbitrary documents, audio and binary files use the Android system document picker.
3. The conversation remains open. Incoming and outgoing attachments appear as cards with real progress, known size, integrity and final state.
4. Incoming files do not show Accept/Reject prompts. Users can cancel an individual transfer or end the session.
5. Received content is not opened, executed or installed automatically.

## Safety and platform constraints

- Use content URIs with ContentResolver; no raw-path assumptions, custom gallery or broad storage permission for selection.
- Cloud-backed picker items that cannot be read offline fail honestly; no cloud relay/upload is added.
- Enforce item/session limits, free-space reserve, concurrency bounds, timeouts and cleanup.
- Keep ADR-0017's mutual TLS, persistent identity proof and current SAS security requirement until a security review approves any change.
- Wi-Fi/P2P loss or TLS failure destroys the scoped grant, closes channels and marks incomplete transfers interrupted. Text history/outbox remains local.
- Default destination layout and older-API storage permission behavior are a separate decision.

## Dependencies and known gap

The inspected CP-CHAT-002 source currently has separate send/prepare-receive routes. Its SingleFileTransferCoordinator is prepared for one local direction and reads its own transfer stream; it cannot safely be instantiated twice on the same logical channel. The current conversation attachment route exits the chat and does not persist attachment bubbles. A single channel owner/dispatcher and correlated transfer state machines are required.

The source baseline candidate is 8e25b56951ef981dcf85ade834edd94881b655a1. CI run 43623779996 is CANCELLED, not PASSED. No physical two-device validation is recorded. Do not treat this design as implementation evidence.

## Acceptance evidence

- Automated tests for concurrent send/receive, one-reader ownership, frame correlation, quotas, malformed/replayed frames, cancellation and resource bounds.
- Storage tests for URI access, private staging, integrity, publication, source revocation and low space.
- Physical Galaxy A12 / ASUS X017DA tests in both connection roles: both transfer directions, simultaneous transfer, many/large/unknown-size files, Wi-Fi off, interruption, process recreation and re-authenticated resume.
- Record exact SHA, CI run, APK checksum, device/API/build, repetition count and failures.

## Governance

Implementation is authorized on feature/cp-chat-003-bidirectional-attachments. Keep CP-CHAT-002 acceptance status IN_PROGRESS until its physical two-device matrix is recorded. Do not merge or modify main, CP-004, or PR #3 as part of this work. CP-CHAT-003 remains IN_PROGRESS until automated, storage, security, accessibility and physical-device acceptance evidence is complete.

## Implementation audit and classroom usability constraints (2026-11-17)

The inspected source at baseline 813016d confirms:

- ChatViewModel owns the CHAT logical-channel coordinator only. It does not own a FILE_TRANSFER lease for the conversation lifecycle.
- PeyvandApp navigates from the conversation to a separate attachment-selection route and then to a directional transfer route.
- SingleFileTransferCoordinator.prepareSession(isSender, ...) claims FILE_TRANSFER for a fixed local direction; send/receive completion closes the channel. It is not a bidirectional session service.
- The current batch receiver asks for offer consent and then item selection. Both prompts conflict with the newly accepted session-scoped receive grant.
- AuthenticatedPeerChannelMultiplexer already owns the single TLS reader and serialized socket writer and permits one lease per logical purpose. The new file exchange must preserve this ownership and add one FILE_TRANSFER frame dispatcher; no second socket or competing channel reader is permitted.
- Existing batch protocol frames carry a batch identifier and are a better correlation starting point than the v1 single-file protocol, whose ACCEPT/REJECT/CANCEL control frames are empty and cannot independently correlate concurrent operations. A reviewed protocol/version decision and negative tests are required before wiring simultaneous sessions.
- The existing batch manifest minimum is two items. One-item attachments therefore need an explicit protocol decision; do not silently weaken a versioned manifest constraint or route a single item through an uncorrelated concurrent v1 flow.

The product owner also identified future school/classroom use. CP-CHAT-003 remains strictly one-to-one; it does not implement classes, groups, channels, teacher controls or multi-recipient delivery. Its UX must nevertheless establish these foundations:

- one obvious attachment action and plain-language labels; no sender/receiver mode selection;
- native pickers, no custom gallery or file browser;
- large, clearly labelled touch targets; TalkBack semantics; large-font and RTL/LTR support;
- always-visible peer identity and connection state, with an easy, immediate disconnect action;
- no unsolicited reception outside an explicitly accepted authenticated session;
- no automatic file opening, execution or APK installation;
- concise, actionable error messages rather than protocol jargon;
- no accounts, public Internet, cloud relay or contact-book access for local chat/file exchange.

A future classroom mode needs its own threat model and ADR for teacher-led pairing, class membership, temporary authorization, student privacy, abuse controls and session teardown. It must not be improvised by extending this one-to-one grant.


## Protocol decision and first implementation slice (2026-11-17)

- ADR-0019 defines chat attachment protocol v4 while preserving CP-004's v3 batch protocol. v4 is required for one-item and multi-item attachment batches with complete batch/item correlation; there is no silent fallback to v1/v3.
- Source commit 34f05893b70c27c8b505073dd143229db7b15287 added the first single-reader BatchTransferFrameRouter and routing tests.
- Follow-up hardening commits 8781645a36d8602ed5a19b610d25e9c7657ae201 and 8aae1ca55554e4eb7988ac5664617a5c022f9498 bound the number of active routes and add an inbound-route exhaustion test.
- The initial router implementation currently routes v3 batch frames only. It is a foundational slice, not yet the v4 chat attachment implementation; it must be upgraded and validated against ADR-0019 before any chat UI integration.
- Initial CI run 44032541586 was cancelled by the newer push under the branch's cancel-in-progress concurrency policy. Run 44032993411 was also cancelled by the subsequent push. Exact-head CI for 8aae1ca55554e4eb7988ac5664617a5c022f9498 is run 44033005923 and was queued/in progress at the time of this update. Do not mark the router or checkpoint PASSED until the latest exact-head run completes.
