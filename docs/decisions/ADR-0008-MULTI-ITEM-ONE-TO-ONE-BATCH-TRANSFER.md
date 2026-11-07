# ADR-0008 — Secure multi-item one-to-one batch transfer

- **Status:** Accepted by product owner for CP-004 design; implementation and interoperability validation pending.
- **Date:** 2026-11-07
- **Scope:** One sender, one authenticated receiver, multiple user-selected arbitrary `content://` files in one ordered batch.
- **Explicit exclusions:** Sender-to-many, multi-sender, APK/split APK, pause/resume, durable process-death recovery, guaranteed background continuation, and product-wide visual redesign.
- **Implementation authorization:** This ADR defines the design boundary. It does not, by itself, authorize application-code changes outside the owner's explicit CP-004 implementation instruction.

## 1. Context and constraints

CP-003C provides a secure one-item operation per authenticated TLS session. `SingleFileTransferCoordinator` owns one authenticated channel and coordinates one manifest, one consent decision, one source stream, one destination, one staging lifecycle and one integrity receipt. `SingleFileTransferProtocol` and `TransferFrameCodec` currently implement protocol version 1 only; the frame decoder rejects every other version. The current coordinator accepts exactly one sender selection through `singleOrNull()`.

CP-004 adds multiple items to one authenticated one-to-one session. It must not turn the single-file coordinator into a multi-purpose class, weaken v1 validation, multiply the existing data window by the queue length, disclose item metadata before consent, or permit a failed item to desynchronize the next item.

The queue is in-memory and sequential. At most one item is active, one source stream is open, one receiver staging artifact exists, and one bounded data window is in flight. Every completed item is independently verified and published before the next item begins.

## 2. Decision: preserve v1 and introduce an explicit v2 batch protocol

1. **Single-item operation:** selecting exactly one file continues to use the existing v1 wire protocol and `SingleFileTransferCoordinator`/`SingleFileTransferProtocol` path.
2. **Batch operation:** selecting two or more files uses a new, explicitly defined v2 batch protocol and a dedicated batch orchestration boundary. A v2 batch is never represented as repeated v1 sessions or repeated v1 manifests inside one channel.
3. **No implicit downgrade:** once the sender starts a v2 batch, an unsupported version, unknown v2 message, or peer incompatibility fails the batch. The sender must not retry the same batch as v1, silently fall back to one-file-at-a-time sessions, or reduce security requirements.
4. **No discovery-based negotiation:** DNS-SD/service-discovery metadata is not evidence that a peer supports batch transfer. Version selection is explicit in the authenticated transfer frame protocol after the existing peer-authentication/TLS stage.
5. **Frame envelope:** preserve the existing fixed 26-byte envelope fields (magic, protocol version, type, authenticated session ID, bounded payload length). The decoder must inspect and validate the common header before dispatching to a strict v1 or v2 codec. Each codec has its own type registry and per-type payload limits. v1 payloads and message semantics remain byte-for-byte compatible.
6. **Version binding:** every v2 frame is bound to the already authenticated TLS session through the envelope session ID. Every batch-scoped payload carries `batchId`; every item-scoped payload additionally carries `itemId` and the zero-based ordinal. IDs are random UUIDs generated with a cryptographically secure source. The ordinal validates queue order; it is never used as the item identity.
7. **No mixed protocol inside a batch:** a batch session accepts only the defined v2 batch message sequence. A v1 frame received during v2, or a v2 batch frame in a v1 single-file session, is a protocol error and closes the session.

The v2 codec must not reuse a v1 frame type with a different meaning. The following v2 message names and wire codes are reserved by this ADR:

