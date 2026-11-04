# CP-003 — Secure one-to-one generic file transfer

- **Status:** `IN_PROGRESS`
- **Start date:** 2026-11-04
- **Branch:** `feature/secure-file-transfer`
- **Parent discovery baseline:** `feature/reliable-bidirectional-discovery` at `05c24ea634cfe016a255532da17d13d10ac9482f`
- **ADR:** [ADR-0007 — Secure one-to-one file transfer over Wi-Fi Direct](../decisions/ADR-0007-SECURE-ONE-TO-ONE-FILE-TRANSFER.md)
- **Objective:** Implement and physically verify one secure one-to-one transfer of one user-selected file of any format, with receiver consent and end-to-end integrity verification. Preserve the existing discovery behavior.

## Scope

- Connect to a selected discovered peer using Wi-Fi Direct.
- Confirm connection formation and group-owner/client role through Android framework state.
- Establish a local TCP session and a session-scoped authenticated pairing step.
- Protect the data path with mutually authenticated TLS.
- Exchange a bounded, versioned manifest/control protocol.
- Let the receiver explicitly accept or reject before any file bytes are sent.
- Stream one arbitrary file from a user-authorized content:// URI using bounded memory and a capped four-chunk cumulative-ack window (256 KiB maximum outstanding data).
- Stage received bytes privately, verify exact length and SHA-256, then publish through SAF.
- Expose truthful connection, pairing, consent, transfer, verification, cancellation, and failure state.

## Acceptance criteria

1. Existing two-way DNS-SD discovery and foreground retry behavior remain unchanged.
2. Only a current discovery candidate may be selected; its transient P2P address is not persisted or treated as identity.
3. Connection formation is confirmed by Android framework state and supports either device becoming group owner.
4. Both devices derive and display the same session SAS; no file metadata or data is transmitted before both approvals and mutual TLS authentication.
5. Receiver rejection, timeout, or lack of consent results in zero file bytes transmitted.
6. Images, video, audio, PDF, archives, extensionless, and unknown-MIME documents transfer byte-for-byte without format-specific parsing.
7. Sender and receiver compute SHA-256 in the streaming path. The final sender record carries actual byte count and digest; receiver checks both against its own result and the optional declared size, publishes only after verification, and returns an authenticated receipt. Success is shown only after that receipt.
8. Memory remains bounded independent of file size; neither side materializes the complete file in RAM. Receiver acknowledgements are cumulative and contiguous; no more than four 64 KiB chunks may remain unacknowledged.
9. Malformed frames, SAS/pin mismatch, short reads/writes, source changes, low storage, destination failures, disconnect, and cancellation fail safely and do not leave a published partial file.
10. No public internet, backend, account, cloud, relay, fallback transport, or broad storage access is introduced.
11. Unit tests, lint, and exact-head Debug assembly pass. Physical tests pass repeatedly on Galaxy A12 (SM-A125F) and ASUS X017DA with exact APK SHA-256 and OS/API/build recorded.

## Implementation stages

- **CP-003A — Protocol and streaming core:** typed bounded frames, contiguous chunk ordering, cumulative acknowledgements/backpressure, state validation, transcript/SAS policy, bounded streaming, SHA-256 verification, URI source/destination boundaries, and pure unit tests.
- **CP-003B — Wi-Fi Direct connection and session authentication:** connection-state lifecycle, group-owner/client socket path, ephemeral Keystore identity, SAS confirmation, and pinned mutual TLS.
- **CP-003C — First end-to-end file transfer:** manifest exchange, receiver consent, streaming, staging/publication, integrity acknowledgement, minimal functional state UI, and physical-device tests.

## Deferred

Multiple files per batch, one-sender-to-many group transfer, independent recipient states, resumable transfer, background execution, transfer history, APK/split-APK extraction and installation, fallback transports, and full visual redesign are not CP-003 acceptance criteria. They require later checkpoints.

## Evidence log

- Checkpoint created from discovery owner-update commit `05c24ea634cfe016a255532da17d13d10ac9482f`.
- No CP-003 implementation, build, or test result is claimed at checkpoint creation.
- Record each implementation commit, exact-head CI, test command/output, Debug APK SHA-256, and physical device/API evidence here.
- Green CI cannot substitute for physical Wi-Fi Direct, TLS, storage-provider, and file-integrity validation.

## Status rule

Keep `IN_PROGRESS` until all acceptance criteria have reproducible evidence. Use `BLOCKED` if a mandatory dependency or device limitation prevents completion. Mark `PASSED` only when the full evidence package is repeatable.

### 2026-11-04 — CP-003A Android-free transfer core added

- Source files include `TransferFrame`, `TransferChunkFlowControl`, `TransferCompletionCodec`, `TransferManifest`, `StreamingTransferEngine`, `PairingShortAuthenticationString`, `TransferIntegrityVerifier`, and `TransferStateMachine`, with corresponding unit tests.
- Frames have a fixed 26-byte header and strict control/data size limits. Manifest supports unknown provider length and unknown MIME. Source and receiver digest are computed inline; receiver compares final byte count and SHA-256 and must issue a receipt after successful publication.
- The receiver-side chunk validator now separates side-effect-free validation from offset advancement. The caller must write the complete chunk to staging and perform the destination's required durability operation before calling `commit()` and sending the cumulative ACK. This is an API contract; it is not yet wired to an Android staging implementation.
- The sender now has a bounded sliding-window state model: it reserves only contiguous chunks, caps both outstanding chunk count and bytes (four / 256 KiB maximum), pauses reservations when full, and releases capacity only on valid cumulative ACKs at previously sent chunk boundaries. Duplicate/stale ACKs do not release capacity twice. This policy is not yet integrated with a socket/data-plane loop.
- Unit coverage now exercises pre-commit receiver state, cumulative ACK release, full-window backpressure, invalid/future/non-boundary ACKs, stale/duplicate ACKs, and protocol window limits. These tests are authored but remain pending exact-head CI execution.
- The core remains Android-free. No Android Keystore certificate generator, mTLS socket, Wi-Fi Direct `connect()` flow, TCP listener/client, ContentResolver transfer stream, staging/publication adapter, transfer UI, or physical end-to-end transfer exists yet.
- Commit `aba87f47fff62cca02201c8f43e00f648210efbb` adds the receiver commit/ACK separation and sender sliding-window model. Commit `f6d29ffaaa5c84b47808933c8d0be84008cf64a1` adds the corresponding unit tests.
- Earlier GitHub Actions run `41833007190` targeted `04b0634a70b7bcfa07d4d8134bc160f67524dd8c`; its build/test/lint step was cancelled and the Debug APK upload was skipped. It is not a passing CI result and does not validate the later flow-control commits.
- The exact-head CI for `f6d29ffaaa5c84b47808933c8d0be84008cf64a1` is not yet verified in this checkpoint. Do not report tests, lint, or Debug assembly as passed until the run is confirmed.
- Draft PR: https://github.com/azad2022/shareit/pull/3. It is open, unmerged and intentionally draft.
