# ADR-0017 — Persistent Chat Identity and TLS Channel Multiplexing

- **Status:** Accepted for CP-CHAT-002 implementation; automated and physical validation remain mandatory.
- **Date:** 2026-11-13
- **Owner approval:** Explicit product-owner authorization in the 2026-11-13 conversation.
- **Scope:** Stable one-to-one peer identity, direct chat message frames/ACKs, and independent chat/file logical channels on one authenticated Wi-Fi Direct TLS session.

## Context

CP-CHAT-001 persists encrypted local message bodies and a durable outgoing outbox. It deliberately does not identify peers permanently or synchronize messages. The file-transfer coordinator previously had exclusive ownership of the authenticated TLS socket.

The product owner has now selected a chat-first app experience. Chat messages and file transfers must coexist in a conversation without introducing a second socket, a second TLS session, a server, a relay, or a second independent reader over the same TLS input stream.

## Decisions

### 1. Persistent peer identity

1. Each installation creates one P-256 EC signing key in Android Keystore. The private key is non-exportable; its public key is encoded and fingerprinted with SHA-256.
2. The identity HELLO is exchanged only after both devices approve the same SAS and pinned mutual TLS completes.
3. The proof transcript is domain-separated and binds the current session UUID, the ordered Group Owner and Group Client ephemeral certificate fingerprints, the sender role, and the sender persistent public key.
4. The remote proof is verified against the supplied P-256 public key. The public key/fingerprint is stored in the local trusted-peer Room table.
5. An existing fingerprint mapped to a different public key is a hard identity conflict. It must close/fail the session and must never silently replace an existing trusted key.
6. Device names, IP/MAC addresses, discovery service names and ephemeral certificates are display/transport metadata only; none is a persistent identity.
7. The first implementation uses a generic Android device label. Editable aliases, explicit forget/revoke UI, key rotation/re-pair policy and user-facing identity lifecycle remain follow-up work.

### 2. Chat wire messages and durable receipt

1. Chat uses bounded CHAT_MESSAGE and CHAT_ACK frames in the existing versioned transfer frame envelope. The chat payload has a canonical UUID message ID, sender sequence, observed peer sequence, creation timestamp, explicit UTF-8 body length and body bytes.
2. Message body UTF-8 payload is bounded by the domain 4 KiB limit. Invalid Unicode, non-canonical identifiers, invalid sequence/timestamp values, oversized/truncated payloads and wrong frame types are rejected.
3. Incoming messages require the next contiguous peer sequence. The recipient encrypts and commits the message and advances its peer sequence in one Room transaction.
4. The recipient emits the ACK only after the transaction returns successfully. ACK contains the same message UUID and sender sequence.
5. The sender confirms delivery and removes the outbox row only after the authenticated peer ACK matches the locally stored message ID, target identity and sequence. Socket write, local enqueue and connection state are never delivery evidence.
6. Repeated transmission of the same immutable message ID/metadata/body is idempotent. Reuse of an ID with different metadata or body is a protocol/data-integrity error. Unknown gaps are rejected; there is no silent sequence skipping.
7. Pending messages are replayed from the durable Room outbox on a later authenticated connection to the same persistent peer. No cloud, push or background-delivery guarantee is made.

### 3. One TLS owner, two logical channels

1. The authenticated TLS socket remains owned by the connectivity/security layer. No feature module receives the raw SSLSocket.
2. AuthenticatedPeerChannelMultiplexer owns exactly one TLS input reader and one serialized TLS output writer.
3. Complete bounded protocol frames are routed to a single-consumer CHAT or FILE_TRANSFER logical channel. The child channels have bounded buffers; backpressure is propagated to the parent reader.
4. The chat coordinator may read only chat frames. The existing file-transfer coordinator may read only transfer frames. Child close releases its logical channel and does not close the parent TLS session.
5. Parent TLS/multiplexer failure closes both children and fails the authenticated session. A child may not silently switch purpose or send another channel's frame type.
6. File bytes remain exclusively in FILE_TRANSFER data frames. Chat frames carry bounded text/control metadata only. A file attachment may be referenced by the UI but is not copied into the chat message body.
7. Only one active logical consumer per purpose is allowed. A second CHAT or FILE_TRANSFER claim returns unavailable instead of allowing competing readers.
8. The multiplexer does not create a second transport, fallback network, server or relay.

### 4. Product UI direction

1. The app's primary home is the local conversation list. The previous Send/Receive/Files three-button home is superseded.
2. The conversation screen is the place for text composition and attachment actions.
3. Image/video/audio/document/APK attachments delegate to the existing file-transfer subsystem and its receiver consent/integrity rules.
4. Voice/video call icons may be disabled placeholders only; call implementation requires a separate media/security milestone.
5. Group chat, read receipts, voice/video calls, cloud delivery and remote deletion are not included.

### 5. Identity-key loss, exact curve validation and sequence exhaustion

1. The remote signing key must use the complete named secp256r1 parameter set (field prime, curve coefficients, generator, subgroup order and cofactor). Matching only 256-bit field/order lengths is insufficient.
2. If the local Android Keystore identity alias is missing while any trusted peer remains in Room, identity creation must fail closed. The app must not silently generate a replacement key and present itself as a different device. Automatic trust deletion or silent re-pairing is prohibited; a separately designed, explicit recovery flow is required.
3. The initial identity may be created when no trusted peers exist. Existing aliases are reused; key invalidation or signing failure must not trigger automatic rotation.
4. `Long.MAX_VALUE` is reserved as the exhausted sequence sentinel. Chat message and acknowledgement codecs and incoming persistence reject it before it can overflow the contiguous sequence counter.
5. Regression coverage verifies exact secp256r1 acceptance/rejection, persistent identity stability, missing-key fail-closed behavior, sequence exhaustion rejection and the coordinator's durable-commit/ACK ordering.

## Consequences and risks

- Both devices must run a compatible PEYVAND protocol version; this checkpoint deliberately has no silent downgrade to the previous file-only session protocol.
- If the remote sends a frame for a logical channel not currently claimed by the recipient, the mux fails closed. The current UI requires the receiver to prepare the receive flow; automatic incoming-transfer prompts are a follow-up integration.
- A stalled logical consumer can apply backpressure to the shared TLS reader. Measure memory, cancellation and sustained transfer/chat behavior on physical devices.
- The current implementation does not persist file attachments as chat timeline records. It launches the existing transfer UI from within a conversation.
- Identity proof, key invalidation, migration, replay, duplicate ACK, session teardown, simultaneous chat/file activity and physical interoperability all require repeatable tests before PASSED.

## Validation gates

- Unit tests for identity transcript binding, invalid signatures, altered role/session/ephemeral certificate fingerprints, malformed identity frames and fingerprint conflict.
- Room instrumentation tests for new trusted-peer migrations, key persistence, inbound commit, duplicate idempotency, conflicting duplicate rejection, out-of-order sequence rejection, durable outbox and ACK binding.
- Multiplexer tests for CHAT/FILE routing, concurrent complete-frame writes, child-close isolation, buffer backpressure, unopened-channel rejection and parent failure cleanup.
- Physical device tests on the supported Galaxy A12 / ASUS X017DA matrix with exact model, Android/API build, APK SHA-256, both connection roles, repeated messages in both directions, disconnect/reconnect/outbox replay, mismatch rejection, file transfer while chat is active, cancellation and peer identity mismatch.
- Accessibility/UX tests: Persian RTL, English LTR, TalkBack, large font scaling, keyboard/composer focus, reduced motion, small screen and long names/messages.