| Code | v2 message | Direction | Purpose |
|---:|---|---|---|
| 32 | `BATCH_OFFER` | Sender → receiver | Batch ID, item count, optional known aggregate size |
| 33 | `BATCH_ACCEPT` | Receiver → sender | Explicit batch-level consent |
| 34 | `BATCH_REJECT` | Receiver → sender | Whole-batch rejection with bounded reason code |
| 35 | `ITEM_MANIFEST` | Sender → receiver | One item ID, ordinal and sanitized metadata |
| 36 | `ITEM_ACCEPT` | Receiver → sender | Per-item consent and current accepted byte limit |
| 37 | `ITEM_REJECT` | Receiver → sender | Per-item rejection or preflight failure |
| 38 | `DATA_CHUNK` | Sender → receiver | Batch/item-bound binary data at a contiguous offset |
| 39 | `CHUNK_ACK` | Receiver → sender | Durable cumulative acknowledgement for the active item |
| 40 | `ITEM_COMPLETE` | Sender → receiver | Final byte count and SHA-256 for the active item |
| 41 | `ITEM_VERIFIED` | Receiver → sender | Authenticated receipt after destination read-back verification |
| 42 | `ITEM_ABORT_REQUEST` | Receiver → sender | Request to stop the accepted active item |
| 43 | `ITEM_ABORT` | Sender → receiver | Ordered terminal marker after all sender-emitted data frames |
| 44 | `ITEM_ABORT_ACK` | Receiver → sender | Confirms the active item is drained and its stage is discarded |
| 45 | `ITEM_TERMINAL_COMMIT` | Sender → receiver | Commits the synchronized terminal item outcome |
| 46 | `BATCH_CANCEL` | Either peer → other | Cancels the entire batch |
| 47 | `BATCH_CANCEL_ACK` | Other peer → initiator | Confirms whole-batch cancellation |

All message payloads are length-bounded and strictly decoded. Reason values are finite enums; arbitrary remote error strings are not accepted as protocol commands. Unknown codes, unsupported fields, duplicate fields, invalid lengths, invalid state transitions and unknown identifiers fail closed.

## 3. Batch and item protocol sequence

### 3.1 Batch offer and consent

1. The sender freezes the selected queue and sends one `BATCH_OFFER` containing `batchId`, `itemCount` and `aggregateSizeBytes` only when the size of every selected source is known and the sum is overflow-safe. Unknown aggregate size is explicit, not represented as zero.
2. Before batch acceptance, the offer must not contain filenames, extensions, MIME values, individual sizes, content URIs, paths, hashes or file bytes. The opaque batch ID is not a device identity.
3. The receiver may accept or reject the whole batch. `BATCH_ACCEPT` is required before any `ITEM_MANIFEST` is sent. Whole-batch rejection is terminal and does not reveal item metadata.
4. Batch consent uses the existing 90-second user-consent timeout. Timeout is not acceptance; it terminates the batch with a timeout reason and closes the session safely.
5. A batch with fewer than two items or more than the configured maximum is rejected before a batch session starts. A one-item selection remains on v1.

### 3.2 Destination selection

1. After `BATCH_ACCEPT` and before the first item manifest, the receiver selects one destination directory for the batch using the Storage Access Framework directory picker (`ACTION_OPEN_DOCUMENT_TREE`). The destination is not selected separately for every item.
2. If the receiver cancels the directory picker or cannot obtain a usable user-granted directory, it cancels the batch before the first item manifest. No item is marked completed.
3. The tree URI is treated as a capability, not converted to a filesystem path. All child creation and writing use `DocumentsContract`/`ContentResolver` operations within the selected tree. No raw-path assumptions or broad storage permission are introduced.
4. The tree grant is used only for the active batch. CP-004 does not persist destination grants for process-death recovery and does not retain the directory URI in transfer history. Durable permission/state lifecycle belongs to CP-007.
5. A provider that cannot create and write new children, or whose behavior cannot be verified safely, causes the affected item to fail. The app must not silently switch to a different directory or provider.

The existing 300-second destination-selection timeout is the initial timeout for this one-per-batch picker. Android 11+ restrictions on selectable directory roots and provider-specific behavior must be handled explicitly and tested; do not assume every visible provider supports identical operations.

### 3.3 Ordered per-item consent

