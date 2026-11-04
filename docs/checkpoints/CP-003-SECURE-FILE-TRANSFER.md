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
- The controller treats the `connect()` ActionListener callback only as request acceptance. It waits for `WIFI_P2P_CONNECTION_CHANGED_ACTION`, then calls `requestConnectionInfo()`. A formed local Group Owner does not require a usable local owner address; a Group Client must have a non-empty remote Group Owner endpoint before it can open its outbound TCP socket.
- Group formation has a 30-second timeout. Explicit disconnect calls `removeGroup()`, observes a P2P connection-change notification as an additional removal signal, and uses a 10-second watchdog; if removal cannot be confirmed, the UI receives a disconnect-timeout failure and a best-effort second removal is attempted. P2P disable, channel loss, permission loss and Activity foreground teardown have separate cleanup paths. DNS-SD registrations are stopped only after group formation is confirmed.
- Added a minimal Persian UI action/status display for selecting a sender-side peer and showing connection/receiver-side group state. The UI explicitly states that group formation is not peer authentication and no file has been sent. Automatic rediscovery is suppressed while the connection is connecting, formed or disconnecting.
- Pure domain tests cover current-candidate selection, inactive-scan rejection and role-sensitive group-formation validation. The disconnect watchdog and Android callback paths compile in CI but do not yet have dedicated framework-level tests. OEM callback ordering, timeout behavior and repeatable physical connect/disconnect remain to be validated on the target phones.
- Current implementation commits include `1e60b322c03970147bda18e30fe6117c5ed9bf2d` (domain contract), `510d83a98a912034035abd715224a15bad7e7827` (policy tests), `0bc4a654dd8ef937627be979c541c1518b31a328` (dedicated controller), `d4e02fecfa3464d6cfba81eae833c731fe29af79` (repository delegation), and `6961f85d85d01370b9833a8782ea670d5221d6d3` (confirmed-group callback wiring), plus the app/ViewModel/UI commits in this branch.
- Still not implemented: TCP listener/client, explicit network-interface binding for local sockets, Android Keystore ephemeral certificate generation, SAS exchange and user comparison, pinned mutual TLS, receiver consent protocol, ContentResolver source streaming, private receiving staging, SAF publication, authenticated integrity receipt, or any physical end-to-end file transfer.
### 2026-11-04 — Owner-reported group-formation smoke test

- The owner reports that two physical devices discover one another, the app displays the `Wi-Fi Direct group formed` state, and the connection screen exposes a disconnect action.
- This is owner-reported manual smoke-test evidence for discovery and visible group formation only. The report did not include both handset model/API/build details, the installed APK SHA-256, which device became Group Owner in each trial, a trial count, or confirmation that the disconnect action was pressed and the group was actually removed.
- Do not mark repeatable cross-device connection or disconnect cleanup as PASSED from this report alone. Record the exact device/build/role and disconnect result during the next physical validation pass.
- A P2P group remains unauthenticated. No TCP session, pairing, consent, or file bytes are implied by the displayed state. CP-003 remains `IN_PROGRESS`.
- Verified GitHub Actions run `41838844163` succeeded on source HEAD `f519e508792d3d495976b802091f66edddb3b646` (2026-11-04). The workflow completed unit tests, configured lint, and `:app:assembleDebug`; both validation-report and Debug APK artifacts uploaded successfully: https://github.com/azad2022/shareit/actions/runs/41838844163.
- JUnit report audit: 88 tests total, 0 failures, 0 errors, 0 skipped (36 in `:core:domain`, 52 in `:core:transfer`). Configured lint completed with 9 warnings and 0 errors; warnings are existing dependency/API-version/resource/package-visibility notices and are not treated as clean lint.
- Debug APK artifact ID `13327953810`, extracted and archive-validated. APK size: 14,010,025 bytes. SHA-256: `820b5064883b19defa8b2ee525800bf46c9483826ef4bb042af6da702886de89`.
- This CI result validates compilation, unit tests, lint and debug packaging only. It does not validate physical Wi-Fi Direct behavior, TLS, receiver consent, or file transfer. The owner-reported group-formation smoke test above is separate manual evidence and does not constitute full physical acceptance.

- Draft PR: https://github.com/azad2022/shareit/pull/3. It is open, unmerged and intentionally draft.
