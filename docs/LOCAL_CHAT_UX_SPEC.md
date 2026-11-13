# PEYVAND Local Chat — Screen-Level UX Specification

- **Status:** Owner-approved design direction; implementation tracked in CP-CHAT-002.
- **Date:** 2026-11-13
- **Design direction:** PEYVAND-original premium blue/white visual identity; Persian-first RTL; accessible RTL/LTR.
- **Reference:** Product-owner-provided Telegram conversation screenshot, used only as a messaging-ergonomics reference.
- **Implementation status:** CP-CHAT-001 provides encrypted local persistence only. CP-CHAT-002 is authorized and in progress.

## 1. Product and UX principles

1. PEYVAND's primary experience is a one-to-one local messenger with integrated direct file/app transfer. The previous three-action Send / Receive / Files home screen is retired as the primary home.
2. The first app destination is the Chats list. A conversation is opened from a trusted peer, not from a fabricated sample contact.
3. Text messages may be composed while disconnected and stored in the durable local outbox. The UI must say they are saved locally and waiting for a direct authenticated connection.
4. Local persistence, queued state, synchronization, durable remote delivery, and reading are separate states.
5. Device presence is shown only from current authenticated peer state. Discovery alone is neither trust nor online presence.
6. File transfer remains an independent workflow delegated from the conversation. A file bubble must be backed by a real transfer job/artifact.
7. No internet server, account, cloud message store, push service, or relay is introduced.
8. Security errors are explicit and block sync when peer identity cannot be verified.
9. No fabricated contacts, message previews, timestamps, unread counts, typing indicators, call states, transfer progress, or delivery receipts.
10. Visual conventions may be familiar to messaging-app users, but all PEYVAND artwork, pattern, illustrations, icons, typography, spacing, and composition must be original.

## 2. Primary navigation and Chats list

The primary destination is **Chats**.

### Header and actions
- PEYVAND brand mark and localized Chats title.
- Search limited to locally known trusted conversations and user-assigned contact labels.
- New conversation action opens nearby discovery and the explicit pairing flow.
- Settings access remains available without taking over the primary content.

### Conversation row
- User-assigned contact alias.
- Verified-device indicator and neutral device descriptor.
- Last locally stored message preview; pending outgoing preview is visibly distinguished.
- Local timestamp and unread count only when those values are backed by stored state.
- Current authenticated connection, disconnected, pending synchronization, or identity-attention state.
- No online indicator based on stale discovery.

### Empty and exceptional states
- No trusted peers: explain how to discover and explicitly pair a nearby device.
- No messages: show an original PEYVAND empty-conversation illustration and a clear first-message prompt.
- No search results: preserve query and offer clear-search.
- Identity attention: persistent security-specific state; do not style it as ordinary offline.
- Local database/key failure: explain that history is unavailable and preserve the error; never silently initialize a replacement key.

## 3. Conversation screen

### Header
- Back navigation, contact avatar/monogram, user alias, and verified-peer indicator.
- Compact connection/synchronization state derived from the current authenticated session.
- Voice-call and video-call icons may be shown as disabled future affordances. They must have clear unavailable semantics and must not launch fake call screens or imply support.
- Overflow actions are limited to implemented and approved functions.

### Conversation background and timeline
- A subtle, original PEYVAND pattern may be used, with message-surface contrast independently validated.
- Incoming and outgoing text bubbles with consistent shape, spacing, and alignment.
- Localized date separators and per-message local timestamps.
- Delivery state displayed as accessible icon-plus-text:
  - Saved on this device
  - Waiting for direct connection
  - Synchronizing
  - Delivered to peer device
  - Needs attention
- No read receipt or “Seen” indicator in the initial version.
- Do not use color alone to communicate status.
- Do not show placeholder/sample messages in production UI.

### Connection banner
- Disconnected: explain that messages remain local and will synchronize only when both devices reconnect directly and authenticate.
- Authenticated with pending messages: show actual queue count and actual sync state.
- Identity mismatch/key invalidation: persistent warning and block message synchronization.
- Show “online” only when the current authenticated session provides that state.

### Composer
- Persistent bottom composer, multiline text field, accessible label, send action, and attachment affordance.
- Emoji affordance may be included when an actual picker/insert behavior is implemented; otherwise omit it.
- Send is disabled for empty/invalid text.
- Persist message and outbox atomically before clearing the draft.
- On storage failure, preserve draft and show a recoverable error.
- Prevent duplicate sends from rapid taps by using a single durable enqueue operation.
- Voice-message recording is not included in the initial scope.

### Message actions
- Copy text may be supported.
- Delete/edit semantics are not assumed; do not show unimplemented menu actions.
- Retry must reuse the same message ID and must not create duplicates.

