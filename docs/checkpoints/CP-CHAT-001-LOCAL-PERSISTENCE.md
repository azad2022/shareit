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

- **Exact-head source:** commit `8e0c57761a26500aea00ed73770825e8d2337a0f`; branch head was re-read from GitHub after the push.
- **Domain JVM tests:** GitHub Actions run #407 executed `:core:domain:testDebugUnitTest`. The dedicated `ChatMessageRulesTest` report records **8 tests, 0 failures, 0 errors, 0 skipped**. The exact-4-KiB boundary cases for Persian, emoji and ASCII passed.
- **Room/Keystore instrumentation:** the test APK and KSP sources compiled on API 26, but `:core:data:connectedDebugAndroidTest` did not produce a test report in run #407. The combined instrumentation job stopped at the earlier storage test task. Therefore Room, Keystore, outbox quota, database-reopen and ciphertext-tamper instrumentation are **not verified**.
- **API 26 unrelated instrumentation failure:** both existing `AndroidPeyvandDestinationPublisherTest` cases failed before exercising their assertions with `NoSuchMethodError: UiAutomation.grantRuntimePermission` at test line 64. This is in the existing `:core:storage` suite, not the chat module.
- **API 36 instrumentation:** no tests ran; Gradle project configuration failed during dependency resolution for multiple Maven artifacts in the runner.
- **Build/lint:** the general CI job failed on pre-existing shared-branch issues outside CP-CHAT-001: unresolved `AWAITING_DESTINATION` and `AWAITING_DESTINATION_TREE` references in `MainActivity.kt`, and an unguarded API-29 `MediaStore.Downloads.getContentUri` call while minSdk is 26. These files belong to the transfer/app baseline and are not changed by this checkpoint.
- **APK:** no Debug APK was produced by run #407.
- **Physical devices:** not tested for this chat persistence slice.
- **Security review:** source-level review is ongoing; no security certification or release readiness is claimed.

The instrumentation Gradle command now uses `--continue` so independent module test tasks can still execute and publish reports when another module's instrumentation task fails. This is test-runner isolation, not a suppression of failures.

## 6. Risks and open decisions

- Current PEYVAND peer certificates are ephemeral per session. Persistent peer identity and safe re-pairing remain future ADR/implementation work.
- Current TLS byte channel is exclusive to the transfer coordinator. A future chat protocol must not contend for or hijack that stream; design a framed multiplexing/session-owner boundary first.
- Android Keystore hardware backing/StrongBox capability varies by device and is not assumed.
- Backup/device-transfer exclusions must be tested on physical supported OEM devices; manifest rules alone are not accepted as evidence.
- Local retention, history quota, manual local deletion, forget-peer semantics and pending-message cancellation remain open. No automatic history pruning is implemented.
- Message ordering/concurrency across both offline devices is not implemented; sender sequence fields are foundations for a later sync protocol.

## 7. Next gate

1. Re-run API 26/API 36 instrumentation with the updated `--continue` task execution so the isolated `:core:data:connectedDebugAndroidTest` result is captured even if storage or connectivity tests fail.
2. Fix any chat-specific Room/Keystore instrumentation failures. Do not treat a compiled instrumentation APK as a passed test.
3. Keep the shared transfer/app build blockers visible and isolated. They require a separately authorized CP-004/baseline repair and must not be hidden or silently patched in this checkpoint.
4. Review local database/key-loss and backup/device-transfer behavior on representative physical devices.
5. Only after CP-CHAT-001 is verified, proceed to the separately reviewed stable-peer-identity, re-pairing and authenticated channel-multiplexing milestone.
