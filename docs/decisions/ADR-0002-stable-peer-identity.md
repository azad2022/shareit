# ADR-0002: Stable Peer Identity for Local Chat

- **Status:** Proposed — requires security design review and product-owner approval
- **Date:** 2026-11-13
- **Scope:** Trusted peer identity used by chat synchronization

## Context

Chat queues may remain on a device for hours or days before the peer reconnects. A display name, IP address, MAC address, discovery record, or currently connected socket is not sufficient to prove that a reconnecting device is the intended recipient. Sending a queued private message to a substituted peer is a security failure.

The existing PEYVAND requirements already require authenticated peers and prohibit treating discovery as authentication.

## Proposed decision

1. Bind each trusted peer record to a cryptographic public-key identity or an equivalently strong stable identity established during authenticated pairing.
2. Store the local private identity key using Android Keystore-backed protection where supported.
3. On every reconnection, authenticate the peer and compare its identity with the trusted record before releasing queued messages.
4. A changed, missing, invalidated, or unknown identity must block automatic synchronization.
5. A display-name change must not alter identity; a matching display name must not override an identity mismatch.
6. Identity reset, app reinstall, key invalidation, and key rotation must use an explicit re-pairing/recovery path. Never silently trust a replacement key.

## Pairing alternatives still to be evaluated

- Authenticated QR bootstrap.
- Short Authentication String (SAS) comparison bound to the current session.
- How the selected method interoperates with the repository's existing mTLS/session design without creating a parallel trust model.

The final choice must reuse a reviewed cryptographic construction. Do not design a custom cryptographic handshake.

## Security and UX consequences

- The Chats list should distinguish a verified peer from an unpaired discovered device.
- On identity mismatch, show an actionable security warning and do not offer a one-tap “trust anyway” path.
- Re-pairing must explain that pending messages remain blocked until the owner confirms the new identity.
- Peer identity should not expose long-term secrets in discovery advertisements or logs.

## Open questions

- Is identity scoped to an app installation, Android Keystore key, or a user-managed identity that can be migrated?
- What is the exact recovery UX after reinstall or device reset?
- Should the old trust record be revoked automatically after explicit re-pairing?
- Which existing PEYVAND secure-session certificate/key materials can safely be reused?

## Acceptance evidence before implementation

- Threat model for peer substitution, replay, key replacement, and device reset.
- Security review of key generation, storage, rotation, and deletion.
- Tests proving queued messages are never sent after identity mismatch.
- Pairing/re-pairing UX review on physical devices.