1. The sender sends exactly one `ITEM_MANIFEST` at a time, in the frozen queue order. The manifest contains `batchId`, unique `itemId`, zero-based ordinal, sanitized display name, normalized MIME type and optional declared size.
2. The receiver validates the batch ID, expected ordinal, item-ID uniqueness, metadata bounds and the current storage limit before requesting user consent.
3. The receiver may accept or reject this item. `ITEM_ACCEPT` includes the receiver's current maximum accepted byte count for this item. That limit is recalculated for every item from the storage adapter's current safe staging capacity; it is not inferred from a prior item's free-space measurement.
4. A known declared size above the accepted limit is rejected before staging. An unknown-size item is accepted only with a finite accepted byte limit, which is enforced while streaming. The sender must enforce that same negotiated limit as well.
5. The sender must not call `ContentResolver.openInputStream()` or otherwise open the file-content descriptor until the matching `ITEM_ACCEPT` has been received and validated. Preparing/displaying bounded source metadata is not permission to read file content.
6. No next item manifest is sent until the current item has completed with a verified receipt or has completed the terminal failure handshake defined below.
7. Item consent uses the existing 90-second timeout. An explicit user rejection is `REJECTED`; timeout is `FAILED` with reason `CONSENT_TIMEOUT`. Timeout is never silently treated as a user rejection or acceptance.

### 3.4 Successful item sequence

For each accepted item:

1. Sender opens one source stream and begins v2 `DATA_CHUNK` frames. Every data chunk is bound to `batchId`, `itemId`, ordinal and contiguous byte offset.
2. The existing CP-003 streaming policy is reused: maximum 64 KiB per chunk, no more than four chunks/256 KiB unacknowledged, receiver stages the bounded window, calls its durability `sync`, commits contiguous offsets, and only then sends `CHUNK_ACK`.
3. The receiver validates that all chunks belong to the current batch/item, offsets are contiguous, chunk sizes are legal, the negotiated item limit is not exceeded, and the bounded in-flight window is respected. Non-final chunks remain exactly 64 KiB; only the terminal chunk may be shorter. Zero-byte files have no data chunks.
4. Sender and receiver calculate SHA-256 incrementally. The sender sends `ITEM_COMPLETE` only after source EOF and declared-size validation. The receiver flushes/syncs the final partial window, verifies exact byte count and SHA-256, seals staging, publishes to a new destination document, reopens/reads back the destination and verifies the same byte count and digest.
5. The receiver sends `ITEM_VERIFIED` containing the authenticated item identity, actual byte count and SHA-256 only after destination read-back verification succeeds. The sender validates that receipt against its own result. Both sides then mark that item `COMPLETED`.
6. The source stream and staging resources are closed before the next item begins. No more than one item source, one staging artifact and one data window may be active.

Progress for the active item is based on receiver-committed cumulative ACK bytes, not sender socket writes. Verified batch bytes count only items that have completed the receiver publication/read-back check and whose receipt was validated. Do not add bytes merely sent, acknowledged, staged or published without verification to the verified total.

## 4. Item failure, item cancellation and terminal synchronization

### 4.1 Item rejection before acceptance

- Receiver sends `ITEM_REJECT` with a bounded reason code; no source content stream is opened and no bytes are sent.
- Sender records `REJECTED` for an explicit user rejection, or `FAILED` for an automatic preflight/timeout error.
- Sender sends `ITEM_TERMINAL_COMMIT` for that batch/item/ordinal. Receiver must receive this commit before it accepts the next item manifest.

### 4.2 Failure or cancellation after `ITEM_ACCEPT`

An item-local failure may continue to the next item only after a synchronized terminal boundary. The sender is the sole writer of the sender-to-receiver data stream and the sole author of the terminal `ITEM_ABORT` marker:

