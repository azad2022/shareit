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
- Pure domain tests cover current-candidate selection, inactive-scan rejection, role-sensitive group formation, and the policy that a disconnect timeout remains unresolved until Android later confirms group removal. The Handler watchdog, removeGroup callbacks and Android broadcast ordering do not yet have framework-level tests. OEM callback ordering and repeatable physical connect/disconnect remain to be validated on the target phones.
- Current implementation commits include `1e60b322c03970147bda18e30fe6117c5ed9bf2d` (domain contract), `510d83a98a912034035abd715224a15bad7e7827` (policy tests), `0bc4a654dd8ef937627be979c541c1518b31a328` (dedicated controller), `d4e02fecfa3464d6cfba81eae833c731fe29af79` (repository delegation), and `6961f85d85d01370b9833a8782ea670d5221d6d3` (confirmed-group callback wiring), plus the app/ViewModel/UI commits in this branch.
- The initial TCP listener/client channel is now implemented in `:core:connectivity`: it resolves the active P2P group interface from `requestGroupInfo()`, selects a usable private IPv4 address only from that interface, binds the Group Owner listener to that exact local address, and binds the Group Client source address before connecting to the framework-provided owner address. It rejects peers outside the P2P subnet, applies bounded accept/connect timeouts and retry, closes sockets on group teardown, and exposes only address-free transport state to the UI.
- The TCP channel is raw and unauthenticated. No TLS handshake or application frame is exchanged at this stage. Still not implemented: Android Keystore ephemeral identity, SAS exchange and user comparison, pinned mutual TLS, receiver consent protocol, ContentResolver source streaming, private receiving staging, SAF publication, authenticated integrity receipt, or physical end-to-end file transfer.
### 2026-11-04 — Owner-reported group-formation smoke test

- The owner reports that two physical devices discover one another, the app displays the `Wi-Fi Direct group formed` state, and the connection screen exposes a disconnect action.
- This is owner-reported manual smoke-test evidence for discovery and visible group formation only. The report did not include both handset model/API/build details, the installed APK SHA-256, which device became Group Owner in each trial, a trial count, or confirmation that the disconnect action was pressed and the group was actually removed.
- Do not mark repeatable cross-device connection or disconnect cleanup as PASSED from this report alone. Record the exact device/build/role and disconnect result during the next physical validation pass.
- A P2P group remains unauthenticated. The current TCP channel establishes only a byte stream and does not exchange application data. Pairing, consent, mTLS and file transfer are not implied by this state. CP-003 remains `IN_PROGRESS`.
### 2026-11-04 — CP-003B P2P-interface-bound raw TCP channel

- Added `WifiDirectTcpChannelController` as an app-lifetime-owned adapter. It reuses the discovery repository's existing `WifiP2pManager` and `Channel`; it does not initialize a second P2P channel or receiver.
- The controller requests the current `WifiP2pGroup`, uses `getInterface()` to identify the exact P2P interface, and resolves the local address only from that interface. It requires an up interface and a usable RFC1918 IPv4 unicast address with a valid prefix length. No wildcard, loopback, link-local, multicast, public or unrelated Wi-Fi/VPN interface is used.
- The Group Owner binds one `ServerSocket` to its exact local P2P IPv4 address on protocol port `49273`; backlog is one. The Group Client binds its source socket to its own local P2P IPv4 address and connects only to the remote Group Owner address returned by `requestConnectionInfo()`. Both addresses must share the same P2P subnet.
- Listener acceptance is bounded to 20 seconds; client connection has a 15-second overall deadline, 1.2-second attempts and a bounded retry delay. Group-info resolution is limited to 5 seconds. The server rejects up to three clients whose source address is outside the P2P subnet. All sockets close on group teardown, permission/lifecycle loss, cancellation, replacement or failure.
- UI state is limited to interface resolution, owner listening, client connecting, raw TCP connected and typed failures. Interface names and IP addresses are not copied to UI state or logs. The connected raw socket remains private to the connectivity/security boundary for the next TLS stage.
- Added pure unit coverage for private IPv4 selection, excluded address/prefix classes and same-subnet enforcement. These tests do not simulate Android Wi-Fi Direct routing or OEM behavior.
- Added `INTERNET` only for local socket APIs. Target SDK remains 36; `ACCESS_LOCAL_NETWORK` is not declared or requested. Android 16 opt-in local-network restrictions and Android 17/target-37 enforcement remain explicit compatibility gates.
- No TCP payload, protocol frame, file name, MIME type, size or file byte is sent. A raw TCP connection is not peer authentication.

