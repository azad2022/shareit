# ADR-0009 — Secure multi-item one-to-one transfer

- **Status:** Accepted for CP-004 implementation; interoperability, failure-path and physical-device validation remain mandatory.
- **Date:** 2026-11-07
- **Scope:** One sender, one receiver, one authenticated Wi-Fi Direct/mTLS session, and an ordered batch of 2–32 user-selected generic content files. Exactly one selected file continues to use the existing version-1 single-file protocol. No group transfer, multi-sender, APK/split-APK, resume, background guarantee or fallback transport.

## Context

CP-003C provides a working version-1 one-file protocol over an exclusively claimed authenticated TLS channel. Its manifest, consent, chunk, ACK, completion and receipt messages are bound to one item. The coordinator terminates the channel after that item. Wrapping `sendOne()` or `receiveOne()` in a loop would be unsafe: terminal messages and resource cleanup currently close the entire session, and a failed item would leave the peers without an unambiguous point at which to continue.

The existing source adapter is URI-only. `openSource()` obtains metadata without opening the provider InputStream; `openStream()` is called after ACCEPT. The receiver stages privately, verifies length/SHA-256, publishes through SAF, reads the destination back, and only then emits the authenticated receipt. These properties are preserved.

## Decisions

### 1. Scope and queue limits

1. CP-004 supports 2–32 generic `content://` items from one sender to one receiver. `InstalledPackage` sources are rejected; APK and split-APK handling belongs to CP-005.
2. Exactly one item uses the existing version-1 flow. Two or more items use the new version-2 batch flow. There is no automatic downgrade or plaintext fallback. A peer that does not support version 2 fails closed with a typed unsupported-version result; the user may retry as a one-file transfer using version 1.
3. The selected order is captured once and is immutable for the batch. The sender and receiver process items strictly in that order. At most one source handle/stream, one receive staging sink, one publication operation and one item data window may be active at a time.
4. The maximum of 32 is derived from the 16 KiB control-frame ceiling: at the existing per-item manifest maxima, 32 length-prefixed entries fit below that limit with the batch envelope. The codec must still check the actual encoded byte count before writing and reject over-limit manifests before allocation.
5. This checkpoint guarantees deterministic order and state during the live operation, not recovery after process death. Durable queue persistence, resume and reconnect belong to CP-007.

### 2. Receiver-consent policy

1. Use two explicit receiver authorization steps. First, the sender transmits a metadata-free `BATCH_OFFER` containing only a random batch UUID. Before the manifest is sent, the receiver must approve disclosure of the file list. Only after a matching `BATCH_OFFER_ACCEPTED` does the sender send the manifest. The receiver then sees a batch-level consent screen with explicit per-item checkboxes. Only selected item IDs may proceed; an empty selection cannot be accepted, and rejecting the list is a distinct action.
2. The first decision protects metadata confidentiality; the second decision authorizes content transfer for the explicitly selected items. This avoids repeated per-file dialogs while keeping the receiver in control. Permission to view metadata is not consent to receive any file.
3. The decision is bound to the session ID, random batch ID, exact manifest digest and manifest order. The sender validates the batch ID/digest and rejects duplicate, unknown or out-of-range item selections. Unselected items are marked REJECTED and never receive an ITEM_START.
4. A manifest contains only bounded display metadata (item ID, sanitized display name, normalized MIME type and optional declared size). It contains no source URI, filesystem path, content bytes or precomputed digest. No manifest field is disclosed before the first metadata-consent decision. No source InputStream is opened and no private receiving stage is created until the second content-consent decision and the per-item readiness exchange.
5. A consent timeout, picker cancellation or rejection produces a non-success terminal result and closes the batch cleanly; it must not silently turn into acceptance.

### 3. Versioned batch control/data protocol

