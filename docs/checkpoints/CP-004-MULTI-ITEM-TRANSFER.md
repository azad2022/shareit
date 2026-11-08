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

## Validation record

| Check | Command / evidence | Result |
|---|---|---|
| Branch base | GitHub branch ref API | Verified at `7237115e1ce04ab225cbad223fad77e9ed8d344f` |
| CP-003 CI | [Workflow 42392698691](https://github.com/azad2022/shareit/actions/runs/42392698691) | Success; prior checkpoint evidence |
| CP-004 unit tests | Not run | Pending |
| CP-004 lint | Not run | Pending |
| CP-004 Debug build | Not run | Pending |
| Physical CP-004 | Not run | Pending |

## Device matrix

| Device | Android/API | Installed APK SHA-256 | Direction | Result |
|---|---|---|---|---|
| Samsung Galaxy A12 (SM-A125F) | Not recorded for CP-004 | Not tested | Both | Pending |
| ASUS X017DA | Not recorded for CP-004 | Not tested | Both | Pending |

## Risks and remaining work

- Wire compatibility and negotiation boundary with CP-003 must be tested before implementation is considered stable.
- Batch and item maximums need concrete bounded values derived from storage/resource tests; do not accept unbounded item count or aggregate size.
- SAF providers vary in deletion, reopen and read-back behavior; provider-specific failure must remain non-success.
- A failed item can continue only after a validated terminal item boundary; otherwise terminate the batch.
- CP-003 physical evidence remains incomplete independently of CP-004.

