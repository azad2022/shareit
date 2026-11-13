# ADR-0004: Local Chat Data Protection, Backup, and Deletion

- **Status:** Proposed — privacy and product-owner decisions required
- **Date:** 2026-11-13
- **Scope:** Local chat database, outbox, peer identity references, and deletion behavior

## Context

The selected chat model intentionally persists private conversations on-device. This creates a local data-protection responsibility even though no chat server exists. Android backup, reinstall, data clearing, key loss, peer removal, and local deletion can affect confidentiality and recoverability.

App-private storage protects access through Android's application sandbox but must not be described as equivalent to database-level encryption.

## Proposed defaults for review

1. Encrypt message history and outbox data at rest using a reviewed, maintained implementation. Do not invent cryptography or choose an algorithm/library before a dependency and platform review.
2. Protect encryption keys through Android Keystore-backed key wrapping where supported. Define behavior when a key becomes invalid or unavailable.
3. Exclude chat history, pending outbox content, and identity secrets from cloud backup by default. Treat cloud backup and device-to-device (D2D) transfer as separate backup channels with separately reviewed rules. Do not assume that `android:allowBackup="false"` alone blocks D2D migration on every Android 12+ device/OEM combination.
4. Do not back up identity private keys as ordinary app data. Define a deliberate D2D history-migration policy separately; restored history must not silently inherit trust in a device identity whose private key was not migrated securely.
5. Explain to users that clearing app data, uninstalling, losing the device, or invalidating the required key may permanently remove local history and queued messages.
6. Keep message deletion local in the first release. Do not implement “delete for everyone” without a separate tombstone and conflict-resolution design.
7. Define a bounded retention/storage policy and show users when cleanup is needed. Never silently delete unsent messages to reclaim space.
8. On “forget peer”, stop future synchronization immediately. Retain or remove local history only according to an explicit user choice and approved retention policy.

These are recommendations, not approved product decisions.

## UX implications

- Settings should contain a “Trusted devices” area with identity status and a deliberate forget action.
- Before forgetting a peer, explain whether its local conversation and pending messages will be retained or deleted.
- If encryption keys become unavailable, show that history cannot be opened; do not silently initialize a new key and present an empty conversation as if no data existed.
- Before clearing history, distinguish local deletion from deletion on the other device.
- A pending unsent message should be highlighted in any cleanup confirmation.

## Open questions requiring owner decision

1. Should clearing/forgetting a conversation retain its local messages by default, delete them, or ask every time?
2. What history retention limit is acceptable (unlimited, time-based, or storage quota)?
3. Confirm whether exclusion from Android cloud backup is the desired default.
4. Confirm whether users need an encrypted manual export/backup feature in a later release.
5. Should local deletion of a pending unsent message cancel it permanently in the outbox?

## Android platform references

- [Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup) — Auto Backup coverage, include/exclude rules, and the distinction between cloud backup and device-to-device transfer.
- [Security recommendations for backups](https://developer.android.com/privacy-and-security/risks/backup-best-practices) — guidance for excluding particularly sensitive data or requiring encrypted backup behavior.

Important implementation note: Android documents that for apps targeting API 31+ on Android 12+, `data-extraction-rules` can distinguish `cloud-backup` from `device-transfer`; on some OEM devices, `allowBackup=false` may disable cloud backup without disabling D2D transfer. The exact behavior must be verified against the eventual target SDK and physical-device matrix.

## Acceptance evidence before implementation

- Data-flow and threat model covering database, WAL/journal files, temporary files, backups, and keys.
- Verification that backup/restore does not orphan or silently replace identity keys.
- Tests for key invalidation, device restore, app-data clear, peer forgetting, and pending-message cleanup.
- User-facing privacy copy reviewed in Persian and English before enabling chat.