1. If the receiver detects a recoverable item-local problem or the receiver cancels the active item, it stops writing to the stage, sends `ITEM_ABORT_REQUEST` with a bounded outcome/reason, and discards rather than stages any remaining in-flight data for that item.
2. The sender stops reading the source as soon as it processes that request, closes the active source stream, flushes no more than the already-reserved four-chunk/256 KiB window, and writes `ITEM_ABORT` after those data frames. `ITEM_ABORT` identifies the batch/item/ordinal, final sender-emitted offset, outcome (`CANCELLED` or `FAILED`) and reason code.
3. The receiver validates and drains only the already-in-flight chunks for the same item, without writing them to staging, until it reads the ordered `ITEM_ABORT` marker. The additional data after the receiver's abort request must not exceed the negotiated 256 KiB outstanding window. Wrong IDs, non-contiguous offsets, excess data or a missing marker are batch-fatal protocol errors.
4. After the marker, the receiver deletes the uncommitted stage and sends `ITEM_ABORT_ACK` for the exact terminal tuple. The sender sends `ITEM_TERMINAL_COMMIT`; the receiver must validate it before advancing to the next item.
5. If the sender itself detects a source-open/read failure or the sender cancels the active item, it closes the source and writes `ITEM_ABORT` after any data frames already emitted. The receiver follows the same drain, stage-delete, ACK and COMMIT sequence.
6. Item-level cancellation is supported only for the currently accepted active item. A queued item cannot be removed after `BATCH_OFFER`; the queue is immutable. To stop future queued items, either peer cancels the whole batch. Before `ITEM_ACCEPT`, use whole-batch cancellation rather than attempting an item-abort exchange.
7. If an abort ACK/commit is not received before its finite protocol timeout, or either side cannot prove that both peers reached the same terminal item boundary, the entire batch fails and the TLS/P2P channel is closed. Continuing on an uncertain stream is prohibited.

A failure is item-local only when the frame sequence is valid, all outstanding bytes are bounded and drained, staging is safely discarded, and both peers complete the terminal handshake. Malformed frames, wrong session/batch/item IDs, invalid offsets, invalid ACKs, invalid receipts, TLS closure, unexpected messages, source/destination ambiguity that cannot be isolated, or cleanup failure that leaves the bounded staging resource unavailable are batch-fatal. A byte-count or SHA-256 mismatch is never publishable; it may be isolated to the item only after the terminal handshake succeeds.

### 4.3 Whole-batch cancellation

- Either peer may send `BATCH_CANCEL` at any point after the batch offer.
- The other peer stops the active item, closes its source or discards its active stage, records remaining queued items as `NOT_STARTED`, and returns `BATCH_CANCEL_ACK`.
- Both peers then close the authenticated channel and P2P group through the existing owner-managed teardown path. Whole-batch cancellation does not attempt to continue the queue.
- If the cancellation acknowledgement cannot be completed, local teardown still happens; the initiating side reports cancellation with an unconfirmed peer outcome, not a falsely synchronized cancellation.

## 5. Batch and item state model

Keep batch state and item state as separate immutable domain models. The UI observes snapshots and submits decisions; it does not own socket, stream, file, staging, cryptographic or protocol operations.

### Batch lifecycle

`CREATED → OFFERED → AWAITING_BATCH_CONSENT → ACCEPTED → SELECTING_DESTINATION → PROCESSING → TERMINAL`

Terminal outcomes:

- `COMPLETED_SUCCESSFULLY`: every item is `COMPLETED` with a validated receiver receipt.
- `COMPLETED_WITH_ERRORS`: every item has a terminal outcome, but at least one is `REJECTED`, `CANCELLED` or `FAILED`.
- `REJECTED`: receiver explicitly rejected the whole batch.
- `CANCELLED`: whole-batch cancellation was locally requested; peer confirmation is tracked separately.
- `FAILED`: channel/security/protocol/timeout/cleanup failure made the batch unsafe or incomplete. Items not yet introduced become `NOT_STARTED` with the batch failure reason.

`COMPLETED` alone is not a batch success state. A partial success must be represented as `COMPLETED_WITH_ERRORS` and shown as such.

### Item lifecycle

`QUEUED → OFFERED → AWAITING_CONSENT → ACCEPTED → TRANSFERRING → VERIFYING → PUBLISHING → COMPLETED`

Terminal item outcomes are `COMPLETED`, `REJECTED`, `CANCELLED`, `FAILED` and `NOT_STARTED`. Every non-success terminal outcome carries a bounded typed reason code. No UI string or localized phrase is used as an identifier or protocol value.

The batch summary is derived from item states; it is not independently mutated into success. The UI must distinguish whole-batch rejection, per-item rejection, per-item failure, per-item cancellation, whole-batch cancellation, partial completion and verified completion.

For unknown-size items, show truthful bytes transferred and state without inventing a percentage or ETA. Do not present one aggregate percentage unless every item size is known and the calculation is based on receiver-committed bytes; even then, item outcomes and verified counts remain visible separately.

## 6. Android selection and destination policy

### Source selection

