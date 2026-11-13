# ADR-0015: Local Message Persistence and Synchronization Semantics

- **Status:** Proposed — protocol and data-model review required
- **Date:** 2026-11-13
- **Scope:** Durable local messages, outbox, synchronization, and acknowledgement

## Context

A disconnected sender must be able to queue a message and synchronize later. Disconnects, process death, retries, duplicate frames, and receiver storage failures are normal failure cases. The system must not lose messages or produce duplicate timeline entries.

## Proposed decision

### Local commit

- Persist the message and its outbox record in one database transaction.
- Only after that transaction commits may the UI report “Saved on this device”.
- If persistence fails (including storage exhaustion), do not clear the composer or claim the message was saved.

### Delivery and acknowledgements

- Treat transport as at-least-once.
- Give each message an immutable unique ID and a sender-scoped monotonic sequence within the conversation.
- Make receiver persistence idempotent using unique constraints on message identity and sender sequence.
- The receiver acknowledges a message only after its durable local transaction commits.
- The sender changes the message to Delivered only after validating that acknowledgement.
- A duplicate message is not inserted twice; the receiver may acknowledge the already committed record again.
- Do not interpret delivery acknowledgement as a read receipt.

### Synchronization progress

- Exchange bounded per-conversation synchronization summaries/cursors after peer authentication.
- Transfer only missing records in bounded batches.
- Advance a received cursor only across contiguous, durably committed sequence numbers.
- Preserve gaps and unacknowledged outbox records across interruption.
- Do not advance cursors or mark messages delivered on socket write completion.

### Ordering proposal

Use a logical ordering strategy rather than relying on device wall clocks. Each sender uses a durable monotonic sequence. For concurrent messages from different senders, a deterministic tie-breaker must be selected and persisted. A hybrid/logical clock may be evaluated, but the exact algorithm is not approved by this ADR.

## Consequences

- Duplicate network delivery is expected and must be harmless.
- Acknowledgement means “durably received by the peer app”, not “seen by a human”.
- Database transaction durability becomes part of the user-visible message lifecycle.
- Message ordering and cursor correctness need extensive tests under reordering and interruption.

## Open decisions

- Exact ID and sequence representation.
- Logical ordering algorithm and deterministic tie-break rules.
- Sync summary encoding and wire-frame version.
- Batch size, limits, retry policy, and queue quotas.
- Whether messages can be edited (not assumed) and how edits affect sequencing.

## Acceptance evidence before implementation

- Model-based tests for duplicate, reordered, missing, and repeated batches.
- Process-death tests before/after local commit and before/after remote acknowledgement.
- Tests proving that receiver storage failure cannot produce Delivered.
- Two-device tests for simultaneous offline composition and convergence.
