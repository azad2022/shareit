# PEYVAND Local Chat — Architecture and UX Design

- **Status:** Owner-selected product behavior; architecture proposal; CP-CHAT-001 local persistence implementation authorized and underway.
- **Decision date:** 2026-11-13
- **Product:** PEYVAND (پیوند)
- **Repository:** azad2022/shareit (keep repository name unchanged)

## 1. Owner decision

The product owner selected **local chat with durable local history and synchronization when devices reconnect**.

Messages are persisted on the sender's device before being reported as locally saved. If the recipient is unavailable, the message remains in a durable local outbox. It synchronizes only after both devices establish a new direct local connection and authenticate the same previously paired peer.

This feature introduces no public internet dependency, account, cloud database, push service, server, relay, or remote delivery path. A queued message is not visible to the recipient until a later successful direct synchronization.

Local persistence survives ordinary app/process restarts while app data and identity keys remain intact. It does not promise recovery after app-data clearing, uninstall, device loss, or key loss.

**Local save, queueing, transport, durable remote receipt, and reading are separate states.** The UI must never label a locally queued message as delivered.

## 2. Initial scope and exclusions

Initial scope:
- One-to-one text conversations.
- Persistent local conversation and message history.
- Durable outgoing queue and reconnect synchronization.
- Reuse of PEYVAND's authenticated direct peer connection and connectivity lifecycle.
- File sharing from a conversation may delegate to the existing transfer subsystem; attachment delivery remains independent of message delivery.

Not in initial scope:
- Group chat or multi-sender chat rooms.
- Remote delivery through a relay.
- Voice/video calls, live audio, or walkie-talkie.
- Read receipts unless separately approved.
- Cloud backup or account-based multi-device synchronization.
- Guaranteed background delivery while the app is force-stopped.

Group file transfer remains a separate capability and does not imply group-chat approval.

## 3. Navigation and UX structure

PEYVAND remains file-transfer first. Chat is a distinct destination, not the primary home-screen experience.

Recommended navigation:
1. **Chats:** trusted-peer conversations and the primary home destination.
2. **Nearby:** discovery and explicit pairing to start a conversation.
3. **Transfers:** active, queued, paused, completed, and failed transfer jobs.
4. **Settings:** language, privacy, trusted devices, storage, and diagnostics.

### Chats list

Each row shows a user-assigned contact label, verified device label, last message preview, local conversation timestamp, unread count, and truthful connection/synchronization state.

Presence rules:
- “Nearby/connected” appears only while the peer is currently discovered, connected, and authenticated.
- When disconnected, show “Not connected” or “Last direct sync: [localized time]” only when backed by local records.
- Never imply that a peer is online or has received a queued message based on stale connection history.

### Conversation screen

Header:
- Contact label and verified-device indicator.
- Current direct connection state.
- Pending synchronization count when applicable.

Timeline:
- Incoming/outgoing text bubbles and localized date separators.
- Explicit delivery labels; do not rely on color or checkmarks alone.
- File cards only when backed by a real transfer job or verified local artifact.

Composer:
- Text composition remains available while disconnected because messages are saved locally.
- The interface explains that the recipient will not see the message until the devices reconnect.
- Attachments delegate to the existing transfer flow and its receiver consent/integrity checks.

Suggested disconnected-state copy:

> This message is saved on this device. It will be sent directly when both devices reconnect and authenticate. The recipient has not received it yet.

Suggested reconnect copy:

> Secure connection established. Synchronizing [N] pending messages.

Both strings must be localized and populated from real state.

### Required UX states

| State | Required behavior |
|---|---|
| No trusted peers | Explain discovery and pairing; do not show fabricated contacts. |
| Empty conversation | Show a first-message prompt; do not fabricate sample messages. |
| Peer disconnected | Allow local composition; mark messages as waiting for direct connection. |
| Pairing/authentication | Show real stages and require explicit confirmation. |
| Connected, sync pending | Show actual pending count and synchronization state. |
| Sync complete | Show completion only after durable remote acknowledgements. |
| Sync interrupted | Preserve local data and retry on a later authenticated session. |
| Local storage failure | Do not silently discard a message; do not claim it was saved. |
| Peer identity changed | Stop automatic sync and require explicit re-pairing. |
| Contact forgotten | Stop future communication and explain local-history consequences. |