- Use `ACTION_OPEN_DOCUMENT` with `CATEGORY_OPENABLE`, MIME type `*/*` and `EXTRA_ALLOW_MULTIPLE=true` for the general arbitrary-file picker.
- For one selected document, read the result URI from `Intent.getData()`; for multiple documents, read the returned `ClipData` items. Normalize both result forms into the existing ordered `ShareItem` collection.
- The immutable queue order is the order delivered by the selection adapter (`ClipData` order for a multi-selection result). Do not sort by name, MIME, size, provider ID or path. Android's public contract documents `ClipData` as the multiple-result container but does not guarantee original tap chronology; do not promise tap-order semantics. If a later UX explicitly supports manual reordering, that resulting list order becomes the queue order.
- Keep URIs and bounded metadata only while queued. Do not pre-open all source streams or retain multiple descriptors. Each URI must remain user-authorized when its item reaches acceptance; a revoked or unavailable URI becomes an item-local source failure only if the abort handshake can complete.

### Destination directory and collisions

- Receiver chooses one user-visible destination directory per batch through `ACTION_OPEN_DOCUMENT_TREE`. The app creates a new child document for each verified item through the selected tree; it never opens an existing same-name destination document for output.
- Never overwrite an existing file. Resolve name collisions with a bounded deterministic suffix before the extension (for example, `report (1).pdf`) and use the provider-returned display name as the final visible name. If provider semantics do not allow the app to verify that a distinct new document was created, fail that item rather than risk replacing an existing artifact.
- Use `DocumentsContract` and `ContentResolver` URI operations only. Do not build filesystem paths from peer metadata. Sanitize names using the existing v1 filename policy and retain Unicode/Persian names where safe.
- A failed copy or read-back must not be shown as completed. Attempt provider-supported deletion of the incomplete new child. If deletion cannot be confirmed, report a possible leftover incomplete artifact and do not silently retry into or overwrite it. Continue only when the item terminal handshake succeeds and resource ownership is safe.
- Provider quotas and storage semantics differ. Re-check the app-private staging capacity before each item and enforce the negotiated item limit while streaming. Provider write/space errors are item-local only when the failure can be isolated and the terminal handshake completes.

## 7. Resource and timeout policy

1. `MAX_BATCH_ITEMS` is initially **100**. This is a hard CP-004 v2 bound and must be tested at 1, 2, 100 and 101 items. A one-item queue uses v1; a v2 batch must contain 2–100 items.
2. The v1/v2 control-frame payload maximum remains **16 KiB** unless a separately reviewed ADR changes it. Batch offers and one-at-a-time item manifests must each fit that bound; never send a single all-items manifest array.
3. Existing metadata bounds remain **255 UTF-8 bytes for display name** and **127 UTF-8 bytes for MIME type**. Malformed UTF-8 and oversized fields are rejected before acting.
4. Existing data bounds remain **64 KiB per chunk**, **four in-flight chunks**, and **256 KiB maximum outstanding data**. The same bound applies during abort draining. Queue length must not multiply the memory window.
5. Per-item receive capacity is dynamic: query `maximumReceiveSizeBytes()` immediately before item acceptance, negotiate that value in `ITEM_ACCEPT`, and enforce it in the staging writer while streaming. Re-query before every item. No fixed per-item limit may exceed the receiver's current safe staging capacity.
6. Do not invent a generic SAF destination free-space figure or a universal aggregate-byte ceiling: a `DocumentsProvider` may represent local, removable or remote storage and does not expose a reliable common quota contract. Aggregate known size is shown to the receiver; actual writes are authoritative. Track cumulative verified bytes with checked `Long` arithmetic and fail closed on overflow. The 100-item cap, one-at-a-time staging, per-item negotiated capacity, 16 KiB control bounds and receiver/provider write failures bound the actual resource use.
7. Batch consent and per-item consent each use **90 seconds**, matching CP-003's current consent policy. Destination directory selection uses **300 seconds**, matching the current SAF destination-picker policy.
8. Data/ACK/abort protocol operations require finite read/write deadlines. The initial v2 no-progress timeout is **120 seconds**, reset only by a validated frame or committed-byte progress. It is not a whole-transfer deadline; long transfers may run longer while making progress. No fixed wall-clock batch deadline is imposed.
9. All arithmetic involving item ordinals, byte offsets, aggregate byte counts and maximum lengths must be overflow-checked. No unbounded retries, buffers, metadata queues, descriptors, stages or concurrent item streams are permitted.

