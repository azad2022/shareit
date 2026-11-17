# ADR-0021 — Backward-Compatible Voice Capability Probe

- **Status:** Accepted
- **Decision date:** 2026-11-17
- **Scope:** Prove support for the versioned offline voice-call protocol without sending unknown TLS frame types to a legacy peer.
- **Related:** ADR-0017 peer identity and mTLS; ADR-0019 protocol framing; CP-CHAT-004.

## Context

An older PEYVAND build may not understand the new `CALL_*` frame types. The TLS multiplexer is intentionally fail-closed for unknown protocol routes. Sending a call frame to an older peer could therefore close a healthy authenticated session and disrupt text chat or file transfer.

## Decision

Use a bounded UDP challenge/response on the authenticated Wi-Fi Direct link, outside the TLS application-frame multiplexer.

1. Create the listener only after bilateral SAS approval, pinned mutual TLS and persistent peer-identity verification.
2. Bind the datagram socket to the exact local IPv4 address of the active Wi-Fi Direct P2P interface. Do not bind to a wildcard address or a default route.
3. Send only to the remote IPv4 address observed on the authenticated TLS socket.
4. Reject datagrams unless source IPv4 and UDP source port match the expected P2P peer and fixed capability port.
5. The request contains a random 256-bit nonce, authenticated session UUID, protocol version and both persistent peer fingerprints. The local Android Keystore identity signs the request payload with P-256 ECDSA/SHA-256.
6. The response binds the same nonce, session UUID, version and identities; the responder signs the response-specific domain-separated payload with its persistent identity key.
7. Verify signatures only against the trusted public key already pinned by the bilateral pairing flow.
8. Open the TLS `CALL_CONTROL` logical channel only after a fresh matching response signature succeeds.
9. If the peer ignores the UDP probe, treat it as a legacy/unsupported peer. Do not send any `CALL_*` TLS frame and do not alter the healthy text/file session.

## Security and resource limits

- A fresh nonce and session UUID prevent a captured response from authorizing a later call session.
- Network reachability and Wi-Fi Direct discovery are not identity proofs; signatures and pinned peer identities are mandatory.
- Packet size is capped at 256 bytes with strict version, message type, reserved-flag, length and signature bounds.
- Only a bounded number of authenticated requests and cached responses are processed per session.
- Capability state and listener lifetime are scoped to one mTLS session and reset on teardown.
- Capability proof is not call consent and does not grant microphone permission.
- Media setup remains separately gated by remote call acceptance, local runtime permission, P2P-only ICE candidate validation, and DTLS-SRTP verification.

## Consequences

- Older peers retain existing chat/file operation because they never receive an unknown call-control TLS frame.
- Both peers must implement the capability listener before calling is enabled.
- Capability failure disables calling only; it must not terminate the authenticated chat/file session.
- No DNS, public STUN/TURN, backend, relay or public Internet is required.
