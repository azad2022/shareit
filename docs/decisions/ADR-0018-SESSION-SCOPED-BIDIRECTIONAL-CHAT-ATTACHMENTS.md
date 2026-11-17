# ADR-0018 — Session-Scoped Bidirectional Chat Attachments

- **Status:** Accepted product behavior; implementation authorized on feature/cp-chat-003-bidirectional-attachments on 2026-11-17.
- **Decision date:** 2026-11-17
- **Owner:** Product owner
- **Scope:** One-to-one chat attachments during an authenticated local peer session.
- **Supersedes:** Previous attachment receiver-consent and directional send/receive UX rules only where they conflict with this ADR.

## Context

The current chat experience exposes separate Send Attachment and Prepare to Receive actions. It leaves the conversation for a directional transfer route, and the transfer coordinator is prepared as either sender or receiver. This forces users to coordinate both phones.

The owner selected a simpler model: one device requests a connection and the other accepts; after secure authentication, the conversation is bidirectional. Both devices may send and automatically receive files until that session ends. No separate consent prompt is required for each file.

## Accepted product decisions

1. The initiating user's Connect action expresses their intent. The other device's Accept action expresses the recipient's consent. The users then enter the same conversation.
2. Neither device has a permanent sender or receiver role. Either endpoint can initiate a transfer during the session.
3. Accepting the connection grants both devices permission to send and automatically receive files from that exact authenticated peer for the lifetime of that session. There are no per-file or per-batch acceptance prompts.
4. The grant is bound to the authenticated session ID and persistent peer identity, kept only in session memory, and destroyed on session close, Wi-Fi P2P loss, identity change, TLS failure or explicit disconnect. It is not a permanent trusted-peer setting.
5. All chat and file data remain device-to-device. No public Internet delivery, server, cloud, push service, relay or operator-held conversation data is introduced.
6. This decision is one-to-one only. It does not approve group chat, channels, many-to-many chat or multiple senders in a group.

Automatic reception never means automatic opening, execution or installation. APK installation remains a separate user-approved Android PackageInstaller action.

## Authentication boundary

User consent and cryptographic authentication are separate. Connect + Accept express intent for the session; they do not replace mutual TLS or persistent peer-identity proof.

ADR-0017 currently includes bilateral SAS approval and pinned mTLS. This cryptographic protection must not be silently removed to reduce taps. Review how first-time pairing and trusted reconnection should fit the simplified Connect/Accept flow. Any change to SAS frequency or bootstrap requires a security threat-model review before implementation. A previously trusted peer is accepted only when its persistent public-key identity matches the stored trust record; names and network addresses are not identity.

## Target transfer architecture

Keep the existing authenticated TLS socket and AuthenticatedPeerChannelMultiplexer as the only transport.

- A PeerSessionSupervisor owns the authenticated session and starts/stops chat and file services together.
- CHAT remains a dedicated logical channel for text/control messages and durable acknowledgements.
- A single PeerFileExchangeCoordinator leases FILE_TRANSFER for the active conversation, not only after a separate receive action.
- Exactly one inbound dispatcher reads FILE_TRANSFER frames and routes complete frames by session ID, transfer ID, item ID and frame type.
- All outbound producers submit bounded complete frames through the multiplexer writer.
- Incoming and outgoing jobs have independent IDs, progress, cancellation, verification, persistence and errors. Both endpoints can initiate transfers.

Do not run concurrent protocol readers on the same FILE_TRANSFER stream. The current SingleFileTransferCoordinator is direction-prepared and reads its own stream; it cannot safely be started twice on the same logical channel. Refactor around one channel owner/dispatcher and correlated transfer state machines.

The initial policy may allow one active batch in each direction concurrently, with further items queued and per-item outcomes. Bound jobs, buffers, in-flight bytes and frames. Radio capacity is shared; never imply both directions independently receive full link throughput.

## Automatic reception and integrity

For each inbound transfer:
1. Validate protocol version, session/peer binding, IDs, item count, metadata lengths, declared length and state transitions.
2. Enforce explicit per-item/session quotas, concurrency limits and available-storage checks. Values must be defined and tested before implementation.
3. Begin private staging and receive automatically, without a confirmation dialog.
4. Stream with bounded buffers and backpressure; never load a complete file into RAM.
5. Verify actual byte count and full-file SHA-256 before publishing.
6. Publish only verified artifacts. Never expose partial data as complete.
7. If validation, quota, storage, permission or publication checks fail, fail that item with a clear reason while preserving chat where safe.
8. Show an incoming transfer card in the conversation. The user can cancel an item or end the session; neither is a per-file approval flow.

File names and MIME hints are untrusted. Sanitize names, reject remote paths, and never treat MIME metadata as proof of safety.

## Native file selection and conversation UX

Do not build a private photo gallery or duplicate general file manager.

- One attachment action opens a small native choice: Photos & Videos or Files.
- Photos/videos use Android Photo Picker multi-select within a bounded product limit.
- General files, audio, archives and documents use the Android system document picker.
- Eligible installed-app/APK sharing stays on a separately reviewed PackageManager flow; APK transfer is not private app-data migration.
- Resolve selected content URIs through ContentResolver. Never assume raw filesystem paths or request broad all-files access merely for selection.
- If a provider revokes access or a selected cloud-backed item is unavailable offline, report that accurately; PEYVAND itself must not add a cloud transfer path.