## 4. Peer identity and trust

A display name, IP address, MAC address, or discovery advertisement is not a stable identity.

Proposed model:
- Each installation/device identity uses a cryptographic key pair protected by Android Keystore where supported.
- Pairing binds a trusted peer record to its public-key identity/fingerprint, not its mutable name or network address.
- Initial pairing uses a reviewed authenticated mechanism, such as authenticated QR bootstrap or short authentication string comparison, with explicit user confirmation.
- Every reconnection authenticates the peer again and validates the expected identity.
- Network addresses, ports, and transport sessions are ephemeral.
- Reinstall, key invalidation, device reset, or identity rotation may require explicit re-pairing. A changed key must never be silently accepted.

Key lifecycle, QR/SAS protocol, certificate binding, revocation, and backup behavior require a security ADR before implementation.

## 5. Logical architecture

Chat is a feature subsystem layered on existing connectivity/security services. It must not create a second discovery engine or bypass peer authentication.

Logical flow:

    Compose Chat UI
      -> Chat ViewModel / immutable UI state
        -> Chat use cases
          -> ChatRepository
            -> Local Chat Store (Room candidate)
            -> Durable Outbox
            -> Sync Coordinator
              -> Authenticated Peer Session interface
                -> Existing ConnectionManager / PeerAuthentication
                  -> Direct local transport

Responsibilities:
- **Presentation:** renders state; contains no socket, SQL, cryptographic, or retry logic.
- **Domain:** message lifecycle, enqueue rules, sync policy, ordering, deduplication, and acknowledgement semantics.
- **Data:** durable conversations, messages, cursors, trusted-peer references, and outbox records.
- **Sync coordinator:** synchronizes only with the expected authenticated peer; exchanges bounded metadata and message batches; resumes after interruption.
- **Connectivity/security adapters:** reuse the existing authenticated session and transport policy.
- **Transfer integration:** delegates attachments to the existing transfer subsystem. Large file bytes never travel through the chat message-control channel.

Room is a candidate persistence technology consistent with repository guidance; schema, migrations, and encryption choice remain open.

## 6. Durable data model (logical proposal)

Candidate records:
- **TrustedPeer:** peer identity reference, public-key fingerprint, user label, trust state, pairing metadata, and last successful authenticated connection time.
- **Conversation:** conversation ID, peer identity reference, creation time, and last activity.
- **Message:** immutable message ID, conversation ID, sender identity, per-sender sequence, logical ordering value, creation time, type, payload, and local delivery state.
- **OutboxEntry:** message ID, target peer identity, enqueue time, retry metadata, and last failure classification.
- **SyncCursor:** conversation/peer identity, sender identity, highest contiguous sequence received, and bounded gap information.
- **AttachmentReference:** message ID and transfer-job/artifact reference; never a copy of large file bytes in the chat database.

Constraints:
- Persist the message and outbox entry atomically before showing “Saved on this device”.
- Allocate sender sequence numbers transactionally and monotonically per conversation/sender identity.
- Use immutable globally unique message IDs.
- Enforce uniqueness for message ID and the (conversation, sender identity, sender sequence) tuple.
- Validate all remote payloads, lengths, versions, sequences, and state transitions before persistence.
- Timestamps are for display; do not use device wall clocks as the sole ordering authority.
- Do not store file contents in the chat database.

## 7. Message lifecycle and synchronization

### User-visible lifecycle

1. **Saved locally:** local transaction committed.
2. **Waiting for connection:** durable outbox entry exists; no delivery claim.
3. **Synchronizing:** a matching authenticated peer session is active.
4. **Delivered:** receiver durably committed the message and returned a valid acknowledgement.
5. **Needs attention:** a non-transient or repeated error blocks progress; local message remains intact.

