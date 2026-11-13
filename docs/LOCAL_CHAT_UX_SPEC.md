# PEYVAND Local Chat — Screen-Level UX Specification

- **Status:** Owner-approved chat-first product direction and screen-level UX.
- **Date:** 2026-11-13 (owner confirmation with Telegram chat screenshot reference).
- **Design direction:** PEYVAND's original blue/white visual identity, Persian-first, accessible RTL/LTR. The uploaded Telegram screenshot is a convention/quality reference only; do not copy Telegram artwork, exact layout, wallpaper, branding, or assets.
- **Implementation status:** Compose conversation-list and conversation screens have been added on feature/cp-chat-002-chat-experience; exact-head CI and two-device validation remain pending. Attachment handoff uses the existing transfer screen/channel; a durable attachment bubble/timeline record is not yet implemented.

## 1. UX principles

1. PEYVAND is chat-first: the main screen is the local conversation list, and each one-to-one conversation is the primary place to exchange text and initiate file transfer.
2. The interface must distinguish local storage from remote delivery.
3. Device presence is ephemeral and based on current authenticated connectivity, not last-known discovery.
4. Chat must never imply internet-style remote delivery.
5. The same message must keep the same visual identity and timestamp/state across app restarts.
6. Security errors require explicit explanation and must not be hidden behind generic connection failure.
7. File transfer status remains independent from chat-message status.

## 2. Primary navigation

The product owner has superseded the earlier transfer-first navigation. The primary app entry is now **Chats**.

- **Chats (home):** trusted-peer conversation list, search, last locally stored message, pending count and truthful direct-connection state.
- **Transfers:** active/queued/paused/completed/failed transfer activity.
- **Nearby / Devices:** discovery, SAS pairing and preparing this device to be found.
- **Settings:** language, trusted devices, privacy, local storage and diagnostics.

A user opens Nearby only to find or prepare a peer. After successful mutual authentication and stable identity proof, PEYVAND opens that peer's conversation. Send/receive/file browsing are not separate main-home actions.

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
- Attachment menu for sending user-selected images, videos, audio and arbitrary documents/APK files through the existing FILE_TRANSFER flow; receiving is explicitly prepared by the receiver.
- Emoji control and send action.
- Voice/video call controls are not messaging controls and remain disabled until a separately approved call subsystem exists.
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

## 11. Authorization and implementation boundary

The product owner explicitly authorized CP-CHAT-002 implementation on feature/cp-chat-002-chat-experience on 2026-11-13. This supersedes the prior CP-CHAT-001-only authorization note.

The current Compose implementation provides a chat-first home, trusted-peer list, conversation timeline, composer, empty states, and a Nearby hub. Text synchronization uses a separate logical CHAT channel. Attachments currently delegate to the existing FILE_TRANSFER screen/channel and retain the existing consent/integrity pipeline; persistent file attachment message cards, inline previews and background incoming-transfer prompts remain follow-up work. Voice/video icons are disabled placeholders only.