Keep the conversation visible during selection and transfer. Render outgoing and incoming attachments as timeline cards with type, name, known size, real progress and honest states: Queued, Sending, Receiving, Verifying, Saved, Paused, Cancelled or Failed. Keep text delivery and file status independent. Show an image preview only after integrity verification; do not autoplay video. Opening a completed item requires a user action.

Persist typed attachment metadata and references to transfer jobs, not file bytes in the chat database. Protect sensitive metadata according to the local chat data-protection policy.

## Disconnect and recovery

Observe Wi-Fi P2P state/connection changes and authenticated socket lifecycle. Wi-Fi disabled, P2P link loss, TLS EOF/failure, identity mismatch or explicit disconnect closes the session.

On termination, destroy the auto-receive grant, stop new offers, cancel active I/O, close streams, release child channels and persist only durable transfer checkpoints. Incomplete artifacts remain unpublished and are marked Paused/Interrupted. Text history and pending outbox remain local. Resume a file only after a new accepted session authenticates the same peer and negotiates a valid durable offset; otherwise restart or request reselection safely.

A one-tap disconnect action may be available in chat. Turning Wi-Fi off must also terminate the session; users must not need to navigate multiple PEYVAND settings screens.

## Storage and resource limits

Use MediaStore, SAF and app-storage abstractions by data type and Android version. Define the visible PEYVAND destination layout and legacy API permission behavior in a separate storage decision. Any OS-level permission is distinct from per-file consent. Enforce free-space reserve, item/session size and count limits, active-transfer caps, timeouts and staging cleanup. Do not claim files are scanned or safe without a real reviewed scanner.

## Acceptance evidence

Require repeatable tests for: request/accept and scoped grant; both directions without role switching or per-file prompts; simultaneous bidirectional transfers with one inbound reader; multi-item independent outcomes; malformed/replayed/mismatched frames; quota and low-storage failures; byte-count/SHA-256 publication; grant destruction on disconnect; authenticated resume; persistent text outbox; Photo Picker/SAF URI access; and physical Galaxy A12 / ASUS X017DA tests including both connection roles, large files, simultaneous transfers, low storage, source revocation, Wi-Fi off, interruption, process recreation, RTL and accessibility.

## Non-goals and implementation boundary

Group chat/channels, many-to-many transfer, server/cloud/relay delivery, force-stopped background guarantees, read receipts, delete-for-everyone, voice/video, automatic open/install/execute and a custom gallery/file browser are out of scope.

This ADR records the approved behavior and constraints for implementation. The product owner explicitly authorized CP-CHAT-003 implementation on feature/cp-chat-003-bidirectional-attachments on 2026-11-17. Consequential protocol/version changes still require an ADR amendment before implementation. Do not change main, CP-004 or PR #3 as part of this checkpoint.


## Child-friendly and future classroom constraints

The product owner intends PEYVAND to be simple enough for a child and potentially useful in schools. This ADR does not expand scope beyond one-to-one chat, but the implementation must:

- expose one clear attachment action, native Photos & Videos / Files pickers, and plain-language status/error copy;
- keep peer identity and connected state visible and provide a straightforward disconnect action;
- support TalkBack, large font scaling, high-contrast states, minimum 48dp touch targets and correct Persian RTL / English LTR behavior;
- accept files automatically only from the exact peer whose authenticated session was explicitly accepted, and only until that session ends;
- never automatically open, execute or install received content;
- never enable background/public reception when no accepted session is active.

No distance claim is implied by this ADR. Wi-Fi Direct range and throughput vary by handset, radio conditions, obstacles, interference and device behavior. Any product claim must be based on a documented physical-device test matrix.

A future school/classroom mode is a separate milestone and needs its own threat model and ADR. It must define teacher-led pairing or class membership, temporary class-scoped authorization, student privacy, misuse/abuse handling, session revocation and safeguarding. It must not reuse this one-to-one auto-receive grant as a blanket classroom permission.

## Protocol implementation gate

The current v1 single-file protocol has uncorrelated empty ACCEPT/REJECT/CANCEL control frames, while the current batch protocol requires at least two manifest items and asks for receiver consent. Neither may simply be run concurrently on the same FILE_TRANSFER input stream.

Before the first CP-CHAT-003 runtime integration, define and test a versioned attachment-exchange envelope that:
- correlates every control/data/terminal frame to a unique batch/transfer identifier;
- supports one-item and multi-item attachments without ambiguous legacy control frames;
- routes all inbound frames through exactly one dispatcher;
- serializes complete outbound frames atomically;
- supports bounded per-transfer queues and backpressure;
- rejects wrong-session, unknown-ID, replayed, malformed, out-of-order and unsupported-version frames;
- has no silent downgrade and does not alter CP-004's isolated branch.

Do not add a second TLS socket or allow two protocol readers to consume the same FILE_TRANSFER stream.