Do not show “read” in the first version. A transport acknowledgement is not a read receipt.

### Reconnection algorithm

1. Verify that the authenticated public-key identity matches the trusted peer.
2. Negotiate protocol version and bounded synchronization limits.
3. Exchange synchronization summaries/cursors for relevant conversations.
4. Determine missing message ranges/IDs in both directions.
5. Exchange only missing messages in bounded batches.
6. Validate each message and persist it transactionally.
7. Acknowledge only after durable receiver-side commit.
8. Advance cursors only across contiguous committed sequences; preserve gaps for retry.
9. Mark outbox entries delivered only after the corresponding durable acknowledgement.
10. On disconnect, retain all unacknowledged records for the next authenticated session.

Batches may be delivered more than once. The receiver must be idempotent: duplicate IDs/sequences are acknowledged without creating duplicate timeline entries. This is at-least-once transport with idempotent persistence, not exactly-once network delivery.

### Ordering

Each sender has a monotonically increasing sequence within a conversation. A logical clock can preserve causal ordering across messages exchanged in both directions. Concurrent messages require a deterministic tie-breaker based on stable sender identity and sequence. Wall-clock timestamps are presentation metadata, not the sole ordering authority.

The final ordering algorithm must be specified and tested for concurrent offline composition on both devices.

### Retry and interruption

- Retry only while a matching authenticated peer session exists or when the next eligible session is established.
- Persist retry metadata across process death.
- Use bounded retry/backoff; do not scan continuously in the background.
- Reconstruct pending work from durable storage after process restart.
- A failed connection must not delete messages or advance sync cursors.
- Treat storage exhaustion, identity mismatch, protocol incompatibility, and malformed data as distinct errors.

## 8. Security and privacy

- Reuse the reviewed TLS/secure-session design; never create custom cryptography or disable certificate validation.
- Bind each sync session to the authenticated trusted peer identity and intended conversation.
- Defend against replay, peer substitution, duplicate delivery, downgrade, malformed payloads, and resource exhaustion.
- Enforce message count, batch size, payload size, and queue limits.
- Keep chat content local to participating devices; no remote relay or analytics upload.
- Define encryption at rest and local key protection before implementation. App-private storage alone is not database encryption.
- Decide Android backup behavior explicitly. Default recommendation: exclude chat history, outbox, and identity secrets from cloud backup; disclose that uninstall/data clearing or device loss can then permanently remove local history.
- Provide trusted-device management and an explicit “forget peer” flow.
- A forgotten or identity-changed peer must not receive queued messages until explicitly paired again.
- Decide whether forgetting a peer retains or deletes local history; never silently delete it.

## 9. Attachments and future media

Text messages are the baseline for the first chat release.

If a user shares a file from a conversation:
- Create a normal transfer job using the existing transfer engine.
- Require existing receiver consent and integrity verification.
- Store a typed attachment reference in the conversation.
- Show attachment transfer state independently from text-message delivery.
- Never mark an attachment received because its chat metadata synchronized.
- Do not route file bytes through the chat sync queue.

Voice messages, audio/video calls, and walkie-talkie behavior require separate product, privacy, codec, background-execution, and transport design. They are not implied by this decision.

## 10. Persistence and deletion

“Durable history” means local persistence inside each participating phone's private app storage, surviving normal process/app restarts while that phone's app data and required keys remain intact. It does not mean a server copy, cloud backup, Android device migration, reinstall recovery, or synchronization to a third device.

The product operator does not store chat messages, chat history, queued content, contacts, or message metadata. The first release has no central server/backend, remote database, cloud backup, Android-managed D2D history migration, app-managed export, or external backup path for chat data. Android backup/device-transfer exclusions must be configured for the supported OS/target-SDK matrix and verified on physical devices.

Before implementation, decide:
- Local history retention and storage limits.
- Single-message deletion and local conversation clearing.
- Whether remote deletion is ever supported (not recommended initially).
- Whether forgetting a peer retains or deletes local history.
- Encryption-at-rest and local key-loss behavior.