## 4. Attachments and transfer integration

- Attachment sheet supports photo, video, audio, documents, generic files, and eligible APK artifacts when the existing catalog and transfer subsystem support that source.
- Selection delegates to the existing one-to-one file transfer flow, including receiver consent, bounded streaming, integrity verification, and destination publication.
- Large file bytes never enter the chat message database or message-control protocol.
- Chat message/attachment metadata delivery and file transfer completion are distinct.
- The conversation timeline may show a transfer card only when backed by an actual transfer job or verified local artifact.
- File transfer cancellation, rejection, failure, and integrity results are rendered from the transfer subsystem's actual state.

## 5. Message lifecycle and copy

| Domain state | UI label | User action |
|---|---|---|
| Local transaction committed | Saved on this device | None |
| Outbox pending, peer unavailable | Waiting for direct connection | Continue chatting |
| Authenticated sync in progress | Synchronizing | No false completion |
| Durable peer acknowledgement | Delivered to peer device | None |
| Transient sync interruption | Sync paused; will retry on next direct connection | Retry only when safe |
| Permanent/protocol error | Needs attention | View details; preserve message |
| Peer identity mismatch | Security check required | Verified re-pair flow |
| Local persistence failure | Message not saved | Retry; preserve draft |

A transport socket write is not delivery. Delivered means the expected authenticated peer durably committed the message and returned a validated acknowledgement. It does not mean read.

## 6. Visual and motion direction

- Premium blue/white PEYVAND identity with restrained neutral surfaces.
- Original brand-specific message wallpaper/pattern; never reuse Telegram wallpaper, stickers, mascot, or other artwork.
- High-contrast incoming/outgoing bubbles; no low-contrast text over decorative pattern.
- Consistent global spacing, shape, elevation, iconography, and typography tokens.
- Persian-first type scale with robust Latin fallback and long-string behavior.
- No remote font dependency for first-run/offline reliability.
- Motion may clarify real message insertion and actual synchronization transitions only. Respect reduced-motion, lifecycle cancellation, battery, and lower-end devices.
- No decorative infinite loops or simulated typing/presence/progress.

## 7. RTL, accessibility, and localization

- Persian and Arabic use native RTL; English, Turkish, German, and French use LTR.
- Kurdish script/locale variant remains unresolved; do not activate a Kurdish locale until Sorani/RTL versus Kurmanji/LTR is confirmed.
- Isolate filenames, package IDs, hashes, URLs, and network values inside RTL text.
- TalkBack reads the timeline chronologically and announces delivery state.
- Interactive targets are at least 48dp.
- Large font scaling must not hide composer controls or message state.
- Keyboard and system-bar insets must be handled; no composer overlap.
- All visible strings are Android resources with stable keys and plurals.

## 8. Validation scenarios

1. No trusted peers: empty state is truthful and directs user to nearby pairing.
2. Send while disconnected: durable local save and pending state are shown.
3. Process restart: message and state remain identical.
4. Reconnect expected peer: only matching authenticated identity allows sync.
5. Changed peer key: sync is blocked and security warning is shown.
6. Interrupt sync: messages remain and retries do not duplicate timeline entries.
7. Receiver storage full: sender never sees Delivered.
8. Simultaneous offline composition: timelines converge deterministically after sync.
9. Attachment metadata syncs but file transfer is rejected: message and transfer states remain distinct.
10. Transfer is accepted: actual transfer progress and integrity result appear in the conversation.
11. Language change: local history and transfer data remain intact.
12. TalkBack, large font, RTL/LTR, keyboard, reduced motion, process recreation, and physical two-device behavior are verified.

## 9. Local-only privacy disclosure

The initial chat release must explain:
- Conversations and queued messages remain in the participating phones' private app data.
- PEYVAND has no chat server, account, cloud message store, relay, or operator-held conversation history.
- Messages waiting for the other device remain on the sender's phone until a later direct authenticated sync.
- Clearing app data, uninstalling, losing the phone, or losing a required key may permanently remove local history and unsent messages.
- The initial release has no cloud backup, automatic device migration, or remote recovery.

Do not use “backed up” or “safe in the cloud” for local persistence.

## 10. Authorization and scope boundary

The product owner explicitly authorized CP-CHAT-002 on 2026-11-13, including persistent peer identity, real one-to-one message synchronization, and Compose chat UI. See `docs/checkpoints/CP-CHAT-002-MESSAGING-AND-UI.md` and accepted `docs/decisions/ADR-0017-CHAT-FIRST-UX.md`.

Group chat, read receipts, voice messages, voice/video calling, server/relay delivery, and cloud backup remain out of scope. Disabled call icons are visual affordances only.