1. Keep version 1 frame meanings and the existing `SingleFileTransferProtocol` implementation intact. Add an explicitly versioned version-2 frame family for metadata-free batch offer/approval, batch manifest/decision, item start/readiness, item data/ACK, item completion/verified receipt, item failure/cancellation and batch completion/receipt.
2. The receiver dispatches the first authenticated application frame by its version and type. A version-1 MANIFEST is handed to the existing single-file receiver; a version-2 BATCH_OFFER is handed to the batch receiver. The first frame must not be lost or read twice. No v1 message is reinterpreted as a batch message. The offer contains only a random batch UUID; the manifest follows only after the receiver's matching approval frame.
3. A batch manifest carries a cryptographically random batch UUID and 2–32 ordered, uniquely identified item manifests. Its exact encoded bytes are SHA-256 hashed. The receiver's decision echoes the batch UUID and manifest digest and includes the selected item IDs (or a bounded bitmap whose bit positions are the immutable manifest order). The codec rejects duplicate IDs, invalid counts, malformed lengths, trailing bytes and oversized frames.
4. Every version-2 control message and batch data/ACK message is bound to the session ID and batch ID. Every item-scoped message also carries its item ID. Data chunks retain explicit contiguous offsets; ACKs identify the batch, item and next durably committed offset.
5. For each selected item, the order is: sender ITEM_START → receiver creates one bounded private stage → receiver ITEM_READY → sender opens that item's URI stream and sends bounded chunks → receiver syncs each maximum-four-chunk window before cumulative ACK → sender sends the authenticated actual-length/SHA-256 completion record → receiver verifies and seals staging → receiver publishes into the user-selected SAF tree and reads back the exact bytes → receiver sends the item's authenticated integrity receipt. No later item starts until the current item has reached a terminal outcome.
6. An item receipt binds batch ID, item ID, actual byte count and SHA-256. The terminal batch receipt binds the ordered list of terminal item outcomes/verified receipts. Sender-side batch completion is not reported until the batch receipt is validated.
7. Control frames remain at or below 16 KiB. Data chunks remain capped at 64 KiB and the outstanding window remains capped at four chunks/256 KiB. The receiver ACKs only after staging sync succeeds. These values are caps inherited from CP-003, not performance promises.

### 4. Failure, cancellation and continuation policy

1. A recoverable item-local failure (source provider open/read failure, item-specific stage allocation/write/sync failure, destination creation/write/read-back failure, or sender/receiver item cancellation) results in one authenticated item terminal outcome, cleanup of that item's stage/stream/descriptor, and continuation at the next manifest item if the channel remains synchronized.
2. The sender may cancel a queued item without opening its URI. Cancelling the active item closes the active source stream to interrupt a blocking ContentResolver read and exchanges an item-scoped cancellation outcome. The receiver discards any bounded in-flight tail, deletes that item's stage, and resumes at the next item boundary.
3. A receiver may reject any item in the initial selection decision. The receiver may abort the entire batch while it is active. No item can be retroactively marked accepted after the decision is committed.
4. Authentication/TLS failure, session or batch ID mismatch, unsupported version, malformed/truncated/oversized frames, invalid item order, invalid chunk sequence, channel EOF/timeout, or a failed control-frame write is fatal to the batch. Both peers stop; remaining not-started items are marked not transferred, and no partial item is published.
5. Cleanup is item-scoped and idempotent. A failed cleanup is observable and must not be reported as successful cleanup. A provider that cannot confirm deletion of a partial destination cannot be treated as a successful publication.
6. The aggregate batch state is derived from immutable per-item terminal states. Mixed outcomes are reported as partial completion; they are never collapsed into an all-success or all-failed result.

### 5. Destination and Android storage