- Verified GitHub Actions run `41838844163` succeeded on source HEAD `f519e508792d3d495976b802091f66edddb3b646` (2026-11-04). The workflow completed unit tests, configured lint, and `:app:assembleDebug`; both validation-report and Debug APK artifacts uploaded successfully: https://github.com/azad2022/shareit/actions/runs/41838844163.
- JUnit report audit: 88 tests total, 0 failures, 0 errors, 0 skipped (36 in `:core:domain`, 52 in `:core:transfer`). Configured lint completed with 9 warnings and 0 errors; warnings are existing dependency/API-version/resource/package-visibility notices and are not treated as clean lint.
- Debug APK artifact ID `13327953810`, extracted and archive-validated. APK size: 14,010,025 bytes. SHA-256: `820b5064883b19defa8b2ee525800bf46c9483826ef4bb042af6da702886de89`.
- This CI result validates compilation, unit tests, lint and debug packaging only. It does not validate physical Wi-Fi Direct behavior, TLS, receiver consent, or file transfer. The owner-reported group-formation smoke test above is separate manual evidence and does not constitute full physical acceptance.

### 2026-11-04 — CP-003B P2P-scoped raw TCP channel

- Added `WifiDirectTcpChannelController` as an app-lifetime-owned adapter component. It reuses the discovery repository's existing `WifiP2pManager` and `Channel`; it does not create another P2P channel or receiver.
- The controller requests the current `WifiP2pGroup`, uses `getInterface()` to identify the exact P2P interface, and resolves the local address only from that `NetworkInterface`. It requires an up interface and a usable RFC1918 IPv4 unicast address with a valid subnet prefix. No wildcard, loopback, link-local, public, multicast or unrelated Wi-Fi/VPN interface is used.
- The Group Owner binds a single-listener `ServerSocket` to its exact local P2P IPv4 address on protocol port 49273. The Group Client binds its source socket to its own P2P IPv4 address and connects only to the remote owner address from `requestConnectionInfo()`; both endpoints must be in the same P2P subnet.
- Listener acceptance is bounded to 20 seconds; client connection has a 15-second overall deadline, 1.2-second attempts and bounded retry. Group-info resolution is limited to 5 seconds. The server rejects up to three clients whose source address is outside the P2P subnet. Sockets are closed immediately on group teardown, permission loss, lifecycle teardown, cancellation or replacement of the P2P group.
- UI state is limited to interface resolution, owner listening, client connecting, raw TCP connected and typed failures. Interface names and IP addresses are not copied to UI state or logs. The raw socket remains private to the connectivity layer for the next TLS/session-authentication stage.
- Added pure unit coverage for private IPv4 selection, excluded address ranges/prefixes and same-subnet enforcement. These tests do not simulate Android Wi-Fi Direct routing or OEM behavior.
- Added `INTERNET` only for local socket APIs. The app still targets SDK 36; `ACCESS_LOCAL_NETWORK` is not declared or requested. Android 16 opt-in local-network restrictions and Android 17/target-37 enforcement remain explicit compatibility tests.
- No TCP payload, protocol frame, file name, MIME type, file size or file byte is sent. A raw TCP connection is not peer authentication.

- Draft PR: https://github.com/azad2022/shareit/pull/3. It is open, unmerged and intentionally draft.


### 2026-11-04 — CP-003B session pairing and mutual TLS implementation

