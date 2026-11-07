# CP-004 — Multi-item one-to-one transfer

- **Status:** `IN_PROGRESS`
- **Branch:** `feature/cp-004-multi-item-transfer`
- **Base:** `feature/secure-file-transfer` at `7237115e1ce04ab225cbad223fad77e9ed8d344f` (includes the latest documentation-only updates after CI-validated code `0bb8d6582d860a45ded777ac9432d617c2106f55`)
- **PR #3:** remains open, Draft and unmerged. CP-004 must not change or merge PR #3.
- **Objective:** send an ordered batch of 2–32 arbitrary user-authorized content files from one sender to one receiver over the already authenticated Wi-Fi Direct/mTLS session. A one-item selection must retain the existing v1 transfer path.
- **Architecture decision:** see [ADR-0009](../decisions/ADR-0009-MULTI-ITEM-ONE-TO-ONE-TRANSFER.md). Receiver consent is one batch-level selection with explicit per-item inclusion/exclusion; transfer and integrity remain independent per item. Version-2 batch frames are separate from the existing v1 one-file wire contract.
- **Current implementation status:** planning baseline recorded. No CP-004 source changes or tests have been committed yet.

## Scope

Included:
- Select 2–32 generic files from approved SAF/ContentResolver URIs, in a deterministic user-selected order.
- Version-2 bounded batch manifest, batch/item IDs, receiver selection decision, per-item start/readiness, data/ACK, completion/integrity receipt, error/cancel outcome and terminal batch receipt.
- Sequential queue with independent immutable item states and actual acknowledged-byte progress.
- Single receiver-selected SAF destination tree, with per-item document create, copy and read-back verification.
- Item-local recoverable failure/cancellation isolation and continuation; fatal protocol/session errors stop the batch.
- Preserve the current single-file v1 transfer path and its security/resource invariants.

Excluded:
- Sender-to-many or group transfer; multiple simultaneous senders.
- APK/split-APK extraction or installation.
- Resume after disconnect/process death, background continuation guarantees and persistent recovery (CP-007).
- New discovery/transport fallback, TLS weakening, release signing and product-wide redesign.

## Acceptance criteria

1. The sender selects 2–32 `content://` files in a stable order; installed-package sources and invalid/duplicate items are rejected.
2. The receiver sees a bounded manifest only inside the authenticated TLS session and explicitly chooses the items to accept in one consent interaction. No source InputStream is opened and no staging file is allocated before the batch decision and per-item readiness.
3. Protocol codecs enforce version, session ID, batch ID, item ID, frame bounds, manifest count, unique IDs, exact lengths, order and valid state transitions before processing data.
4. Items transfer serially with at most one source stream, one staging sink and one publication operation active. Chunks are at most 64 KiB, the receive window is at most four chunks/256 KiB, and ACKs follow durable stage sync.
5. Every item has independent QUEUED/WAITING/TRANSFERRING/VERIFYING/PUBLISHING/COMPLETED/REJECTED/CANCELLED/FAILED state, actual progress, and an authenticated byte-count/SHA-256 receipt when completed.
6. A recoverable item failure or item cancellation deletes that item's uncommitted stage and does not corrupt the next item. A fatal protocol/session error stops the batch without publishing incomplete data.
7. Batch completion is reported only after all item outcomes are terminal and the authenticated batch receipt has been verified. Partial success remains visibly partial.
8. Receiver publication uses one user-selected SAF tree, a bounded copy, read-back SHA-256 and byte-count verification, and best-effort confirmed cleanup on failure; no filesystem path assumption or overwrite shortcut is introduced.
9. Tests cover mixed MIME, zero-byte, large, Persian/Unicode, extensionless, reject, cancel, item failure then continue, low storage/provider error, independent receipts, resource cleanup and malformed/cross-item protocol frames.
10. Existing version-1 single-file protocol/coordinator tests remain green and the one-file app route remains usable.
11. Configured lint and Debug build pass on the exact final commit. No physical-device result is marked PASSED without exact APK/build details and repeatable evidence.

## Planned validation commands

- `./gradlew :core:transfer:testDebugUnitTest`
- `./gradlew :core:domain:testDebugUnitTest`
- `./gradlew :core:storage:testDebugUnitTest` (only if storage unit tests are added/configured; otherwise record Android storage behavior as instrumentation/manual pending)
- `./gradlew lint`
- `./gradlew :app:assembleDebug`

The exact workflow may run the same tasks through GitHub Actions. Record actual commands and results from the final commit; do not infer results from a prior SHA.

## Evidence and device matrix

- Documentation baseline: `7237115e1ce04ab225cbad223fad77e9ed8d344f`.
- Code SHA: pending.
- CI run/jobs: pending.
- Debug APK artifact/hash: pending.
- Physical devices: Galaxy A12 SM-A125F Android 12/API 31 and ASUS X017DA; exact build, API, APK digest, roles, direction and repetition: pending.
- SAF provider matrix, destination tree restrictions, low-space and provider write/delete behavior: pending.

## Risks and open verification

- Version-2 negotiation is intentionally not backward-compatible for multi-item batches; old builds must fail closed. Version-1 one-file transfer remains the compatibility route.
- SAF tree providers vary in creation, collision, output-stream, read-back and deletion semantics. No universal atomicity is promised.
- A provider may return unknown or stale size/MIME metadata. Actual streamed byte count and SHA-256 remain authoritative; size ceilings must be enforced while staging.
- A blocking provider read must be actively closed on item cancellation, not merely coroutine-cancelled.
- Physical Wi-Fi Direct/mTLS and Android DocumentProvider behavior are not covered by JVM codec tests.
- CP-003 remains `IN_PROGRESS` overall; CP-003C's basic one-file smoke path is owner-reported `PASSED`. This does not satisfy CP-003's complete physical failure-path matrix.