These are protocol/resource safety bounds, not throughput claims. They must be covered by boundary tests and physical-device measurements before CP-004 can pass.

## 8. Security and privacy invariants

- The local network remains hostile; discovery is not identity. CP-004 starts only after the existing SAS approval and mutually authenticated TLS session has succeeded.
- Before batch consent, reveal only the aggregate offer fields explicitly defined above. Do not include sender URI, filesystem path, device MAC address, filename, MIME, per-item size or content.
- Every v2 control/data/ACK/receipt/abort message is bound to session ID, batch ID and, when item-scoped, item ID and ordinal.
- Validate message type, protocol version, frame length, IDs, ordinal, item order, state, MIME syntax, metadata length, byte offsets, data-window limits, negotiated size, reason code and terminal receipt before side effects.
- Do not accept replayed, duplicate, missing, reordered, cross-session, cross-batch or cross-item messages. A duplicate ACK must not release flow-control capacity twice.
- No source stream opens before item acceptance. No receiver stage becomes a user-visible file before exact size/hash verification. No item is reported complete before the receiver's destination read-back and sender's authenticated receipt validation.
- Never log file contents, full content URIs, raw paths, hashes, or unnecessary private metadata. Use bounded typed diagnostics.
- Failure, cancellation and teardown release the source stream, staging sink, temporary artifact, channel, coroutine/job and P2P group according to their owning component.

## 9. Required implementation boundaries

- A dedicated `BatchTransferCoordinator` owns the batch operation, channel claim, immutable queue, sequence, active item, cancellation handshake, aggregate state and teardown.
- `SingleFileTransferCoordinator`, v1 frame types, v1 manifest codec, v1 state machine and v1 wire behavior remain dedicated to one-item transfer. Shared low-level primitives may be extracted only if v1 byte-level compatibility and regression tests prove no behavior change.
- Domain models and reducers for batch and item state remain Android-free and independently unit-testable.
- Android adapters own `ACTION_OPEN_DOCUMENT` result normalization, `ACTION_OPEN_DOCUMENT_TREE`, URI permissions, `ContentResolver`, staging and destination-provider operations.
- No Compose/ViewModel class owns a socket, TLS stream, source descriptor, staging file, SHA-256 policy or protocol loop.
- The v2 protocol is transport-independent at the domain boundary and receives its authenticated channel through an explicit ownership contract. It must not depend on Compose or presentation types.

## 10. Required validation before CP-004 acceptance

### Protocol and codec

- v1 byte-for-byte compatibility and all existing CP-003 tests remain green.
- v2 header/version dispatch, every assigned message code, wrong-version/wrong-type combinations and unsupported-version failure.
- Batch offer boundaries, item-count boundaries, unknown/known aggregate size, overflow, malformed UTF-8, oversized metadata, malformed MIME, duplicate IDs, duplicate ordinals, missing/reordered items, cross-session/batch/item IDs and invalid state transitions.
- Data chunk contiguous offsets, non-final chunk size, final short chunk, zero-byte item, maximum 64 KiB chunk, four-chunk window, invalid/duplicate/future ACK and abort-drain upper bound.
- Consent rejection/timeout before source open; source open only after matching item accept.

### State and coordinator

- Separate batch/item reducers; all legal transitions and rejection of illegal transitions.
- Mixed MIME files; stable order; 2, 10 and 100 items; one item remains v1.
- One failed/rejected/cancelled item followed by a valid item; batch partial-success summary; whole-batch rejection/cancel; channel loss; protocol error; timeout; invalid receipt; abort handshake; simultaneous/late cancellation behavior.
- Cancellation during a blocking source read closes the stream and channel promptly. At most one source, one stage and one data window remain active.
- No later item starts before the preceding item's receipt or terminal commit has been validated.

### Storage and Android interoperability