- Source implementation was added on the active branch in commits beginning with `90ab385bed876c03d13718c70e887594c6df0898` and consolidated through `848fb054d3e55de7fc797db5dcf318c9a32f29a5`.
- The incomplete domain contract introduced in `90ab385` was followed by a concrete `PeerSecureSessionRepository` implementation; the initial commit's CI failure was due to the missing implementation and is not treated as passing evidence.
- `WifiDirectSecureSessionController` now waits for the P2P TCP channel, assigns the application role from the connection initiator, and exchanges a bounded bootstrap containing a session UUID, role, 32-byte nonce and ephemeral X.509 certificate. The bootstrap carries no file name, MIME type, size or file bytes.
- Each endpoint generates an ephemeral ECDSA P-256 key in Android Keystore. Peer certificates are parsed, validity-checked, constrained to EC P-256 and checked for self-signature. Session aliases are removed on teardown and up to 32 stale aliases are cleaned on controller startup.
- Both endpoints derive the SAS from the same session-bound transcript (certificate fingerprints, nonces, participant roles and protocol version). Local approval is explicit on each device. Approval/rejection is exchanged before TLS; rejection and approval timeout attempt to send a zero-payload session-close frame.
- The socket is upgraded to mutually authenticated TLS, preferring TLS 1.3 and requiring TLS 1.2 or newer. Each side pins the exact peer certificate fingerprint from the bootstrap; default permissive trust and certificate-validation bypasses are not used.
- Persian UI now exposes the SAS and approve/reject actions and distinguishes bootstrapping, waiting for approval, TLS handshake, authenticated, rejected and failed. The authenticated message explicitly states that file transfer has not started.
- Source implementation commit: `848fb054d3e55de7fc797db5dcf318c9a32f29a5`; the branch's documentation checkpoint commit at the time of the validation run was `d25028b4a21e0f06f91bd02d1c9b56935d03e07c`.
- GitHub Actions run `41896531270` completed successfully on the exact branch-head SHA `d25028b4a21e0f06f91bd02d1c9b56935d03e07c`: ` :core:domain:testDebugUnitTest`, `:core:transfer:testDebugUnitTest`, configured lint for app and all core modules, and `:app:assembleDebug` all completed successfully.
- JUnit report audit: 98 tests total (42 in `:core:domain`, 56 in `:core:transfer`), 0 failures, 0 errors, 0 skipped. These are JVM unit tests; the session controller's Android Keystore/TLS orchestration has no dedicated instrumented/device test in this run.
- Lint report audit: 10 warnings, 0 errors. One warning is `CustomX509TrustManager` in `:core:connectivity`, which requires focused security review of the pinning trust manager before treating the TLS layer as security-accepted. Other warnings: 7 in app, 1 in designsystem, 1 in storage.
- Debug APK artifact `13355498653` from run `41896531270`: 14,109,243 bytes; extracted APK SHA-256 `c2ab089c117d00fd80c5261783718ca62217dfb883d72ae7d07389730cddfd93`. The artifact ZIP and extracted APK both passed ZIP integrity checks.
- This CI confirms compilation, existing JVM unit tests, configured lint completion and Debug packaging only. It does not validate Android Keystore key generation, identical SAS on two physical phones, TLS provider interoperability, rejection/timeout behavior, live P2P socket upgrade, receiver consent or file transfer.
- No physical-device pairing/TLS test has yet been performed. Required next test: install the same exact-head Debug APK on both physical devices, verify identical SAS, approve on both, verify authenticated state, then separately test mismatch/rejection, timeout and disconnect cleanup.
- This substage does not implement receiver consent to a particular file manifest, file metadata exchange, file streaming, staging, SAF publication or authenticated integrity receipt. No file can yet be transferred.
- CP-003 remains `IN_PROGRESS`; CP-003B is not marked `PASSED`.


### 2026-11-07 — CP-003C one-to-one transfer runtime integrated

This entry supersedes the earlier CP-003B notes above that stated no file data plane or SAF publication existed. Those notes remain as historical snapshots of the state at the time they were written.

