# CP-CHAT-002 — Persistent Peer Identity and Local Message Synchronization

- **Status:** IN_PROGRESS
- **Owner authorization:** Explicitly approved by product owner on 2026-11-13, including real one-to-one chat, local history, reconnect synchronization, and chat-first Compose UI.
- **Branch:** `feature/cp-chat-002-messaging-ui`
- **Base:** `eaa8fad9649362951e9283b91b8d162a26c75488` (`feature/cp-chat-001-local-persistence`)
- **Product decision:** ADR-0017 (chat-first product home and conversation UX)
- **Scope:** Stable device identity and trust, secure one-to-one message synchronization, Compose chats list/conversation UI, and integration of attachments with the existing transfer subsystem.

## Product-owner-approved behavior

- The app opens into a chat-first conversations experience, not the previous Send / Receive / Files three-button screen.
- Users can discover and explicitly pair a nearby device, then open a one-to-one conversation.
- Users can compose text while disconnected; the message must be durably saved locally and clearly marked as waiting for direct connection.
- Messages synchronize only after a direct local connection authenticates the same previously trusted peer.
- A message becomes Delivered only after a validated acknowledgement proving durable receiver-side commit.
- Photos, videos, audio, documents, generic files, and eligible APK artifacts are selected from the conversation and delegated to the existing transfer subsystem. Attachment delivery remains independent from message delivery.
- Voice/video call affordance icons may be visible but disabled until a separately approved call milestone exists.

## Required implementation slices

1. Security design: stable local device identity key lifecycle, trust record, authenticated QR/SAS pairing bootstrap, key invalidation/reset/re-pair behavior, and threat model.
2. Protocol: versioned bounded message framing, authenticated peer-session binding, replay/downgrade/resource-exhaustion defenses, idempotent receive, sequence/cursor rules, durable acknowledgements, and interruption recovery.
3. Persistence: extend the existing encrypted Room model with trusted peers, inbound message transaction, sender/peer sequence cursors, and migration tests; do not weaken CP-CHAT-001 protections.
4. Presentation: Compose chats list, trusted nearby-peer entry, one-to-one conversation, message timeline, empty state, delivery banners, composer, attachment sheet, and disabled future call affordances.
5. Transfer integration: launch the existing file selection/transfer path from the conversation and render only actual transfer state.
6. Validation: JVM/domain tests, Room instrumentation, protocol/security tests, Compose tests, exact-head CI, and physical two-device tests across supported API/device combinations.

## Security invariants

- Never use discovery name, IP/MAC address, or session-scoped TLS certificate as stable identity.
- Do not reuse or multiplex the current file-transfer-owned TLS channel without an explicit reviewed transport/session ADR.
- Unknown, changed, invalidated, or mismatched peer identity blocks message synchronization.
- Persist incoming messages before acknowledging them; sender marks Delivered only after validating that acknowledgement.
- At-least-once transport with idempotent persistence; never claim exactly-once network delivery.
- No read receipts, group chat, calls, server, cloud, relay, push, or background delivery guarantees.
- Never fabricate contact presence, message content, typing indicators, delivery status, or file progress.

## Design reference and originality

The product owner supplied a Telegram conversation screenshot as a quality reference for familiar messaging ergonomics (header, message timeline, empty state, persistent composer, attachment affordance, and call affordances). The implementation must use PEYVAND's original blue/white design language, original pattern/illustration, and original composition. No Telegram artwork, exact layout, proprietary assets, or signature styling may be copied.

## Evidence and checkpoint fields

- Commit SHA: pending
- CI run and exact-head result: pending
- Automated test commands/results: pending
- Debug APK SHA-256: pending
- Physical devices/API levels: pending
- Security review evidence: pending
- Remaining risks: persistent identity and synchronization protocol are not implemented until their respective slices pass review.
