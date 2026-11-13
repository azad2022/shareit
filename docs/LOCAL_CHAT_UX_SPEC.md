# PEYVAND Local Chat — Screen-Level UX Specification

- **Status:** Proposed screen-level UX; based on owner-selected local-history/reconnect-sync behavior.
- **Date:** 2026-11-13
- **Design direction:** PEYVAND blue/white identity; original composition; accessible RTL/LTR.
- **Implementation status:** Not authorized.

## 1. UX principles

1. PEYVAND remains transfer-first; chat is a peer communication feature, not the app's primary identity.
2. The interface must distinguish local storage from remote delivery.
3. Device presence is ephemeral and based on current authenticated connectivity, not last-known discovery.
4. Chat must never imply internet-style remote delivery.
5. The same message must keep the same visual identity and timestamp/state across app restarts.
6. Security errors require explicit explanation and must not be hidden behind generic connection failure.
7. File transfer status remains independent from chat-message status.

## 2. Navigation proposal

Primary destinations:
- **Nearby:** device discovery, send/receive, pairing, and active connection.
- **Transfers:** transfer queue and history.
- **Chats:** trusted-peer conversations.
- **Settings:** language, trusted devices, privacy, local storage, and diagnostics.

The final navigation component and placement require a full-app information-architecture review. The four destinations above are a proposal, not an approved fixed tab bar.

## 3. Chats list screen

### Header
- Title: localized “Chats”.
- Search action searches only locally known conversations and trusted contact labels.
- Add conversation action opens the trusted/nearby peer selection flow; it must not silently create trust from a discovered device.

### Conversation row
- Primary label: contact alias set by the user.
- Secondary label: verified device label or a neutral device descriptor.
- Preview: last locally stored message, with a distinct prefix for unsent outgoing messages where relevant.
- Metadata: local timestamp and unread count.
- State: one of current authenticated connection, disconnected, pending sync, or identity attention required.

### Empty and exceptional states
- No trusted peers: explain discovery and pairing.
- No messages: explain that a conversation begins after a trusted peer is paired.
- No search results: keep query and offer clear-search action.
- Identity attention: show a security-specific state; do not make the row look ordinarily offline.

## 4. Conversation screen

### Top app bar
- Back navigation.
- User contact alias.
- Verified peer indicator that opens peer details.
- Compact connection/synchronization status.

### Connection banner
- Disconnected: “Messages are saved on this device and will sync when both devices reconnect directly.”
- Authenticated and pending: show actual number of queued outgoing/incoming records and current sync status.
- Identity mismatch: persistent security warning; sending queued data is blocked.
- Do not display “online” unless a current authenticated peer session exists.

### Message timeline
- Incoming and outgoing text bubbles.
- Localized date separators.
- Timestamp per message, with clear locale and time-zone behavior.
- Delivery state text or accessible icon-plus-label:
  - Saved here
  - Waiting for direct connection
  - Synchronizing
  - Delivered to the peer device
  - Needs attention
- No read checkmark or “Seen” state in the initial design unless separately approved.
- Never use color alone to represent delivery state.

### Composer
- Multiline text input with accessible label.
- Send action disabled while text is empty or invalid.
- On send, persist atomically before clearing the composer or showing local-save confirmation.
- If storage commit fails, preserve the draft and show a recoverable error.
- While disconnected, allow sending to the local outbox and show the offline-delivery explanation.
- Prevent duplicate sends caused by rapid taps by using a single durable message operation/idempotency token.

### Message actions
- Copy text.
- Local delete only after deletion semantics are approved.
- Retry action for recoverable errors; retries must reuse the same message ID rather than create a duplicate message.

## 5. Attachments

- Attachment action opens the existing file-selection/transfer flow.
- The user sees recipient consent and transfer progress supplied by the transfer subsystem.
- The chat bubble stores a typed transfer reference, not file bytes.
- Separate status labels are required for message metadata delivery and file transfer/integrity.
- The chat UX must not imply that a file is available to the recipient merely because its message bubble synchronized.

