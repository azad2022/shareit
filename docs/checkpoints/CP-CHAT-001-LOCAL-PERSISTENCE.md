# CP-CHAT-001 — Local encrypted message persistence

- **Status:** `IN_PROGRESS`
- **Branch:** `feature/cp-chat-001-local-persistence`
- **Base:** CP-004 branch code at `cf80930bd557a3a6feae0d6d8ada0e200f2fb6c8`
- **Product authorization:** Explicitly granted by the product owner on 2026-11-13 for this local-persistence implementation slice only.
- **Objective:** Persist one-to-one text messages exclusively on participating Android devices, encrypt message bodies at rest, and atomically maintain a durable outgoing outbox. No network or UI implementation is included.
- **Latest implementation head:** `2bb8cd51082428b4c1223cf38260d2672ca0dfb4` (CI-validated; documentation updates follow this code SHA).

## 1. Implemented scope

- Android-free chat domain model and repository contract in `:core:domain`.
- Strict SHA-256 public-key fingerprint value type; display names, discovery tokens and IP/MAC addresses are not accepted as peer identity.
- Message input validation: non-empty text, valid surrogate pairs, no NUL/forbidden control characters, maximum 4 KiB UTF-8 payload.
- Room entities/DAOs/database in `:core:data` for local conversation metadata, encrypted message payloads, outbox records and crypto-key version state.
- AES-256-GCM message-body encryption via a non-exportable Android Keystore key. Authenticated associated data binds peer fingerprint, conversation/message IDs, direction, sender sequence, observed peer sequence and creation timestamp.
- Atomic outgoing enqueue transaction: allocate local sequence, encrypt, insert message row, insert outbox row and advance sequence in one Room transaction. Repository returns only after transaction completion.
- Idempotent local transition to Delivered after the synchronization layer validates a durable receiver receipt; no socket-write shortcut exists in this repository.
- Initial bounded outbox quota: 1,024 pending outgoing messages per peer. This is an engineering guardrail; product retention/deletion policy remains separate and is not silently implemented.
- Android manifest sets `allowBackup=false` and references legacy backup rules plus Android 12+ data-extraction rules. The current rules exclude root, files, databases, shared preferences and external app data for both cloud backup and device transfer; this is app-wide, not chat-only.
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

- Domain JVM tests (`ChatMessageRulesTest`, 8 cases): fingerprint normalization/validation, Persian and multiline acceptance, blank/oversized input rejection, exact UTF-8 limit, forbidden controls and malformed surrogate rejection.
- Room/Keystore instrumentation (`RoomChatRepositoryTest`, 16 cases per API): encrypted payload storage, database reopen/outbox persistence, monotonic sequences, receipt behavior, wrong-peer isolation, missing-key fail-closed behavior, oversized input rejection, ciphertext/metadata/peer/sequence tampering, unsupported key version, missing crypto metadata, and temporary plaintext byte-array cleanup on success and exception.
- Adjacent Android instrumentation: storage publication and mTLS loopback suites on API 26 and API 36.
- CI matrix: Android API 26 and API 36 emulator instrumentation. Emulator evidence is not physical-device evidence.

## 5. Validation evidence

### 5.1 Historical baseline — CI run #408

