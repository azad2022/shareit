# Checkpoint CP-CHAT-DESIGN-001 — Local Chat Architecture and UX

- **ID:** CP-CHAT-DESIGN-001
- **Objective:** Record the owner-selected offline local-chat behavior and produce reviewable architecture, synchronization, privacy, and screen-level UX specifications.
- **Status:** IN_PROGRESS
- **Date:** 2026-11-13
- **Branch:** main
- **Design baseline commit:** 2bf8620f2c13235d7be5f5204461252bccf472e5
- **Implementation authorization:** Not granted by this checkpoint.

## 1. Scope completed

- Recorded the selected model: durable local chat history, durable outgoing queue, and synchronization only when the same devices reconnect directly and authenticate each other.
- Updated the product requirements and engineering skill to include local chat.
- Added a screen-level UX specification for Chats list, Conversation, connectivity banners, composer, delivery states, attachments, accessibility, and RTL/LTR.
- Added ADR-0001 as Accepted because it records the explicit owner decision.
- Added ADR-0002, ADR-0003, and ADR-0004 as Proposed; they contain engineering recommendations that still require security/privacy review and product-owner approval.
- Clarified that cloud backup and Android device-to-device data transfer are separate policy channels and that Android Keystore hardware backing cannot be assumed on every device.

## 2. Acceptance criteria and evidence

| Criterion | Evidence | Result |
|---|---|---|
| Owner-selected delivery model is explicit in product requirements | docs/PRODUCT_REQUIREMENTS.md; blob SHA c9082b4e33541c1e8df4120411efc488c69cdd62 | Verified |
| Architecture separates chat state/persistence/sync from transfer UI and file data plane | docs/LOCAL_CHAT_ARCHITECTURE_AND_UX.md; blob SHA a9fcf938c9b4edb58742f13336c36320c050caa9 | Verified |
| Screen-level UX and state/copy map exist | docs/LOCAL_CHAT_UX_SPEC.md; blob SHA 9b853c84bac9aef48a88196d9c184fcd59ae7cb1 | Verified |
| Owner-approved local delivery model is recorded | docs/decisions/ADR-0001-local-chat-delivery-model.md; blob SHA e3f10ec2be96d8b06174fc02377a8af6d6dd2f1a | Verified |
| Peer identity design is documented but not silently approved | docs/decisions/ADR-0002-stable-peer-identity.md; blob SHA f1cf9729cd3c9d4536c0599e15c9735b71d98953 | Verified |
| Message persistence and synchronization recommendations are documented | docs/decisions/ADR-0003-message-sync-semantics.md; blob SHA 2c22332ddbb7273cf7ed3bc0839a98670ead54de | Verified |
| Local data protection and Android backup distinctions are documented | docs/decisions/ADR-0004-chat-data-protection.md; blob SHA d5a7a8999ba99cf4bc8680b3e8b7d26a619d5eb1 | Verified |
| Engineering skill reflects the chat scope and test expectations | .agents/skills/shareit-android/SKILL.md; blob SHA 94f6dce02eb948420b526c58a00a4cd03a40cc40 | Verified |

## 3. Tests and environment

- **Documentation verification:** GitHub main contents fetched after writes; expected content markers and blob SHAs checked.
- **Automated code tests:** Not run; this checkpoint contains documentation-only changes.
- **Android build / lint / unit tests:** Not run.
- **Physical-device tests:** Not run; no chat implementation exists.
- **Devices/API levels:** Not applicable to this design-only checkpoint.

No build, app behavior, security test, or device compatibility claim is made.

## 4. Remaining blockers / decisions

1. Review and approve or revise ADR-0002 (stable peer identity, pairing, key rotation, and recovery).
2. Review and approve or revise ADR-0003 (message IDs, sequence/order algorithm, acknowledgement, cursor semantics, batching, and retry limits).
3. Decide ADR-0004 policy: cloud backup exclusion, optional encrypted D2D history migration, local retention, peer-forgetting, and deletion behavior.
4. Decide whether group chat, read receipts, voice messages, or synchronized deletion belong in any later milestone.
5. Define a versioned chat control protocol and threat model before implementation.
6. Obtain a separate explicit product-owner authorization before Kotlin/Compose/Room/Gradle implementation.

## 5. Risks

- Reconnect-only delivery may be delayed indefinitely if devices do not meet again or Android prevents the app from running.
- Device identity/key invalidation can make pending messages unavailable until a safe re-pair/recovery process exists.
- Backup behavior varies by Android version, target SDK, and device manufacturer; it must be explicitly configured and physically tested.
- Message ordering, duplicate suppression, and durable acknowledgements are correctness-critical; a UI-only implementation would not satisfy this checkpoint's design.
- Group chat must not be inferred from the already approved one-sender-to-many-receivers file-transfer capability.

## 6. Next gate

The next gate is owner review of ADR-0002 through ADR-0004. Only after those decisions and a protocol threat model are closed should a bounded implementation milestone be proposed. This checkpoint does not authorize implementation.
