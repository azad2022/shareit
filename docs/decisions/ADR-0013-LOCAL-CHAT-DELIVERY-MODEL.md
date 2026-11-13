# ADR-0013: Local Chat Delivery Model

- **Status:** Accepted
- **Decision owner:** Product owner
- **Decision date:** 2026-11-13
- **Scope:** PEYVAND local chat

## Context

PEYVAND is an Android-first local peer-to-peer product. The product owner selected a chat model that keeps durable local history and synchronizes messages when the same devices reconnect. A cloud/relay model would change the product's offline-first boundary and introduce a remote infrastructure dependency.

## Decision

1. Chat messages are stored durably on the sender's device before the UI reports that they are saved locally.
2. Messages that cannot currently be delivered remain in a durable local outbox.
3. Chat synchronization occurs only over a direct local connection after the remote device has been authenticated as the previously paired peer.
4. No public-internet delivery, account, backend, cloud database, push service, or relay is part of this model.
5. Local-save state and remote-delivery state are distinct. A local outbox entry is not proof of delivery.
6. Delivery confirmation must be based on a protocol-level acknowledgement whose semantics are defined separately; writing bytes to a socket is not delivery.

## Consequences

### Positive
- The product remains aligned with its direct, offline-first architecture.
- Local conversations can remain available across ordinary process and app restarts.
- No central service receives message content or metadata for routing.

### Trade-offs
- Delivery can be delayed indefinitely until both devices reconnect directly.
- Messages are not recoverable if app data or required identity keys are lost unless a separately approved export/backup mechanism is designed.
- The app cannot truthfully show remote presence or delivery while the peer is unreachable.
- Android background-execution restrictions may delay synchronization; force-stop and device shutdown are not guaranteed delivery scenarios.

## Not decided by this ADR

- Whether group chat is in scope.
- Read receipts.
- Voice/video calls or live audio.
- Message deletion semantics.
- Encryption-at-rest implementation and key lifecycle.
- Exact peer authentication bootstrap, wire format, message ordering, and synchronization batch limits.

## References

- `docs/PRODUCT_REQUIREMENTS.md`
- `docs/LOCAL_CHAT_ARCHITECTURE_AND_UX.md`
