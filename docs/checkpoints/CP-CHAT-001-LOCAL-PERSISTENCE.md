# CP-CHAT-001 — Local encrypted message persistence

- **Status:** `IN_PROGRESS`
- **Branch:** `feature/cp-chat-001-local-persistence`
- **Base:** CP-004 branch code at `cf80930bd557a3a6feae0d6d8ada0e200f2fb6c8`
- **Product authorization:** Explicitly granted by the product owner on 2026-11-13 for this local-persistence implementation slice only.
- **Objective:** Persist one-to-one text messages exclusively on the participating Android devices, encrypted at rest, with an atomic durable outgoing outbox. No network or UI implementation is included in this checkpoint.

## 1. Implemented scope

- Android-free chat domain model and repository contract in `:core:domain`.
- Strict SHA-256 public-key fingerprint value type; display names, discovery tokens and IP/MAC addresses are not accepted as peer identity.
- Message input validation: non-empty text, valid surrogate pairs, no NUL/forbidden control characters, maximum 4 KiB UTF-8 payload.
- Room entities/DAOs/database in `:core:data` for local conversation metadata, encrypted message payloads, outbox records and crypto-key version state.
- AES-256-GCM message-body encryption via a non-exportable Android Keystore key. Authenticated associated data binds peer fingerprint, conversation/message IDs, direction, sender sequence, observed peer sequence and creation timestamp.
- Atomic outgoing enqueue transaction: allocate local sequence, encrypt, insert message row, insert outbox row and advance sequence in one Room transaction. Repository returns only after transaction completion.
- Idempotent local transition to Delivered after the synchronization layer validates a durable receiver receipt; no socket-write shortcut exists in this repository.
- Initial bounded outbox quota: 1,024 pending outgoing messages per peer. This is an engineering guardrail; product retention/deletion policy remains separate and is not silently implemented.
- Android manifest disables backup and adds exclusions for legacy cloud backup plus Android 12+ cloud-backup and device-transfer rules.
- App exposes the local ChatRepository lazily. Accessing the repository does not open a socket, discover peers, or connect to any external system.
- CI extended to run data-module unit/lint and Android instrumentation tests on API 26 and API 36; reports and Room schemas are included in test artifacts.

## 2. Explicitly excluded

- Compose Chats list or Conversation screens.
- Incoming network message protocol or message synchronization.
- Persistent device key identity, trust lifecycle, QR/SAS redesign or re-pairing.
- Multiplexing the current mTLS channel. The current channel is exclusively claimed by the transfer coordinator and its peer certificate is session-scoped.
- Group chat, read receipts, voice/video, message deletion UI, synchronized deletion, cloud backup, D2D migration, manual export, remote recovery, server, relay or backend.

Do not present CP-CHAT-001 as a functioning end-to-end chat feature until the identity, sync protocol, channel-ownership and UI milestones pass.

## 3. Acceptance criteria

1. The domain rejects blank, oversized, malformed-Unicode and forbidden-control messages before persistence.
2. A peer identity is accepted only as a normalized 64-character SHA-256 hex fingerprint.
3. Message body bytes are not stored in plaintext in the Room payload column.
4. AES-GCM authentication failure is surfaced as a data-integrity failure; corrupted ciphertext is never returned as text.
5. Message row, outbox row and next local sequence commit atomically.
6. Local sender sequence is monotonic across messages and database reopen.
7. Reopening the Room database preserves the pending queue and message body while the Keystore key remains available.
8. An authenticated receipt can mark only a message belonging to the expected peer/conversation as delivered; the outbox removal and message status update are transactional.
9. Missing/invalidated Keystore key with existing crypto metadata fails closed; no replacement key is silently generated.
10. Pending outbox is bounded at 1,024 messages per peer; a full queue does not overwrite or delete existing messages.
11. Cloud backup and Android device-transfer exclusion rules are declared; physical OEM verification remains a separate evidence requirement.
12. No server, backend, account, relay, cloud message store, remote API or external chat retention is introduced.

## 4. Automated tests added

- Domain JVM tests: fingerprint normalization/validation, Persian/multiline acceptance, blank message rejection, UTF-8 byte-size limit, forbidden control characters, and malformed surrogate rejection.
- Android instrumentation tests in `:core:data`: encrypted payload storage, database reopen/outbox persistence, sequence monotonicity, receipt behavior, wrong-peer isolation, missing-key fail-closed behavior, oversized message rejection, and ciphertext tamper detection.
- CI matrix: Android API 26 and API 36 instrumentation.

## 5. Validation evidence

- **Local Gradle:** Not run. The current execution environment failed to resolve github.com via DNS, so the repository could not be cloned for local Gradle execution.
- **GitHub Actions:** workflow updated to include the new data-module tasks. Intermediate runs #373–#390 were generated while commits were being pushed; they were cancelled or reference earlier source SHAs. No run is yet accepted as exact-head evidence for this checkpoint.
- **APK:** Not produced or claimed for the final CP-CHAT-001 source SHA.
- **Physical devices:** Not tested. The Galaxy A12 / ASUS device pair has not been used for this chat persistence slice.
- **Security audit:** code-level review still required; no security certification or release readiness is claimed.

## 6. Risks and open decisions

- Current PEYVAND peer certificates are ephemeral per session. Persistent peer identity and safe re-pairing remain future ADR/implementation work.
- Current TLS byte channel is exclusive to the transfer coordinator. A future chat protocol must not contend for or hijack that stream; design a framed multiplexing/session-owner boundary first.
- Android Keystore hardware backing/StrongBox capability varies by device and is not assumed.
- Backup/device-transfer exclusions must be tested on physical supported OEM devices; manifest rules alone are not accepted as evidence.
- Local retention, history quota, manual local deletion, forget-peer semantics and pending-message cancellation remain open. No automatic history pruning is implemented.
- Message ordering/concurrency across both offline devices is not implemented; sender sequence fields are foundations for a later sync protocol.

## 7. Next gate

1. Run exact-head CI for unit tests, lint, Debug APK, Room/KSP compilation and Android instrumentation API 26/36.
2. Fix every compile/test/lint issue before marking persistence acceptance as passed.
3. Review the local database/key-loss and Android backup/device-transfer behavior.
4. Design and approve stable peer identity, re-pairing and authenticated sync-channel multiplexing before network synchronization.
