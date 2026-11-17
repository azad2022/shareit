# Checkpoint CP-CHAT-003 — Bidirectional In-Chat Attachments

- **ID:** CP-CHAT-003
- **Status:** IN_PROGRESS
- **Opened:** 2026-11-17
- **Product behavior:** Owner-approved 2026-11-17
- **Implementation authorization:** Not granted
- **Design branch:** feature/cp-chat-002-chat-experience
- **Design baseline branch head:** b721b1ff8b4188e4fd729b0a4751c9997f6eac3d
- **Source baseline candidate:** 8e25b56951ef981dcf85ade834edd94881b655a1
- **Prerequisite:** CP-CHAT-002 remains IN_PROGRESS. Exact-head CI run 43623779996 was cancelled; physical two-device chat validation is not recorded.

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

Design only. No Kotlin, Compose, Room migration, transfer protocol, Gradle or runtime files are changed by this checkpoint. Implementation remains unauthorized. Status: IN_PROGRESS.