- Connected the authenticated TLS channel to the transfer coordinator through the exclusive `claimAuthenticatedChannel()` contract. The coordinator claims the session once, owns the channel for the operation, and closes the channel/P2P group on completion, failure, cancellation or leaving the route.
- Added the live single-item control/data path: bounded manifest exchange, receiver-specific accept/reject, bounded binary chunks, contiguous offsets, four-chunk/256 KiB sender window, cumulative receiver ACKs, and progress based on receiver-committed bytes.
- Receiver staging is app-private under `noBackupFilesDir`. Up to four 64 KiB chunks (at most 256 KiB) are written into the private stage before one `FileDescriptor.sync()`; only after that sync succeeds does the receiver advance the committed offset and emit one cumulative ACK. A final partial window is synced and acknowledged when the sender's completion frame arrives. Thus no more than four chunks remain unacknowledged and no ACK covers data that has not passed the staging sync. The receiver calculates SHA-256 while receiving and checks actual length/digest against the sender's authenticated completion record before publication.
- Source files are read from user-selected `content://` URIs through `ContentResolver`; no sender path is transmitted or trusted. The first runtime slice deliberately rejects non-URI sources, including installed-app package objects.
- After successful staging verification, the receiver launches the SAF `ACTION_CREATE_DOCUMENT` flow. The file is copied with a bounded buffer, hashed during copy, then reopened and read back to verify byte count and SHA-256. The sender only reaches `COMPLETED` after receiving and validating the receiver's authenticated `ITEM_VERIFIED` receipt.
- Added explicit `VERIFYING` state on both sides so a sender waiting for the receiver's receipt is not shown as still transferring at 100%. Receiver consent, transfer, verification, destination selection, publishing, completion, rejection, cancellation and failure have separate UI states.
- The app now directly depends on `:core:transfer`; the activity lifecycle uses the coordinator's `TransferSessionPhase` type and avoids stopping discovery while the SAF destination picker temporarily backgrounds the activity.
- Hardened SAF publication loops against non-progressing zero-length reads and made the bounded copy/read-back loops check coroutine cancellation. A failed private-stage deletion after successful verified publication no longer converts a valid destination into a false transfer failure. On normal publication errors, the adapter tries `DocumentsContract.deleteDocument()` and then `ContentResolver.delete()`; if the provider does not confirm removal, the transfer fails instead of reporting success. SAF providers do not guarantee identical atomic-delete/rename behavior, so the UI tells the user to remove a leftover incomplete destination file if one remains.
- A stale private stage that cannot be deleted immediately remains inside app-private storage and is subject to the 24-hour cleanup pass on a later storage operation. No resume guarantee is made.
- Added `SingleFileTransferCoordinatorTest`, a JVM end-to-end orchestration test using paired full-duplex streams and fake storage. It exercises channel claiming, manifest/consent, a 900,123-byte multi-window binary payload, receiver staging, integrity-checked publication, sender verification wait and final receipt. It is not a physical Wi-Fi Direct/TLS test and does not substitute for SAF provider tests.
- Existing `SingleFileTransferProtocolTest` covers arbitrary binary bytes, receiver rejection before source bytes are read, and zero-byte files. Codec/state/stream/integrity tests remain in `:core:transfer`.

#### Validation and remaining acceptance

- CI run `42389254385` on source SHA `3694357040c555e290bba382df10832d751536ff` failed during `:app:compileDebugKotlin`: the app had not declared its direct dependency on `:core:transfer`, so transfer types were unresolved. The two Keystore mTLS emulator jobs (API 26 and API 36) succeeded in that run, but the app build did not; no APK was produced by that failed run.
- The dependency and activity-state references were corrected. Additional runtime integration, storage and test changes followed. Exact-head CI for the implementation with the coordinator integration test is being run; do not treat the earlier failed run or any superseded/cancelled run as passing evidence.
- Owner-reported physical-device evidence currently confirms discovery, matching SAS and authenticated TLS on the ASUS device and Samsung Galaxy A12 (SM-A125F). The owner has not yet reported a real file sent through this CP-003C code path. Therefore no physical file-transfer, SAF-provider, large-file, cancellation or integrity-mismatch test is marked as passed.
- CP-003 remains `IN_PROGRESS`. Required before CP-003 can be `PASSED`: exact-head CI (unit tests, lint, Debug assembly and mTLS instrumentation), a downloadable exact-head APK with SHA-256, and repeated physical tests of one file in both directions on Galaxy A12 and ASUS. Include small/large, unknown MIME, zero-byte, rejection, cancel, destination failure and hash verification cases. Record exact Android builds and APK digest.
- Deferred remains unchanged: multi-file batches, sender-to-many, per-recipient group state, resume, background transfer guarantees, APK/split-APK extraction and installation, discovery performance tuning and final visual redesign.


### 2026-11-07 — Durable cumulative ACK batching and verification-state correction

- Replaced the per-64 KiB `fsync`/ACK pattern with a bounded durable window: at most four chunks/256 KiB are written, `TransferStagingSink.sync()` is called once for the batch, the chunk validator then commits each contiguous chunk, and one cumulative ACK is sent for the window's final offset.
- The sender transmits its final authenticated completion record before waiting for the last partial-window ACK. This allows the receiver to flush and acknowledge a tail of one to three chunks without deadlock. Exact multiples of four chunks are acknowledged by the regular full-window path; zero-byte files need no data ACK.
- The coordinator integration test now asserts the 900,123-byte transfer uses four bounded batch syncs plus the final stage seal sync, instead of syncing every one of its fourteen data chunks.
- Progress callbacks preserve `VERIFYING` while final ACKs are drained; they cannot regress the UI to `TRANSFERRING` after the sender has finished reading its source or after the receiver has begun validating/publishing.
- The four-chunk window is a protocol cap, not a throughput guarantee. Physical performance must be measured on the target phones.


### 2026-11-07 — Receive-window shape hardening