## 6. State and copy map

| Domain state | UI label | User action |
|---|---|---|
| Local transaction committed | Saved on this device | None |
| Outbox pending, peer unavailable | Waiting for direct connection | Continue chatting |
| Authenticated sync in progress | Synchronizing | Optional cancel only if protocol supports safe cancellation |
| Durable peer acknowledgement | Delivered to peer device | None |
| Transient sync interruption | Sync paused; will retry on next direct connection | Retry when connected |
| Permanent/protocol error | Needs attention | View details; preserve message |
| Peer identity mismatch | Security check required | Re-pair through a verified flow |
| Local persistence failure | Message not saved | Retry; preserve draft |

Exact strings are draft copy and must be externalized in resources and reviewed by native-language QA.

## 7. Visual system guidance

- Primary surfaces: white and restrained blue-tinted neutrals.
- Primary actions: PEYVAND blue tokens from the central design system.
- Message bubbles: distinct sender/receiver surfaces with adequate contrast; avoid overly saturated backgrounds.
- Status: semantic icon plus text and optional color. Do not use green/red alone.
- Shape: consistent with global PEYVAND shape tokens; avoid copying the uploaded prototype's exact measurements/layout.
- Typography: Persian-first readable type scale, with robust Latin fallback and long-string behavior.
- No remote font dependency for first-run/offline reliability; use a reviewed bundled/system font strategy when implementation is authorized.

Final HEX values, fonts, spacing, component shapes, shadow/elevation, and animation timings belong to the central design-system ADR and need contrast/performance evidence.

## 8. RTL, accessibility, and motion

- Persian and Arabic layouts must use native RTL layout direction; English, Turkish, German, and French remain LTR.
- Kurdish remains unresolved until Sorani/RTL or Kurmanji/LTR is explicitly selected.
- Isolate file names, package identifiers, hashes, URLs, and network values inside RTL content.
- TalkBack reading order must follow chronological conversation order and announce delivery state.
- Interactive controls must meet at least 48dp target size.
- Large font scaling must not hide message state or composer controls.
- Use visible focus and keyboard navigation where applicable.
- Motion may animate message insertion and actual synchronization transitions only. Respect reduced-motion and lifecycle interruption.
- Never animate a queued message as delivered or use a fabricated typing/presence indicator.

## 9. UX validation scenarios

1. Send while both devices are disconnected; message shows local save and pending state.
2. Close/reopen sender app; message and pending state remain identical.
3. Reconnect the expected peer; only authenticated identity allows sync.
4. Reconnect a device with a different key; synchronization remains blocked and a clear security warning appears.
5. Interrupt synchronization midway; messages remain and no duplicates appear after reconnect.
6. Receiver storage is full; sender never sees Delivered.
7. Both users compose while offline; after sync, both timelines converge deterministically.
8. Chat attachment metadata syncs but file transfer is rejected; UI shows metadata delivered and file not transferred.
9. Change app language; conversation data and transfer data remain intact.
10. Validate TalkBack, large font, RTL/LTR, and reduced-motion.

## 10. Local-only data and privacy disclosure

The initial chat release must communicate these facts in Persian-first user-facing privacy/help copy:

- Conversations and queued messages are stored only on this phone and the other participating phone after receipt.
- PEYVAND has no chat server, account, cloud message store, relay, or operator-held conversation history.
- Messages waiting for the other device remain on the sender's phone; they are not delivered remotely while the devices are apart.
- Clearing app data, uninstalling the app, losing the phone, or losing a required key may permanently remove that phone's local chat history and unsent queue.
- The initial release does not provide cloud backup, automatic device migration, or remote recovery for chat history.

Do not use wording such as “backed up” or “safe in the cloud”. Local persistence must not be presented as off-device backup.

## 11. Authorization boundary

This document is a screen-level design specification only. It does not authorize Android source code, Compose screen implementation, Gradle changes, executable mocks, or protocol implementation.
