# CP-CHAT-002 — Chat-First Experience and Direct Peer Messaging

- **Status:** `IN_PROGRESS`
- **Date opened:** 2026-11-13
- **Owner authorization:** Explicitly granted on 2026-11-13 for stable peer identity, direct one-to-one text messaging, safe channel multiplexing and chat-first Compose UI.
- **Repository:** `azad2022/shareit`
- **Branch:** `feature/cp-chat-002-chat-experience`
- **Base:** CP-CHAT-001 branch head `eaa8fad9649362951e9283b91b8d162a26c75488`
- **Latest implementation source SHA:** `8e25b56951ef981dcf85ade834edd94881b655a1` (the documentation commit that follows is not a source-code change)

## Objective

Replace the three-button Send/Receive/Files home with a polished, original PEYVAND messenger experience. The home screen is a local conversation list. Users open a one-to-one conversation to send text or initiate file transfer. Text must synchronize only over a directly authenticated local Wi-Fi Direct session to the same previously trusted peer.

## Scope

- Persistent P-256 signing identity protected by Android Keystore.
- Session-bound peer identity proof only after bilateral SAS approval and pinned mTLS.
- Room trusted-peer persistence and migration from chat DB v1 to v2.
- Bounded versioned CHAT_MESSAGE and CHAT_ACK frames.
- Durable incoming Room commit before ACK; outgoing delivery only after a matching durable-receipt ACK.
- Durable outbox replay after direct re-authentication to the same peer.
- One TLS input reader and one serialized writer owned by a bounded multiplexer, with separate CHAT and FILE_TRANSFER logical channels.
- Chat-first Compose home, conversation list/timeline, composer, empty states, nearby discovery hub, attachment handoff to existing transfer, and disabled future call affordances.
- Persian resource strings and native RTL behavior within the existing localization architecture.

## Exclusions

- Group chat, read receipts, message deletion for everyone, remote relay/cloud/push/server/account delivery.
- Voice/video calling or voice notes.
- Persistent attachment message/timeline records, inline image/video/document previews and automatic incoming-transfer notification prompts in this first UI slice.
- Release signing or release builds.
- Changes to main, CP-004, CP-003 or PR #3.

## Acceptance criteria

1. Main app entry opens the Chat list, not the legacy three-button home.
2. Conversation list contains only locally persisted trusted peers; no fabricated contact, presence or preview data.
3. The conversation header identifies the exact trusted peer and reports actual connection state.
4. Text send first commits encrypted message+outbox atomically. If disconnected, UI shows local-save/waiting; if connected, it sends on CHAT channel but remains pending until receiver Room commit and matching ACK.
5. Recipient commits an incoming message, sequence and encrypted body durably before sending ACK. Duplicate same-message replay is idempotent; conflicting IDs and sequence gaps fail closed.
6. On reconnection, only the authenticated same persistent public-key identity may synchronize that peer's outbox. A changed key stops the session.
7. CHAT and FILE_TRANSFER share one authenticated TLS socket through exactly one frame reader/writer and never compete for socket streams. Closing the transfer child must not close the chat child.
8. File attachment action delegates to the existing transfer coordinator. Existing receiver consent, bounded stream, SHA-256 verification and publication logic remain authoritative.
9. Voice/video icons, if shown, remain disabled and labelled as future functionality.
10. All user-visible text is resource-backed; no fake online state, message preview, typing indicator, progress or delivered state.
11. CI builds/tests/lint the exact branch head. Instrumentation validates DB migration and inbound message behavior on API 26/36.
12. Physical two-device tests validate first pairing, identity stability, both message directions, reconnect replay, mismatch rejection, file transfer while chat is active, child channel cleanup and no regressions to existing file transfer.

## Current source implementation snapshot

- Android Keystore P-256 identity provider and transcript-bound peer identity handshake are present; peer verification compares the full secp256r1 parameter set, not only bit lengths.
- If the persistent local identity alias is missing while trusted peers remain, pairing fails closed with an explicit identity-key-unavailable state; it does not mint a replacement identity.
- Trusted peers are stored in Room with a v1-to-v2 migration.
- Chat frame codec and session coordinator implement bounded message/ACK handling and durable receipt ordering. `ChatSessionCoordinatorTest` now exercises the real local CHAT channel path, proving that ACK waits for the incoming commit and the sender outbox is acknowledged only after the matching peer ACK.
- Message and ACK codecs plus Room ingestion reject `Long.MAX_VALUE` as an exhausted sequence sentinel.
- `AuthenticatedPeerChannelMultiplexer` routes frames over one TLS socket into CHAT and FILE_TRANSFER logical streams.
- The old `MainHomeScreen` is no longer the app's main route; `PeyvandApp` opens the new chat-first Compose home.
- Attachments launch the existing transfer UI/channel from the conversation; attachment cards are not yet persisted/rendered in the chat timeline.
- Previous code snapshot `3cbc8c2f5abb920f23a0bc8314eca2f1eff78ec9` passed exact-head CI run [43599087732](https://github.com/azad2022/shareit/actions/runs/43599087732) and produced a Debug APK. That run predates the security fixes and added coordinator tests.
- Exact-head CI for updated implementation `8e25b56951ef981dcf85ade834edd94881b655a1` is run [43623779996](https://github.com/azad2022/shareit/actions/runs/43623779996); it was still pending at the last status read. Do not treat the updated code or its new tests as validated until that run completes.
- No physical two-device chat validation has been completed. The checkpoint remains `IN_PROGRESS`; emulator CI is not hardware interoperability evidence.

## Evidence and remaining risk

Record the exact CI run, test counts, APK artifact/SHA-256, physical device/API/build details and failure-path outcomes after they are actually available. Do not infer pass from an in-progress or green emulator-only job.

Highest-risk areas:
- Multiplexer routing, child close semantics, backpressure and shared-reader lifecycle.
- mTLS/TLS session compatibility and no-downgrade protocol behavior with mixed app versions.
- Keystore key invalidation, device reset and trusted-peer re-pairing.
- Room migration and encrypted inbound idempotency.
- File-transfer coexistence while chat stream is active.
- User-visible trust/identity explanation and accessible Persian RTL.

## Related record

- [ADR-0017 — Persistent Chat Identity and TLS Channel Multiplexing](../decisions/ADR-0017-CHAT-IDENTITY-AND-CHANNEL-MULTIPLEXING.md)
- [CP-CHAT-001 — Local encrypted persistence](CP-CHAT-001-LOCAL-PERSISTENCE.md)
- [Local Chat UX Specification](../LOCAL_CHAT_UX_SPEC.md)
- [Local Chat Architecture and UX](../LOCAL_CHAT_ARCHITECTURE_AND_UX.md)
