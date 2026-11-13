# ADR-0016: Local Chat Data Protection, Backup, and Deletion

- **Status:** Partially accepted — no server/cloud/external retention is a confirmed product constraint; local encryption and exact lifecycle policies remain proposed
- **Date:** 2026-11-13
- **Scope:** Local chat database, outbox, peer identity references, and deletion behavior

## Context

The selected chat model intentionally persists private conversations on-device. This creates a local data-protection responsibility even though no chat server exists. Android backup, reinstall, data clearing, key loss, peer removal, and local deletion can affect confidentiality and recoverability.

App-private storage protects access through Android's application sandbox but must not be described as equivalent to database-level encryption.

## Confirmed product boundary

1. PEYVAND will not operate or depend on a central server, backend, hosted database, cloud message store, push service, account service, or relay for chat.
2. PEYVAND/the product operator will not receive or retain chat messages, chat history, message metadata, contact lists, or queued content on external infrastructure.
3. Chat data exists only on the participating Android devices: the sender's private app storage for its local history/outbox, and the recipient's private app storage after durable receipt.
4. The only synchronization path is the direct local Wi-Fi Direct connection after peer authentication. No Android Auto Backup, cloud backup, app-managed export, or automatic device-migration path is included in the initial chat scope.
5. The implementation must explicitly exclude chat history, outbox data, peer metadata, and identity material from Android backup/device-transfer channels where the relevant Android version and target SDK expose those controls. Verify effective behavior on physical devices and supported OEMs; do not assume one manifest flag is sufficient.
6. If a user clears app data, uninstalls PEYVAND, loses the device, or loses an essential key, local history and unsynchronized messages may be permanently lost. No server-side copy exists to restore them.

## Proposed local data-protection measures

1. Encrypt message bodies at rest using a reviewed, maintained AEAD implementation. Treat database metadata protection as a separate requirement; do not imply that field-level payload encryption is equivalent to full database/file encryption. Do not invent cryptography or choose an algorithm/library before dependency and platform review.
2. Protect encryption keys through Android Keystore-backed key protection where supported. Detect actual device capability; do not assume hardware-backed or StrongBox protection on all devices.
3. Keep deletion local in the first release. Do not implement “delete for everyone” without a separate tombstone and conflict-resolution design.
4. Define a bounded retention/storage policy and show users when cleanup is needed. Never silently delete unsent messages to reclaim space.
5. On “forget peer”, stop future synchronization immediately. Retention or deletion of that peer's local history still needs an explicit product decision.

The no-server/no-external-retention boundary is accepted from the product owner's clarification. Message-body encryption is implemented in CP-CHAT-001 and CI-verified on API 26 and API 36 emulators; physical-device verification remains open. Full database metadata encryption, retention, key-loss, and local deletion details remain proposals until reviewed.

## UX implications

- Settings should contain a “Trusted devices” area with identity status and a deliberate forget action.
- Before forgetting a peer, explain whether its local conversation and pending messages will be retained or deleted.
- If encryption keys become unavailable, show that history cannot be opened; do not silently initialize a new key and present an empty conversation as if no data existed.
- Before clearing history, distinguish local deletion from deletion on the other device.
- A pending unsent message should be highlighted in any cleanup confirmation.

## Open questions requiring owner decision

1. Should clearing/forgetting a conversation retain its local messages by default, delete them, or ask every time?
2. What history retention limit is acceptable (unlimited, time-based, or storage quota)?
3. Should local deletion of a pending unsent message cancel it permanently in the outbox?
4. What supported Android API/device matrix will be used to verify that OS-managed cloud backup and device-to-device migration do not export chat data?

## Android platform references

- [Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup) — Auto Backup coverage, include/exclude rules, and the distinction between cloud backup and device-to-device transfer.
- [Security recommendations for backups](https://developer.android.com/privacy-and-security/risks/backup-best-practices) — guidance for excluding particularly sensitive data or requiring encrypted backup behavior.

Important implementation note: Android documents that for apps targeting API 31+ on Android 12+, `data-extraction-rules` can distinguish `cloud-backup` from `device-transfer`; on some OEM devices, `allowBackup=false` may disable cloud backup without disabling D2D transfer. The exact behavior must be verified against the eventual target SDK and physical-device matrix.

## Acceptance evidence before implementation

- Data-flow and threat model covering database, WAL/journal files, temporary files, backups, and keys.
- Verification that backup/restore does not orphan or silently replace identity keys.
- Tests for key invalidation, app-data clear, peer forgetting, and pending-message cleanup.
- Physical-device verification that app chat data is excluded from applicable Android cloud-backup and device-to-device migration paths for the supported API/target-SDK/OEM matrix.
- User-facing privacy copy reviewed in Persian and English before enabling chat.


## CP-CHAT-001 implementation evidence — 2026-11-13

The following records implementation evidence, not a blanket acceptance of every proposed control in this ADR:

- Message bodies are encrypted using AES-256-GCM (`AES/GCM/NoPadding`) with a 12-byte nonce and a 128-bit authentication tag. The key is created in Android Keystore as a non-exportable AES-256 key; hardware-backed storage and StrongBox are not guaranteed by this implementation.
- Authenticated associated data binds the peer fingerprint, conversation ID, message ID, direction, local sender sequence, observed peer sequence and creation timestamp. Tests demonstrate rejection after ciphertext, timestamp, sender-sequence or peer-binding modification.
- Room stores encrypted body bytes. Message/conversation identifiers, peer fingerprint, direction, sequence values, timestamp and delivery state remain plaintext metadata; this is field-level body encryption, not full-database encryption.
- The Room transaction commits the outgoing message row, outbox row and next sequence together. CI run [#43477138714](https://github.com/azad2022/shareit/actions/runs/43477138714), exact SHA `2bb8cd51082428b4c1223cf38260d2672ca0dfb4`, passed the 16 Room/Keystore tests on both API 26 and API 36 emulators.
- Temporary plaintext byte arrays are zero-filled in a `finally` block after encryption/decryption processing. This is best-effort hygiene only; it cannot guarantee erasure of immutable Kotlin/Java `String` objects, decoder/runtime copies, or provider-internal copies.
- Missing Keystore key, missing crypto metadata when messages exist, and unsupported crypto-key version fail closed in the tested repository paths. Physical key invalidation and OEM Keystore behavior are not yet verified.
- Current manifest backup settings disable backup and exclude app root, files, databases, shared preferences and external app data from both the legacy backup configuration and Android 12+ cloud/device-transfer rules. These rules affect app data broadly, not only chat. Their effective behavior still needs physical-device verification; no assumption is made that emulator CI proves OEM migration behavior.

The CI run succeeded and produced a Debug APK, but the checkpoint remains `IN_PROGRESS` until the physical-device and data-lifecycle gates listed above are completed. See [CP-CHAT-001](../checkpoints/CP-CHAT-001-LOCAL-PERSISTENCE.md) for the full test evidence and open risks.
