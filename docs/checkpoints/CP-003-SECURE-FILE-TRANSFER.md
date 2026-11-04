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
- Unit coverage exercises pre-commit receiver state, cumulative ACK release, full-window backpressure, invalid/future/non-boundary ACKs, stale/duplicate ACKs, and protocol window limits. A prior CI test run exposed a JUnit4 non-void test method and a mismatched boxed numeric assertion; both source-level test issues have been corrected. Exact-head CI must complete before tests or build are reported as passed.
- The transfer core remains Android-free. Its ACK and send-window models are not yet connected to a socket data plane or an Android staging writer; therefore they do not yet guarantee durable per-chunk ACKs in a live transfer.
- Commit `aba87f47fff62cca02201c8f43e00f648210efbb` adds receiver commit/ACK separation and sender sliding-window state. Commit `f6d29ffaaa5c84b47808933c8d0be84008cf64a1` adds corresponding tests; commits `09f0dfbaeda21c12313334495af1fe6cb9489668` and `baaaaa5500e2b6d47ba219ddbc9b3a01bde08597` correct the discovered JUnit/test assertion defects.

### 2026-11-04 — CP-003B initial Wi-Fi Direct group-connection slice

- Added the Android-free `PeerConnectionRepository` contract, immutable connection snapshot/status/failure models and pure checks that a selected peer is still in the active discovery snapshot and that Android supplied a usable group-owner endpoint.
- Added `WifiDirectPeerConnectionController` as a focused connectivity component. It reuses the discovery repository's existing `WifiP2pManager`, `Channel` and single registered P2P receiver; it does not create a competing channel or receiver.
- The sender can select a currently visible candidate. Candidates marked with explicitly incompatible TXT metadata cannot be selected. The transient P2P address is used only for the active `connect()` request and is not persisted or presented as a trusted device identity.
- The controller treats the `connect()` ActionListener callback only as request acceptance. It waits for `WIFI_P2P_CONNECTION_CHANGED_ACTION`, then calls `requestConnectionInfo()`; only `groupFormed=true` with a non-empty group-owner address produces `GROUP_FORMED`. Both group-owner and client roles are represented in state.
- Connection attempts have a 30-second timeout. Explicit disconnect, P2P disable, channel loss, permission loss and Activity foreground teardown clear or remove the P2P group as applicable. DNS-SD registrations are stopped only after group formation is confirmed.
- Added a minimal Persian UI action/status display for selecting a sender-side peer and showing connection/receiver-side group state. The UI explicitly states that group formation is not peer authentication and no file has been sent. Automatic rediscovery is suppressed while the connection is connecting, formed or disconnecting.
- Pure domain tests cover current-candidate selection, inactive-scan rejection and group-formation state validation. Platform callback ordering, OEM behavior, timeout behavior and physical group formation have not yet been proven on the target phones.
- Current implementation commits include `1e60b322c03970147bda18e30fe6117c5ed9bf2d` (domain contract), `510d83a98a912034035abd715224a15bad7e7827` (policy tests), `0bc4a654dd8ef937627be979c541c1518b31a328` (dedicated controller), `d4e02fecfa3464d6cfba81eae833c731fe29af79` (repository delegation), and `6961f85d85d01370b9833a8782ea670d5221d6d3` (confirmed-group callback wiring), plus the app/ViewModel/UI commits in this branch.
- Still not implemented: TCP listener/client, explicit network-interface binding for local sockets, Android Keystore ephemeral certificate generation, SAS exchange and user comparison, pinned mutual TLS, receiver consent protocol, ContentResolver source streaming, private receiving staging, SAF publication, authenticated integrity receipt, or any physical end-to-end file transfer.
- No physical group connection has been reported by the owner for this implementation yet. Do not treat a connected Android P2P group as an authenticated transfer session. CP-003 remains `IN_PROGRESS`.
- Source HEAD before this checkpoint edit: `6961f85d85d01370b9833a8782ea670d5221d6d3`. GitHub Actions exact-head validation is pending; no build/test/lint/APK success is claimed here.
- Draft PR: https://github.com/azad2022/shareit/pull/3. It is open, unmerged and intentionally draft.
