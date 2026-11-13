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

- **Exact-head source:** commit `0d9a927e878e5d1af92a6f6de18b6852dec00172` on `feature/cp-chat-001-local-persistence`; branch contents were fetched from GitHub before this update.
- **Exact-head workflow:** GitHub Actions run [#408](https://github.com/azad2022/shareit/actions/runs/43445471038) used this exact SHA. The overall workflow conclusion is **failure**; the results below distinguish the passing CP-CHAT tests from unrelated baseline failures.
- **Domain JVM tests:** `ChatMessageRulesTest` — **8 tests, 0 failures, 0 errors, 0 skipped**. The exact 4 KiB UTF-8 boundary cases for Persian, emoji and ASCII passed.
- **Room/Keystore instrumentation, API 26:** `RoomChatRepositoryTest` — **9 tests, 0 failures, 0 errors, 0 skipped**. This includes encrypted payload storage, database reopen/outbox persistence, monotonic sequences, peer isolation, authenticated receipt handling, bounded outbox behavior, missing-key fail-closed behavior, oversized-message rejection and ciphertext tamper detection.
- **Room/Keystore instrumentation, API 36:** the same **9 tests, 0 failures, 0 errors, 0 skipped**. The data instrumentation task completed on both API levels because the workflow uses Gradle `--continue`.
- **mTLS instrumentation:** API 36 — **4 tests passed, 0 failures, 0 skips**, including TLS 1.2, TLS 1.3, Android Keystore ECDSA signing and rejection of an incorrect peer fingerprint. API 26 — **3 passed, 0 failed, 1 conditional skip** for TLS 1.3 when unsupported by the runtime; TLS 1.2, Keystore signing and incorrect-fingerprint rejection passed.
- **Existing Storage instrumentation:** API 36 — **2 tests passed**. API 26 — both existing `AndroidPeyvandDestinationPublisherTest` cases failed before reaching their assertions with `NoSuchMethodError: UiAutomation.grantRuntimePermission` at test line 64. This is a test-platform/API compatibility failure in `:core:storage`, not a CP-CHAT data test.
- **General Build/Lint job:** failed outside this checkpoint. `:app:compileDebugKotlin` reports unresolved `AWAITING_DESTINATION` and `AWAITING_DESTINATION_TREE` references in `MainActivity.kt:106-107`; `:core:storage:lintDebug` reports an unguarded API-29 `MediaStore.Downloads.getContentUri` call at `AndroidPeyvandDestinationPublisher.kt:209` while minSdk is 26. These app/transfer baseline files are not modified by CP-CHAT-001.
- **API 26 instrumentation job:** overall failure is due to the two Storage tests above; the Room and connectivity instrumentation tasks did execute and produced reports.
- **API 36 instrumentation job:** completed successfully; Room, Storage and connectivity reports were uploaded.
- **Debug APK:** none was produced by run #408 because the general build job failed. Do not install or describe an artifact for this run.
- **Physical devices:** not yet tested for this local chat persistence slice. Emulator instrumentation does not verify OEM Keystore behavior, backup/device-transfer exclusions or physical-device key invalidation.
- **Security review:** source-level review remains distinct from Android runtime/physical-device validation. No security certification or release readiness is claimed.

The `--continue` change is effective: a failed independent Storage instrumentation task no longer prevents the Room chat and connectivity tasks from executing. It does not suppress failures or turn the overall API 26 job green.

## 6. Risks and open decisions

- Current PEYVAND peer certificates are ephemeral per session. Persistent peer identity and safe re-pairing remain future ADR/implementation work.
- Current TLS byte channel is exclusive to the transfer coordinator. A future chat protocol must not contend for or hijack that stream; design a framed multiplexing/session-owner boundary first.
- Android Keystore hardware backing/StrongBox capability varies by device and is not assumed.
- Backup/device-transfer exclusions must be tested on physical supported OEM devices; manifest rules alone are not accepted as evidence.
- Local retention, history quota, manual local deletion, forget-peer semantics and pending-message cancellation remain open. No automatic history pruning is implemented.
- Message ordering/concurrency across both offline devices is not implemented; sender sequence fields are foundations for a later sync protocol.

## 7. Next gate

1. **Completed:** run API 26/API 36 instrumentation with Gradle `--continue`; both isolated Room chat reports are now available.
2. **Completed for automated chat tests:** all 9 Room/Keystore instrumentation tests passed on API 26 and API 36, and all 8 chat domain tests passed. No chat-specific automated test failure remains in run #408.
3. Keep the unrelated app compile and Storage Lint/API 26 instrumentation failures visible. Do not patch those files from this checkpoint; they need a separately scoped CP-004/baseline repair.
4. Perform the remaining physical-device validation for Keystore key availability/invalidation and backup/device-transfer exclusion behavior on representative supported OEM devices. Record device/API/build evidence; do not infer this from emulator results.
5. Finish the source-level security review and record any remaining findings before considering CP-CHAT-001 complete.
6. Only after this checkpoint is accepted, move to the separately reviewed stable-peer-identity, re-pairing and authenticated channel-multiplexing milestone. That work is not authorized by CP-CHAT-001 and has not started.