Recommended first-release behavior: local deletion only; no “delete for everyone”. Local deletion must not imply remote deletion. Synchronized deletion would require tombstones and a separate conflict design.

## 11. Accessibility, localization, and motion

- Follow PEYVAND's blue/white semantic design system; do not copy the HTML mock literally or reproduce another product's signature layout.
- All strings, delivery states, dates, and counts use stable localized resources.
- Persian and Arabic are RTL; English, Turkish, German, and French are LTR.
- Kurdish remains disabled until Sorani/RTL or Kurmanji/LTR is selected and translations are reviewed.
- Isolate mixed-direction filenames, URLs, package IDs, and identifiers.
- Support TalkBack, logical reading order, large font scaling, visible focus, and minimum 48dp targets.
- Motion is limited to real events: message insertion, connection state, and synchronization activity. Respect reduced motion and lifecycle interruption.
- Never animate a message as delivered before durable acknowledgement.

## 12. Future implementation acceptance criteria

Do not mark the chat milestone PASSED until reproducible tests demonstrate:

1. A message is durably stored before local-save confirmation.
2. Sending while disconnected survives process death and app restart.
3. Reconnection to the correct authenticated peer synchronizes queued messages in both directions.
4. A substituted peer cannot receive the queue.
5. Repeated batches and duplicate acknowledgements create no duplicate messages.
6. Interrupted synchronization resumes without message loss or cursor corruption.
7. Concurrent offline messages converge to the same deterministic timeline order.
8. Receiver acknowledgement occurs only after durable persistence.
9. Receiver storage failure never produces a delivered state.
10. Chat sync does not corrupt, starve, or falsely advance active file transfers.
11. Attachment metadata and actual transfer/integrity states remain independent.
12. No chat content, history, or identity secret is sent to a server or cloud relay.
13. RTL/LTR, large fonts, TalkBack, reduced motion, and lifecycle states are validated on physical devices.

The test matrix must include at least two physical Android devices, process recreation, disconnect during synchronization, storage exhaustion, duplicate/reordered frames, identity change, and app-data/key-loss behavior.

## 13. Open decisions requiring ADR or owner confirmation

This document records the selected local-history/reconnect-sync behavior, but does not silently finalize:
- Persistent identity and key rotation/re-pairing policy.
- Authenticated pairing bootstrap and certificate/key binding.
- Encryption at rest and local key protection.
- Android backup exclusion and history-loss disclosure.
- Message ordering algorithm and protocol frame/version design.
- Queue quotas, payload limits, and history retention.
- Local deletion and forget-peer semantics.
- Read receipts in a later version.
- Whether attachments enter the first chat milestone or remain a later integration.
- Background execution expectations and notification policy.
- Minimum Android API and compatibility constraints.

## 14. Related decisions and UX specification

- [ADR-0013 — Local Chat Delivery Model](decisions/ADR-0013-LOCAL-CHAT-DELIVERY-MODEL.md) — accepted owner decision.
- [ADR-0014 — Stable Peer Identity](decisions/ADR-0014-STABLE-PEER-IDENTITY.md) — proposed; security review/owner approval required.
- [ADR-0015 — Message Persistence and Synchronization Semantics](decisions/ADR-0015-MESSAGE-SYNC-SEMANTICS.md) — proposed; protocol review required.
- [ADR-0016 — Local Chat Data Protection](decisions/ADR-0016-LOCAL-CHAT-DATA-PROTECTION.md) — partially accepted for no external retention; local lifecycle policies remain open.
- [CP-CHAT-001 — Local persistence](checkpoints/CP-CHAT-001-LOCAL-PERSISTENCE.md) — current implementation slice.
- [Local Chat Screen-Level UX Specification](LOCAL_CHAT_UX_SPEC.md) — detailed screens, state/copy map, accessibility, and validation flows.

## 15. Authorization boundary

This is a product/architecture/UX document only. It does not authorize Kotlin source code, Compose screens, Room entities, Gradle changes, protocol implementation, or build configuration. Implementation requires a separate explicit owner instruction and a scoped checkpoint plan.
