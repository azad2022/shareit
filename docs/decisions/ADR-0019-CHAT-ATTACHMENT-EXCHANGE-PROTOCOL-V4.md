# ADR-0019 — Correlated Bidirectional Chat Attachment Protocol v4

- **Status:** Accepted engineering design for CP-CHAT-003 implementation
- **Date:** 2026-11-17
- **Scope:** One-to-one in-chat file attachments over the existing authenticated FILE_TRANSFER logical channel
- **Related:** ADR-0017, ADR-0018, ADR-0009 (CP-004 batch protocol)

## Context

The v1 single-file protocol has empty ACCEPT/REJECT/CANCEL control frames and cannot safely correlate multiple active transfers. The existing v3 batch protocol correlates frames by batch ID, but it requires at least two items and asks for receiver consent twice. Starting sender and receiver protocol instances directly on the same FILE_TRANSFER input stream would create competing readers.

CP-CHAT-003 needs one-item and multi-item attachments, automatic reception after session consent, both peers initiating transfers, and exactly one reader per logical channel. CP-004's existing v3 behavior must remain isolated and unchanged.

## Decision

Introduce **chat attachment protocol version 4** using the existing authenticated TLS socket, existing FILE_TRANSFER logical channel and existing batch frame family (wire codes 12–31). Do not add a socket, service, relay or public-network path.

- BATCH_PROTOCOL_VERSION remains 3 for CP-004.
- CHAT_ATTACHMENT_PROTOCOL_VERSION is 4 and is used only by the chat attachment exchange.
- v4 supports 1–32 items per batch. v3 remains constrained to 2–32 items.
- Every v4 batch frame carries the batch UUID in the first 16 payload bytes. Item-scoped frames additionally carry the item UUID after it.
- The chat attachment path sends v4 only. It must not silently retry with v1 or v3 if the peer does not support v4. Show a clear “both devices need a compatible PEYVAND version” message while leaving text chat available.
- The existing multiplexer remains the sole TLS reader/writer. It serializes complete frames and owns CHAT and FILE_TRANSFER logical channels independently.

## Frame lifecycle

1. The local sender registers an outbound batch route before writing BATCH_OFFER.
2. BATCH_OFFER contains only the batch UUID; it discloses no filenames, MIME types, sizes or item count.
3. The peer's session-scoped receive grant must already be active. Its one FILE_TRANSFER dispatcher creates an inbound route and automatically sends BATCH_OFFER_ACCEPTED. No per-offer, per-batch or per-item dialog is shown.
4. The sender transmits BATCH_MANIFEST. Its bounded, validated manifest contains 1–32 ordered items with unique item IDs, sanitized display names, MIME hints and optional declared sizes.
5. The receiver automatically selects all manifest items and sends BATCH_DECISION bound to the exact manifest digest. An item that exceeds policy/storage capacity receives an item-scoped failure; it does not require another consent prompt.
6. Existing item start/ready, chunk, cumulative acknowledgement, completion, verification, failure and cancellation frame types are reused with v4 framing and the same batch/item correlation.
7. A file is reported as Saved/Received only after exact byte-count and SHA-256 verification and successful durable publication. Text-message delivery remains independent.

## Single-reader frame routing

- Exactly one BatchTransferFrameRouter reads FILE_TRANSFER input.
- It validates session ID, protocol version, batch frame type and the batch UUID before routing.
- One bounded route exists per active batch. Unknown batch IDs, duplicate inbound offers, wrong-session frames, unsupported versions and malformed payloads fail closed.
- At most one outbound batch and one inbound batch may be active per session initially. Items within each batch are processed sequentially. Additional local selections are queued in the UI; a second inbound batch in the same direction is rejected as a protocol/resource violation.
- Each route has a bounded 128 KiB pipe. At most two routes exist, bounding route buffering to 256 KiB, in addition to the existing bounded channel and protocol buffers.
- All protocol writers use the existing channel output stream; the multiplexer must serialize complete encoded frames atomically. No concurrent raw stream readers or independent socket writers are allowed.
- Closing a batch route releases only its virtual route. Closing the FILE_TRANSFER child releases only that logical lease; the chat channel remains active.

## Security and resource controls

- The v4 receive grant is valid only for the exact authenticated peer identity and current session ID; it is destroyed on Wi-Fi/P2P loss, TLS failure, identity mismatch or explicit disconnect.
- No offer is accepted when the session grant is absent, even if a FILE_TRANSFER frame arrives.
- Validate manifest count, duplicate IDs, lengths, display names, MIME hints, declared sizes, item transitions, per-item limits, session quota, free-space reserve, concurrency and deadlines before or during staging.
- Stream to private staging with bounded buffers and backpressure. Verify actual size and SHA-256 before publication. Partial artifacts remain private and are deleted or marked interrupted.
- Filenames and MIME values are untrusted metadata. Never treat MIME as a content-safety guarantee.
- Received files are never automatically opened, executed or installed. APK installation remains a separate explicit Android PackageInstaller action.

## Compatibility and non-goals

- CP-004's v3 sender/receiver path remains unchanged on its isolated branch. No CP-004 files or branch refs are changed by this ADR.
- v1 single-file frames and v3 batch frames are not used as a silent fallback for v4 chat attachments.
- Group chat, channels, many-to-many transfers, multiple inbound batches, background delivery after session termination and classroom membership are out of scope.
- Future classroom mode requires a separate threat model and ADR.

## Acceptance tests

Require tests for v4 one-item and multi-item manifests; v3 minimum-two-item compatibility; simultaneous one-outbound/one-inbound batches; frame interleaving and correlation; one-reader ownership; complete-frame writer serialization; unknown/duplicate/replayed batch IDs; wrong session/version; malformed/out-of-order frames; route and buffer limits; auto-reception only with an active session grant; cancellation and failure isolation; low storage/quota; and digest-verified publication.