- Earlier exact-head source SHA: `0d9a927e878e5d1af92a6f6de18b6852dec00172`; workflow [#408](https://github.com/azad2022/shareit/actions/runs/43445471038) failed due to unrelated app/Storage baseline failures. Its results are historical and superseded for current acceptance by run #43477138714 below.
- At that point the chat-specific suites were 8/8 domain and 9/9 Room tests per API, but the general build and API 26 Storage instrumentation were red. Those old failures were subsequently repaired on this isolated branch; do not treat #408 as the current result.

### 5.2 Latest exact-head validation — CI run #43477138714

- **Source SHA:** `2bb8cd51082428b4c1223cf38260d2672ca0dfb4`, exact current code head at time of validation.
- **Workflow:** [GitHub Actions #43477138714](https://github.com/azad2022/shareit/actions/runs/43477138714) — overall conclusion **success**. All three jobs passed: Build/unit tests/lint, Android API 26 instrumentation, Android API 36 instrumentation.
- **JVM unit tests:** **148 passed, 0 failed, 0 skipped** across the configured domain, storage and transfer test tasks. `ChatMessageRulesTest` specifically passed **8/8**.
- **Chat Room/Keystore instrumentation:** `RoomChatRepositoryTest` passed **16/16** on API 26 and independently **16/16** on API 36; both reports show zero failures, errors or skips.
- The five new tests added on SHA `2bb8cd5` passed on both API levels:
  - `changingSenderSequenceFailsIntegrityCheck`
  - `missingCryptoMetadataWithPersistedMessagesFailsClosed`
  - `unsupportedCryptoKeyVersionFailsClosed`
  - `temporaryPlaintextBytesAreErasedAfterSuccessfulUse`
  - `temporaryPlaintextBytesAreErasedWhenUseFails`
- **Storage instrumentation:** `AndroidPeyvandDestinationPublisherTest` passed **2/2** on API 26 and **2/2** on API 36. This resolves the earlier API 26 test-runner failure on this branch; it does not constitute a physical OEM storage-provider test.
- **mTLS instrumentation:** API 26 reports 4 tests, 0 failures and 1 conditional skip (TLS 1.3 when the runtime does not support it); API 36 reports **4/4** with no skip. TLS 1.2, Keystore ECDSA signing and pinned-peer rejection passed on both.
- **Lint:** 0 errors. The combined reports contain 16 warnings: 13 in `:app`, one in `:core:connectivity`, one in `:core:designsystem`, and one in `:core:storage`. `:core:data`, `:core:domain` and `:core:transfer` report no lint issues. Relevant warnings include the custom `X509TrustManager` review warning, package-visibility guidance, target-SDK/manifest-attribute warnings, and an obsolete library lint-check warning. CI success does not mean these warnings were waived or proven harmless.
- **Debug APK:** produced successfully; APK size **14,720,844 bytes**. SHA-256: `6944412768a0af66945888adaa68b98e2c413423d94c1956fd70699716c2a33c`. This is a standard Debug build, not a Release artifact.
- **Artifacts:** [Debug APK ZIP — artifact 14063403573](https://github.com/azad2022/shareit/actions/runs/43477138714/artifacts/14063403573); [API 26 instrumented reports — artifact 14064365761](https://github.com/azad2022/shareit/actions/runs/43477138714/artifacts/14064365761); [API 36 instrumented reports — artifact 14063811912](https://github.com/azad2022/shareit/actions/runs/43477138714/artifacts/14063811912); [build/unit/lint reports — artifact 14064585146](https://github.com/azad2022/shareit/actions/runs/43477138714/artifacts/14064585146).
- **Environment:** Android instrumentation ran on CI emulators at API 26 and API 36. No physical handset, OEM Keystore, physical backup/device-transfer or real two-device chat synchronization test was part of this workflow.
- **Evidence integrity:** the uploaded API 26/API 36 XML reports were inspected directly. The reported Room, Storage and connectivity test counts above come from those XML files, not estimates from job status alone. The APK sidecar checksum was also independently recalculated from the extracted APK and matched.

### 5.3 Acceptance interpretation

The automated local-persistence and emulator acceptance cases passed on the exact SHA above. CP-CHAT-001 nevertheless remains `IN_PROGRESS`: physical-device Keystore/key-invalidation and backup/device-transfer behavior, complete security review, and data-lifecycle decisions are still open. No release readiness or full end-to-end chat claim is made.

## 6. Risks and open decisions

- Current PEYVAND peer certificates are ephemeral per session. Persistent peer identity and safe re-pairing remain future ADR/implementation work.
- Current TLS byte channel is exclusive to the transfer coordinator. A future chat protocol must not contend for or hijack that stream; design a framed multiplexing/session-owner boundary first.
- Android Keystore hardware backing/StrongBox capability varies by device and is not assumed.
- Backup/device-transfer exclusions must be tested on physical supported OEM devices; manifest rules alone are not accepted as evidence.
- Local retention, history quota, manual local deletion, forget-peer semantics and pending-message cancellation remain open. No automatic history pruning is implemented.
- Message ordering/concurrency across both offline devices is not implemented; sender sequence fields are foundations for a later sync protocol.

## 7. Next gate

1. Perform repeatable physical-device validation on at least two Android OEMs. Record exact handset model, Android/API level, build fingerprint, installed APK SHA-256 and test result.
2. On physical devices, verify Keystore persistence through process death/restart, behavior after key invalidation or loss, and fail-closed recovery. Do not claim StrongBox/hardware backing unless queried and evidenced on each device.
3. Verify cloud-backup and device-to-device-transfer exclusion behavior on physical devices for the supported target-SDK/OEM matrix. Current exclusions are app-wide and must not be mistaken for chat-only rules.
4. Complete a focused review of encryption/key lifecycle, plaintext metadata exposure, memory lifetime, Room WAL/journal handling, and the `CustomX509TrustManager` lint warning. The temporary byte-array wipe is best-effort only; immutable `String` values and runtime/provider copies cannot be guaranteed erased.
5. Obtain product decisions for local history retention, storage quota, local deletion, and “forget peer” behavior. No automatic history pruning or silent deletion is implemented.
6. Keep stable peer identity, re-pairing, synchronization protocol, channel multiplexing and Compose chat UI outside this checkpoint. They require their own reviewed ADR/milestone and explicit owner authorization.

CP-CHAT-001 may be considered for acceptance only after the physical validation and review gates above have reproducible evidence. Do not change status to `PASSED` based solely on emulator CI.
