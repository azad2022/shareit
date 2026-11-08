# CP-004 — Multi-item one-to-one transfer

- **Status:** IN_PROGRESS
- **Branch:** `feature/multi-item-transfer`
- **Base:** `feature/secure-file-transfer` at `7237115e1ce04ab225cbad223fad77e9ed8d344f`
- **Scope:** Multiple arbitrary user-selected SAF/ContentResolver files from one sender to one authenticated receiver in one ordered batch.
- **Out of scope:** sender-to-many, multi-sender, APK/split APK, durable resume, guaranteed background continuation, final product-wide redesign.

## Goal

Add a bounded, ordered, multi-item transfer operation with batch-level and per-item state, receiver consent, independent integrity receipts and item failure isolation, while preserving the CP-003 single-file path.

## Acceptance criteria

1. Multiple user-selected `content://` URIs with mixed MIME types are accepted in a stable selection order.
2. Batch and item IDs are random, unique, validated and bound to the authenticated session and all relevant protocol messages.
3. Batch offer reveals only aggregate count/known aggregate size before batch consent; no filenames, MIME metadata, URI, paths or bytes are disclosed before that consent.
4. Each item manifest is disclosed only after batch acceptance; the receiver independently accepts/rejects each item.
5. Sender opens no source descriptor and sends no bytes for an item before its matching item-level ACCEPT.
6. At most one source descriptor, active item and bounded data window are owned at a time.
7. Queue order is deterministic; item-local rejection/failure does not corrupt other item state; channel/protocol desynchronization terminates the batch safely.
8. Every item has independent real ACK-based progress, terminal status, staging, SHA-256 verification, SAF publication/read-back and authenticated receipt.
9. Zero-byte, large, unknown-size, Unicode/Persian and extensionless filenames are handled without whole-file RAM buffering.
10. Cancellation closes blocking source I/O, channel, descriptors and staging resources; temporary artifacts are cleaned or explicitly reported when provider semantics prevent confirmed deletion.
11. Existing CP-003 one-file transfer tests and physical behavior are not regressed.
12. Unit/integration tests, configured lint and Debug build pass on the exact code SHA.
13. Physical device tests record handset model, Android/API/build, exact APK SHA, direction, source/destination provider and repeatable case results.

## Initial architecture decision

See [ADR-0008](../decisions/ADR-0008-MULTI-ITEM-ONE-TO-ONE-BATCH-TRANSFER.md).

- `DESTINATION_READY(batchId)` synchronizes the batch-accepted and SAF-destination-ready boundary; no item metadata is sent before this signal.
- Dedicated batch coordinator; retain `SingleFileTransferCoordinator` as the one-item path.
- Sequential item processing, stable selected order, one active item/source descriptor.
- Two consent boundaries: aggregate batch acceptance before item metadata; independent per-item acceptance before opening its URI stream.
- New explicitly negotiated/versioned batch protocol with batchId/itemId binding and per-item authenticated integrity receipts.
- Continue after a synchronized item-local rejection/failure; abort the batch on channel loss, malformed protocol, invalid receipt/ACK, whole-batch cancellation or uncertain stream boundary.
- In-memory queue only for CP-004; durable recovery is CP-007.

## Checkpoint log

### 2026-11-07 — Initial repository audit and design