1. The receiver selects one destination directory through the Storage Access Framework `ACTION_OPEN_DOCUMENT_TREE` flow before the first accepted item starts. The storage adapter creates each destination document through `DocumentsContract`, streams the verified stage using a bounded buffer, and performs a read-back byte-count/SHA-256 verification.
2. Remote file names are sanitized display names, never paths. The provider's own collision behavior is respected; existing user files must not be overwritten by constructing a path or by using a guessed filesystem location.
3. If document creation, copy, verification or confirmed partial cleanup fails for one item, report that item as FAILED and continue only if the TLS protocol remains synchronized.
4. SAF providers differ in supported operations and durability. The implementation must not claim universal atomic publication. Android 11+ restricts tree selection for the internal-storage root, reliable SD-card roots, Downloads, Android/data and Android/obb; the picker must be allowed to enforce those platform restrictions. See the official Android storage guide: https://developer.android.com/training/data-storage/shared/documents-files.

### 6. State and resource ownership

1. Keep the existing one-file coordinator and its lifecycle behavior for the version-1 route. Add a focused batch coordinator/protocol rather than expanding the single-file coordinator into a multi-responsibility class.
2. Model the queue and each item independently. Required item states are QUEUED, WAITING_FOR_RECEIVER, TRANSFERRING, VERIFYING, PUBLISHING, COMPLETED, REJECTED, CANCELLED and FAILED. Aggregate state must expose counts and whether the result is complete or partial.
3. Compose renders immutable snapshots only. ViewModels submit item/batch decisions. Protocol, source streams, staging sinks, socket/TLS channel and SAF publication remain in coordinator/domain/platform layers.
4. Use structured concurrency and bounded IO. No whole-file buffering, unbounded channels/queues, parallel file streams, global mutable state or `GlobalScope`. Every active URI stream is closed on item completion, cancellation, failure and coordinator shutdown. The TLS channel is closed once at batch completion/failure/cancellation.
5. No persistent resume is promised. An app/process stop may terminate the live batch; CP-007 defines durable recovery semantics.

## Consequences

- Two-file and larger batches require both devices to run a version-2-capable build. Single-file transfers retain the existing version-1 wire contract and code path.
- The receiver explicitly authorizes metadata disclosure before seeing filenames, then makes one batch-level content decision with per-item selection and independent terminal status.
- A chosen SAF directory avoids repeated save dialogs but is subject to document-provider limitations and Android's system-enforced directory restrictions.
- Sequential processing reduces memory, descriptor and disk-pressure risk. It does not claim parallel-file throughput.
- The version-2 dispatcher and all codecs require negative tests for malformed manifests, duplicate IDs, cross-batch/item frames, invalid decisions, out-of-order items and truncation.

## Validation gates

- Codec tests: metadata-free offer exact length/roundtrip, count 2/32/33, exact 16 KiB boundary, malformed UTF-8, duplicate IDs, malformed lengths, duplicate/unknown selections, manifest digest mismatch, unknown item IDs, wrong batch/session binding and trailing bytes.
- Consent-order tests: receiver rejection sends no metadata approval; sender does not transmit a manifest before a matching offer approval; item contents do not begin before explicit per-item acceptance and ITEM_READY.
- Protocol tests: version-1 sender/receiver regression; version-2 two/32 item flows; mixed MIME; arbitrary binary; zero-byte; large item; unknown length; Persian/Unicode names; extensionless names; item receipts and terminal batch receipt.
- Consent tests: no source stream or stage before consent; selected subset only; reject all; timeout; destination-tree cancellation.
- Item isolation: provider-open/read failure, low storage, stage/write/sync failure, destination creation/write/read-back failure, active/queued cancellation, cleanup and subsequent-item continuation.
- Resource tests: maximum one active source stream/stage, four-chunk cap, bounded memory, no descriptor/socket/staging leak, cancellation of a blocking source read.
- Build gates: all relevant unit tests, configured lint and Debug APK build.
- Physical tests: Galaxy A12 (SM-A125F, Android 12/API 31) and ASUS X017DA, both sender/receiver directions, exact build/APK digest, mixed files, zero-byte/large files, rejection, cancellation, low storage and provider failure. CI/emulator tests do not replace these physical tests.