- `ACTION_OPEN_DOCUMENT` single and multiple result forms; URI permission revocation; provider returning unknown size/MIME; Unicode/Persian, extensionless, long and duplicate names.
- One directory selection per batch; providers with different tree/create/write/read-back/delete behavior; collision handling; low staging space; destination full/quota/write errors; failed cleanup; no overwrite of a pre-existing file.
- Zero-byte, large and unknown-size files; exact byte count and SHA-256; sender/receiver both directions; repeated batches; cancellation and disconnect.
- Physical-device evidence on Samsung Galaxy A12 (SM-A125F) and ASUS X017DA with Android/API/build, exact installed Debug APK SHA-256, direction, selected source provider, destination provider, file characteristics, repetition count and result.
- Exact-head GitHub Actions: unit tests, configured lint, Debug assembly and relevant Android instrumentation. CI does not replace physical Wi-Fi Direct/mTLS/SAF testing.

CP-004 remains `IN_PROGRESS` until the complete evidence package is reproducible. Do not mark it `PASSED` because the happy path or CI alone succeeds.

## 11. Consequences and known limitations

- One authenticated session can transfer multiple items in stable order without changing the existing one-file v1 path.
- Batch metadata is withheld until batch consent; each item's metadata and bytes are separately gated by item consent.
- Sequential processing and a fixed four-chunk window bound active memory and descriptor ownership independently of queue length.
- Item-local failures can be isolated only through a validated terminal handshake. Channel/protocol ambiguity stops the entire batch.
- A receiver may have successfully published an item immediately before a connection failure prevents the sender from receiving its receipt. The receiver must retain the verified artifact; the sender reports batch failure/unknown peer outcome and must not auto-retry the item, because that could create duplicates. Cross-device reconciliation is deferred to CP-007.
- The queue is not durable. Process death, device reboot, Force Stop, long-background execution and resume are not guaranteed in CP-004.
- The destination provider may leave an incomplete new artifact if it cannot confirm deletion; the UI must disclose this and must never label it completed.
- Group transfer and APK/split-APK transfer remain separate checkpoints and must not leak into this implementation.

## 12. Platform references