- Verified branch head `feature/secure-file-transfer` = `7237115e1ce04ab225cbad223fad77e9ed8d344f`.
- Verified CP-003 validated code SHA `0bb8d6582d860a45ded777ac9432d617c2106f55` and exact-head CI run [42392698691](https://github.com/azad2022/shareit/actions/runs/42392698691) with all three jobs successful.
- Verified PR #3 remains open, Draft and unmerged. It is not modified or merged.
- Verified current architecture has `SingleFileTransferCoordinator`, `SingleFileTransferProtocol`, one-item manifest/frame codecs and a single-item state machine. Coordinator rejects any sender selection not containing exactly one item.
- Created CP-004 work branch from the exact current documentation head; no changes were made to the CP-003 branch.
- No CP-004 application code, build or test has been executed at this point.

### 2026-11-08 — CI failure diagnosis, race fix and regression hardening

- Latest source/test commit at validation: `bedef4039a3fe2080a8a7e0aeb1059d0275fc5da`.
- CI run [#311](https://github.com/azad2022/shareit/actions/runs/42447599866) exposed Kotlin compilation failures in the batch payload codecs (missing `UUID` import, batch minimum constant and counter-bound typing). These were corrected in the intervening batch codec commits through `86aaaa11582ccfa91e6afb0c972432225517896f`.
- CI run [#312](https://github.com/azad2022/shareit/actions/runs/42448200150) then exposed an intermittent timeout in `SingleFileTransferCoordinatorTest.authenticatedSenderAndReceiverCompleteOneFileOnlyAfterConsentAndPublication`.
- Source inspection identified a real StateFlow/Deferred ordering race: `WAITING_FOR_CONSENT` and `AWAITING_DESTINATION` were published before their corresponding `CompletableDeferred` decision handlers were registered. A UI observer responding immediately to either state could submit its decision while the handler was still null; the decision was lost and the transfer could stall.
- Fixed `SingleFileTransferCoordinator` so the consent and destination Deferred references are assigned before publishing the states that enable those UI actions. Both references are `@Volatile` for cross-thread visibility. Production-code fix is in `9d27fcdfa6f48a54f406d02832d0ba0adce3f26c`.
- Hardened the existing coordinator regression test in `bedef4039a3fe2080a8a7e0aeb1059d0275fc5da`: `Dispatchers.Unconfined` StateFlow observers issue consent and destination responses inline with state publication. This forces the race-sensitive timing instead of depending on ordinary scheduler interleaving.
- CI run [#314](https://github.com/azad2022/shareit/actions/runs/42448815933) passed all three jobs on the production-code fix SHA. CI run [#315](https://github.com/azad2022/shareit/actions/runs/42449433525) passed all three jobs on the latest source/test SHA `bedef4039a3fe2080a8a7e0aeb1059d0275fc5da`.
- The final run's main job passed unit tests, configured lint tasks and Debug APK assembly. Domain: 65 tests, 0 failures/errors/skips. Transfer: 89 tests, 0 failures/errors/skips. The coordinator suite's two tests, including the immediate-decision regression test, both passed.
- Android Keystore mTLS loopback instrumentation: API 26 ran 4 tests (3 passed; TLS 1.3 capability test skipped by the API 26 provider; 0 failures); API 36 ran 4 tests (4 passed; 0 failures/skips).
- Latest Debug APK from run #315 was independently checksum-verified: `bcd7a76abb907f362a95a9b0dfd2233c49a0e358203185079ffb2a9d137c2a00`. Debug build only; no release keystore or release artifact was created.
- Lint completed with 0 errors but is not warning-free: 13 warnings across the configured module reports. They include the reviewed certificate-pinning `X509TrustManager` rule, the library-level package-visibility warning (the consuming app manifest already declares the narrow launcher `<queries>`), a Lifecycle lint-registry compatibility warning that skips two checks, target-SDK/dependency-version notices, and small unused/empty-super/attribute findings. These are tracked as cleanup/compatibility work; they were not hidden by disabling lint or promoting a false green result.
- The custom TLS trust manager was reviewed rather than replaced with a permissive or unsuitable PKIX fallback: PEYVAND uses short-lived self-signed session identities, and the manager requires a single certificate, validity, self-signature and exact SHA-256 pin equality. The SAS derived from both peer identities must be approved on both devices before TLS; tests cover successful mutual TLS and rejection of an incorrect pin. Keep this control; do not disable certificate validation to silence lint.

## Validation record

| Check | Command / evidence | Result |
|---|---|---|
| Branch base | GitHub branch ref API | Verified at `7237115e1ce04ab225cbad223fad77e9ed8d344f` |
| CP-003 CI | [Workflow 42392698691](https://github.com/azad2022/shareit/actions/runs/42392698691) | Success; prior checkpoint evidence |
| CP-004 Domain unit tests | `:core:domain:testDebugUnitTest`; [CI #315](https://github.com/azad2022/shareit/actions/runs/42449433525), SHA `bedef4039a3fe2080a8a7e0aeb1059d0275fc5da` | 65 passed; 0 failures/errors/skips |
| CP-004 Transfer unit tests | `:core:transfer:testDebugUnitTest`; [CI #315](https://github.com/azad2022/shareit/actions/runs/42449433525), SHA `bedef4039a3fe2080a8a7e0aeb1059d0275fc5da` | 89 passed; 0 failures/errors/skips; race regression and cancellation tests passed |
| Configured lint | `:app:lintDebug`, `:core:data:lintDebug`, `:core:designsystem:lintDebug`, `:core:domain:lintDebug`, `:core:connectivity:lintDebug`, `:core:storage:lintDebug`, `:core:transfer:lintDebug`; CI #315 | All tasks succeeded; 0 errors, 13 warnings. Warning debt is recorded above; not warning-free |
| Debug APK | `:app:assembleDebug`; CI #315 artifact | Success; SHA-256 `bcd7a76abb907f362a95a9b0dfd2233c49a0e358203185079ffb2a9d137c2a00` |
| mTLS API 26 | `:core:connectivity:connectedDebugAndroidTest`; CI #315 artifact | 3 passed, 1 TLS 1.3 capability test skipped by provider, 0 failures |
| mTLS API 36 | `:core:connectivity:connectedDebugAndroidTest`; CI #315 artifact | 4 passed, 0 failures/skips |
| End-to-end CP-004 batch transfer | Batch coordinator and Android storage/publishing path not yet implemented | Pending |
| Physical CP-004 device tests | Real Wi-Fi Direct hardware and SAF/provider matrix | Pending |

## Device matrix

| Device | Android/API | Installed APK SHA-256 | Direction | Result |
|---|---|---|---|---|
| Samsung Galaxy A12 (SM-A125F) | Not recorded for CP-004 | Not tested | Both | Pending |
| ASUS X017DA | Not recorded for CP-004 | Not tested | Both | Pending |

## Risks and remaining work

- Batch domain models/state reducer and protocol v2 codecs are present, but there is no end-to-end `BatchTransferCoordinator` yet. Batch frames are not evidence that a complete multi-item exchange is wired into the authenticated session.
- The Android SAF source/staging/publication path for each batch item is not yet connected to the batch protocol. The CP-004 acceptance flow remains incomplete.
- Wire compatibility and negotiation boundary with CP-003 must be exercised with an explicit compatibility test before implementation is considered stable.
- Batch and item maximums are bounded in models/codecs, but their aggregate storage/resource limits still need validation against real storage providers and low-space scenarios.
- SAF providers vary in deletion, reopen and read-back behavior; provider-specific failure must remain non-success.
- A failed item can continue only after a validated terminal item boundary; otherwise terminate the batch.
- Lint warning debt (13 warnings) remains; the exact categories and their rationale are recorded in the 2026-11-08 checkpoint log.
- Physical CP-004 transfer evidence is absent. Test the final batch flow on multiple OEM/API devices, including interruption, provider-specific SAF paths, low storage and screen-off behavior.
- CP-003 physical evidence remains incomplete independently of CP-004.

