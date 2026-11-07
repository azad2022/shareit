# ADR-0008 — Secure multi-item one-to-one batch transfer

- **Status:** Proposed for CP-004; implementation and interoperability validation pending.
- **Date:** 2026-11-07
- **Scope:** One sender, one authenticated receiver, multiple user-selected arbitrary content URIs in one ordered batch. No group transfer, APK/split APK, resume, or background-continuation guarantee.

## Context

CP-003C provides a secure one-item operation per authenticated TLS session. `SingleFileTransferCoordinator` currently accepts exactly one sender item (`singleOrNull()`), owns the authenticated channel, and coordinates one manifest/consent/data/staging/publication/receipt lifecycle. `SingleFileTransferProtocol` is intentionally single-item and its frame/state validation must not be weakened by wrapping it in a loop.

CP-004 must introduce explicit batch and item identity, bounded metadata, deterministic ordering, per-item consent and integrity receipts, and failure isolation while preserving the CP-003 single-file protocol path.

## Decisions

### 1. Batch ownership and compatibility

1. Introduce a separate batch orchestration boundary. Do not turn `SingleFileTransferCoordinator` into a multi-purpose batch coordinator and do not reuse a single-item state object as batch state.
2. Keep the existing single-file coordinator/protocol behavior available and covered by regression tests. Shared low-level codecs/streaming primitives may be extracted only when behavior remains wire-compatible and the extraction is independently tested.
3. A batch owns one already-authenticated TLS channel exclusively for its lifetime. Exactly one sender and one receiver participate. The coordinator is the sole owner of channel claim, item sequencing, cancellation and final teardown.
4. The batch is sequential: at most one active item and one source descriptor at a time. No parallel item streams are introduced in CP-004.

### 2. Protocol version and identifiers

1. Define a new batch-capable protocol version or an explicitly negotiated capability that cannot be confused with the CP-003 single-item wire format. Never infer batch support from discovery metadata.
2. Use a cryptographically random `batchId` for the operation and a unique random `itemId` for every item. Every control/data/ACK/receipt message is bound to the authenticated session, batch ID and relevant item ID.
3. A bounded batch offer contains only aggregate information required for an initial decision (item count and a declared aggregate size only when every size is known). It must not include file names, MIME values, URI values, sender paths or file bytes.
4. After batch acceptance, each item is introduced in the predeclared stable queue order using a bounded item manifest (item ID, sanitized display metadata, MIME type and optional declared size). Validate count, uniqueness, aggregate limits, individual limits, string lengths, MIME syntax, and total control-frame size before acting.
5. Reject duplicate, missing, reordered, unknown or cross-batch item identifiers; reject illegal transitions and any item data received before that item's acceptance.

### 3. Receiver consent policy

Use two explicit consent boundaries:

1. **Batch consent:** after authenticated TLS, the receiver sees only the aggregate offer (count and aggregate size if known) and may accept or reject the entire operation. No item metadata or file content is sent before batch acceptance.
2. **Per-item consent:** after batch acceptance, the sender introduces one item manifest at a time. The receiver may accept or reject that item. The sender must not open the ContentResolver source stream or send bytes until the matching item-level ACCEPT is received. Item rejection records a terminal REJECTED state for that item and advances according to the queue policy; it does not silently authorize another item.

The receiver UI must clearly distinguish whole-batch rejection from an individual item rejection. Consent waits have finite deadlines; timeout is not acceptance.

### 4. Queue and failure policy

1. Preserve the sender's selected order exactly; never sort by filename or provider metadata.
2. The batch queue is in-memory for CP-004. Durable process-death recovery is deferred to CP-007; do not label this queue persistent across process death.
3. Process one item at a time. A terminal item outcome is one of COMPLETED, REJECTED, CANCELLED or FAILED.
4. An item-level rejection does not abort the remaining queue. A recoverable item-local failure (for example source open/read failure or destination publication failure) marks only that item FAILED, then sends a bounded authenticated ITEM_ABORT/ITEM_RESULT and proceeds only if both peers confirm a synchronized terminal item boundary.
5. A channel failure, malformed protocol, batch-ID mismatch, invalid ACK/receipt, TLS closure, cancellation of the whole batch, or inability to establish a synchronized item boundary terminates the batch and marks all not-yet-started items NOT_STARTED/CANCELLED as appropriate. Never continue on a desynchronized byte stream.
6. The batch summary is derived from item states. Batch COMPLETED means every item reached a terminal state and every item marked COMPLETED has a verified receiver receipt; it does not mean every item succeeded. Expose partial success distinctly.

### 5. Data plane, integrity and publication

1. Reuse the CP-003 bounded chunk/window/durable-ACK rules per active item; do not multiply the four-chunk memory bound by the number of queued items.
2. Open at most one source URI stream after its item ACCEPT. Close it before advancing to the next item. Cancellation must close a blocking source stream and the channel through the owning coordinator.
3. Receiver staging, byte counting and SHA-256 are independent per item. An item's stage must be sealed and verified before publication; publication read-back must match that item's exact byte count and digest.
4. Every completed item receives a distinct authenticated receipt bound to session ID, batch ID, item ID, byte count and SHA-256. A receipt for one item cannot complete another item or the batch.
5. No partial artifact may be presented as completed. Preserve existing SAF provider cleanup/error semantics; inability to verify destination read-back is not success.

### 6. State model and observability

Maintain separate immutable batch and item state. Batch state includes batch ID, ordered item IDs, current index, aggregate counters and terminal/partial-success summary. Item state includes its own phase, metadata, declared/committed bytes, result, error and verified receipt reference.

Progress is based on receiver-committed ACK bytes for the active item. Completed bytes in the batch summary count only receiver-verified completed items; do not conflate bytes sent, acknowledged, staged, published and verified. UI observes state and submits decisions only; it does not own sockets, streams, storage or protocol orchestration.

### 7. Resource bounds

Define and enforce explicit limits for item count, per-item bytes, aggregate declared bytes, manifest frame size, metadata string lengths, consent timeout, item timeout, batch deadline, queue size, chunk window and staging reserve. Limits must be documented, tested at boundary values, and aligned with storage capacity. Unknown source sizes are counted while streaming and must not bypass aggregate/runtime limits.

## Consequences

- The existing single-item wire protocol remains isolated and backward-compatible.
- Batch metadata disclosure is minimized until the receiver accepts the aggregate operation.
- Per-item consent and receipts provide independent accountability.
- Sequential processing simplifies descriptor ownership, backpressure, disk-space accounting and failure isolation.
- CP-004 does not promise queue recovery after process death; that requires CP-007 durable state and authenticated resume design.

## Required validation before acceptance

- Codec tests for protocol negotiation/versioning, malformed/oversized offer and item manifests, duplicate IDs, cross-batch IDs, illegal ordering and terminal outcomes.
- State-machine tests for batch and item transitions, rejection, item-local failure continuation, batch cancellation, channel failure and partial success.
- Coordinator tests with multiple items, mixed MIME types, zero-byte and large items, unknown sizes, source open only after item ACCEPT, item-specific hash/receipt binding, and resource closure between items.
- Negative tests for a failed item followed by a valid item, stream desynchronization, invalid ACK/receipt, timeout, storage exhaustion and cancellation during a blocking read.
- Regression suite proving CP-003 single-file behavior remains unchanged.
- Android Debug build, configured lint and relevant JVM/instrumented tests.
- Physical Wi-Fi Direct/mTLS and SAF-provider tests on the supported handset matrix. Do not mark CP-004 PASSED without reproducible physical evidence.