- Android `ACTION_OPEN_DOCUMENT`, `EXTRA_ALLOW_MULTIPLE` and `ClipData` result handling: https://developer.android.com/guide/components/intents-common
- Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE`, destination selection and Android 11+ directory restrictions: https://developer.android.com/training/data-storage/shared/documents-files
- `DocumentsContract` tree and child-document operations: https://developer.android.com/reference/android/provider/DocumentsContract
- Existing single-file security, TLS, framing, streaming and storage baseline: [ADR-0007](ADR-0007-SECURE-ONE-TO-ONE-FILE-TRANSFER.md)



## 13. Protocol concurrency and terminal-boundary addendum

This section is normative and clarifies the message ownership and completion rules above. If an earlier paragraph can be read as allowing competing stream readers, an unbounded abort response, or an unsynchronized batch terminal state, this section takes precedence.

### 13.1 Single-reader/single-writer ownership

1. Each direction of the authenticated TLS channel has exactly one frame reader. No two coroutines may concurrently call the frame decoder on the same input stream.
2. The sender runs one structured, session-owned inbound control reader independently of the source-reading/data-writing work. It routes CHUNK_ACK, ITEM_ABORT_REQUEST, BATCH_CANCEL and terminal responses to the batch state machine. It must remain able to process a remote abort/cancel while a ContentResolver read is blocking.
3. On a valid active-item abort request, the coordinator closes the currently active source stream before cancelling its source-reading job. Coroutine cancellation alone is not relied on to interrupt a blocking platform read.
4. All writes to each directional output stream are serialized through one writer owner. Data chunks, ITEM_ABORT, ITEM_TERMINAL_COMMIT, BATCH_CANCEL and terminal markers cannot be written concurrently or interleaved.
5. The receiver similarly has one inbound data/control frame reader for sender-to-receiver messages and one serialized reverse-direction writer for ACKs, rejection, abort requests and cancellation requests. UI callbacks never read or write protocol streams directly.
6. Reader/writer children belong to the batch coordinator's structured coroutine scope. On batch termination, the coordinator closes the active source first, cancels children, closes staging resources and the authenticated channel, then releases the P2P group. No detached/global coroutine is permitted.

### 13.2 Bounded abort response

The abort protocol in section 4.2 is interpreted as follows:

1. ITEM_ABORT_REQUEST is receiver-to-sender only. ITEM_ABORT is the sender-to-receiver ordered terminal marker. This avoids two peers independently writing competing abort markers.
2. Because the sender's inbound control reader runs independently, the sender can process a receiver abort request even while the source reader is blocked. The active source is closed; no new source read is started.
3. The sender may finish only data chunks already reserved in its existing four-chunk window, then writes exactly one ITEM_ABORT marker on the same sender-to-receiver stream after those frames. It must not reserve another chunk after processing the request.
4. The receiver stops staging the active item as soon as it requests abort. It continues decoding and discarding only well-formed, same-item data frames already in flight, with a strict cumulative ceiling of 256 KiB beyond the abort request, until the ordered ITEM_ABORT marker is read. The marker's final offset must equal the end offset of the last observed data frame.
5. The receiver then deletes the uncommitted stage and sends ITEM_ABORT_ACK. The sender sends ITEM_TERMINAL_COMMIT; the receiver validates that exact batch ID, item ID, ordinal, outcome and reason before allowing the next manifest.
6. A sender-originated source failure or sender cancellation writes ITEM_ABORT after all data frames already emitted. The receiver consumes those earlier frames in order, deletes the stage, acknowledges, and waits for the commit.
7. A missing marker, non-contiguous drain, more than 256 KiB of post-request data, duplicate/cross-item marker, failed stage cleanup, ACK timeout or commit mismatch terminates the whole batch. Never guess the next frame boundary.

### 13.3 Explicit batch completion handshake

Two additional v2 frame codes are reserved:

| Code | v2 message | Direction | Purpose |
|---:|---|---|---|
| 48 | BATCH_COMPLETE | Sender → receiver | Announces that all declared items have terminal outcomes and includes bounded outcome counts |
| 49 | BATCH_COMPLETE_ACK | Receiver → sender | Confirms that the receiver independently derived the same batch summary |

After all declared items have completed their receipt or terminal-commit exchange:

1. The sender derives the final item counts and sends BATCH_COMPLETE containing the batch ID, declared item count and counts of completed, rejected, cancelled and failed items. Counts must sum to the declared item count; NOT_STARTED is not allowed in a normally completed batch.
2. The receiver independently derives its item outcomes and validates the counts and batch ID. A mismatch is batch-fatal.
3. The receiver returns BATCH_COMPLETE_ACK with the same bounded summary. The sender marks COMPLETED_SUCCESSFULLY or COMPLETED_WITH_ERRORS only after validating this acknowledgement. The receiver may mark its local batch terminal after sending the acknowledgement; a subsequent transport failure is reported separately and does not invalidate already verified files.
4. The completion handshake is finite-timeout and is the only normal path to a completed batch result. Reaching the last item ordinal alone is not sufficient.
5. No item manifest, data frame or second completion frame is legal after BATCH_COMPLETE.

### 13.4 Cancellation versus final completion race

1. The sender's serialized writer/state owner linearizes the final choice between emitting BATCH_CANCEL and emitting BATCH_COMPLETE; it must never emit both for one batch.
2. A receiver-initiated cancellation is effective only when the sender has not committed BATCH_COMPLETE. If the sender processes cancellation first, it returns BATCH_CANCEL_ACK and the batch becomes cancelled. If BATCH_COMPLETE has already been committed, the receiver treats a crossing late cancellation request as too late, validates the completion summary and returns BATCH_COMPLETE_ACK.
3. The receiver reports a user-requested cancellation as confirmed only after receiving BATCH_CANCEL_ACK. If completion wins the race, the UI reports that the batch completed before cancellation took effect; it must not relabel completed files as cancelled.
4. For a whole-batch cancellation while data is in flight, the sender stops new source reads and writes the cancellation acknowledgement after already-emitted data frames. The receiver drains/discards no more than the same bounded four-chunk window before acknowledging/tearing down. No item after the active item is started.
5. Tests must cover both orderings of the cancellation/completion race, remote cancellation during a blocking source read, cancellation at a full and partial data window, and loss of either terminal acknowledgement.

