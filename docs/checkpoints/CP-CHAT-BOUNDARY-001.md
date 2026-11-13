# Checkpoint CP-CHAT-BOUNDARY-001 — No Server / Device-Only Chat Data

- **ID:** CP-CHAT-BOUNDARY-001
- **Objective:** Record the owner's clarification that PEYVAND chat is fully local and no server or external information retention is permitted.
- **Status:** IN_PROGRESS
- **Date:** 2026-11-13
- **Branch:** main
- **Scope:** Product requirements and documentation only; no application implementation.

## 1. Accepted product constraints

1. PEYVAND has no central server, backend, hosted database, account service, cloud message store, push service, or remote relay.
2. PEYVAND/the product operator does not receive or retain chat messages, conversation history, contact lists, queued content, or message metadata on external infrastructure.
3. The only persistent chat copies are in the private app storage of the participating phones: the sender retains local history/outbox; the receiver stores a message only after successful direct synchronization and durable local commit.
4. The only synchronization path is a direct local Wi-Fi Direct P2P connection between the participating devices, with peer authentication.
5. The initial release has no cloud backup, Android-managed device migration, app-managed export, or off-device recovery of chat history.
6. Normal app/process restart persistence remains required; this is local on-device storage, not operator-held or server-side storage.
7. If app data, the phone, or a required key is lost, there is no server copy to restore unsynchronized messages or local history.

## 2. Technical clarification

Android Wi-Fi Direct can establish a connection between nearby devices without connecting to an infrastructure network or hotspot. Android's socket APIs may nevertheless require the manifest INTERNET permission; this does not itself create public-internet traffic or a server dependency.

At the transport layer, one peer may temporarily listen for a local socket connection or be selected as Wi-Fi Direct group owner. This is a temporary role inside a direct peer-to-peer session, not a central PEYVAND server or a place where the product operator stores user data.

## 3. Documents updated

- docs/PRODUCT_REQUIREMENTS.md
- docs/LOCAL_CHAT_ARCHITECTURE_AND_UX.md
- docs/LOCAL_CHAT_UX_SPEC.md
- docs/decisions/ADR-0004-chat-data-protection.md

The engineering skill and ADR-0001 already describe local-only synchronization and no server/relay; they were re-read and retained.

## 4. Verification

- GitHub files were fetched from main before each update.
- Final file contents and blob SHAs must be re-read after the writes.
- No Kotlin, Compose, Room, Gradle, protocol, or executable mock implementation was created.
- No Android build or device test was run; this checkpoint covers product/architecture documentation only.

## 5. Remaining engineering work

- Decide exact local encryption-at-rest implementation and key lifecycle.
- Define history retention, storage quotas, and local deletion semantics.
- Configure Android backup/device-transfer exclusions for the supported Android/target-SDK/OEM matrix and verify on physical devices.
- Complete peer identity and message synchronization protocol threat models.
- Obtain separate explicit authorization before implementation.