- Receiver now rejects any data chunk shorter than 64 KiB if a later data chunk follows it. For CP-003, only the terminal chunk may be short; zero-byte files have no data chunks. The official sender fills each non-final chunk before sending, so this does not reject compliant streams.
- This prevents a peer with a valid authenticated session from splitting a large payload into many tiny chunks and forcing excessive staging syncs/ACK operations. The receiver continues to enforce item identity, contiguous offsets, maximum file size and four-chunk in-flight count.
- Added a JVM regression test that sends a malformed one-byte chunk followed by another data chunk, and asserts the receiver fails closed, deletes staging, and has not issued a durability sync/ACK for that malformed window.
- Protocol detail is recorded in ADR-0007. CP-003 remains `IN_PROGRESS` pending exact-head CI and physical file-transfer validation.


### 2026-11-07 — Source descriptor lifecycle

- Sender now passes a source-stream factory into the protocol instead of opening the URI stream before sending the manifest. The actual `ContentResolver.openInputStream()` call happens only after an authenticated receiver `ACCEPT`.
- Rejection, consent timeout or cancellation before acceptance therefore leaves the file content stream unopened and avoids holding a provider descriptor while the receiver considers the manifest.
- The protocol rejection test now asserts both zero source-open calls and zero source reads when the receiver rejects the manifest.


### 2026-11-07 — Cancellation closes active source I/O

- `SingleFileTransferCoordinator` retains the currently active source stream while the sender reads it. On cancel, leaving the transfer screen, or coordinator shutdown, it closes that source stream before closing the TLS channel and cancelling the job.
- This is required because coroutine cancellation alone does not guarantee that a blocking platform `InputStream.read()` is interrupted.
- `ContentUriTransferFileSource` now wraps the provider stream so closure clears its stored reference; closing it from the cancellation path and later disposing the source handle does not close the same provider stream twice.
- Added a JVM coordinator test with a deliberately blocking source stream. It waits until the source is opened, cancels the transfer, and asserts the source stream and authenticated channel are closed and the transfer state is `CANCELLED`.


### 2026-11-07 — Owner-confirmed real file-transfer smoke acceptance

- **Product-owner report:** on 2026-11-07, the owner explicitly confirmed that file transfer in the app was successful. This is recorded as owner-reported physical functional smoke evidence and supersedes earlier entries saying that no real file transfer had yet been reported.
- **Substage result:** the basic CP-003C one-file transfer path is accepted by the product owner for functional operation: `PASSED` (owner-reported smoke acceptance).
- **Evidence boundary:** the confirmation did not include a test log identifying handset model, Android/API/build, APK SHA-256 installed on each phone, sender/receiver direction, file name/type/size, destination provider, number of repetitions, or the exact test cases performed. Do not infer these details or claim they were covered.
- **Exact-head CI:** GitHub Actions run [42392698691](https://github.com/azad2022/shareit/actions/runs/42392698691) completed with `success` on source SHA `0bb8d6582d860a45ded777ac9432d617c2106f55`. All three jobs succeeded:
  - Build, unit tests, and lint — job `128463492116`.
  - Android Keystore mutual-TLS loopback — API 26, job `128463492015`.
  - Android Keystore mutual-TLS loopback — API 36, job `128463492195`.
- **Debug APK:** workflow artifact `13581694883` (`peyvand-debug-apk`); ZIP artifact size 13,387,717 bytes; extracted `app-debug.apk` size 14,221,658 bytes. Extracted APK SHA-256: `bd279275f25554a374e04398d1aa9aab38f0b1f7d041653b10605769505661d0`. Artifact ZIP digest: `sha256:d7823624ae18034e132eeade96f3fd34d1b27e1983294675d919dd575a67e704`. The APK archive was validated after extraction.
- **Overall checkpoint status remains `IN_PROGRESS`:** acceptance criterion 11 requires repeatable physical tests on both Samsung Galaxy A12 (SM-A125F) and ASUS X017DA, with exact OS/API/build and installed APK digest recorded. The owner's confirmation proves a successful functional smoke test, but the data needed to establish that full repeatable device matrix is not present in the report. Rejection, cancellation, zero-byte, large-file, unknown-MIME, SAF-provider failure/cleanup, source-permission revocation, and integrity-mismatch cases also remain without recorded physical evidence.
- **No scope expansion is implied:** CP-003 still covers one sender, one receiver, one file per authenticated session. Multi-file queues, group transfer, resume/background guarantees, APK/split-APK transfer and final visual redesign remain separate checkpoints.

