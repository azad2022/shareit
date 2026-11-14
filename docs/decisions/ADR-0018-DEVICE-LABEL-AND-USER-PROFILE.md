# ADR-0018 — Device Label and Optional User Profile

- **Status:** Accepted for CP-PROFILE-001 implementation.
- **Date:** 2026-11-14
- **Owner approval:** Explicit decision to display the device name and, when configured by the user, the separate profile.
- **Scope:** Local PEYVAND device label, optional user profile name/image, authenticated profile exchange, and peer presentation.

## Context

The chat experience already has a persistent cryptographic peer identity and a device display label exchanged after the users approve the same SAS and pinned mutual TLS completes. The first implementation uses a manufacturer/model label and stores it with the trusted peer. User-editable presentation data was not yet available.

The product owner selected a two-layer presentation: the device remains identifiable by its device name, while a user may additionally configure a profile name and image. The two must not be conflated.

## Decisions

### 1. Separate presentation values from identity

1. The device label is always available. Its default is the Android manufacturer/model; the user may override the label inside PEYVAND.
2. The override applies only inside PEYVAND. It must not mutate the Android system name, Bluetooth name, Wi-Fi Direct platform name, or operating-system settings.
3. The profile is optional and consists of a user-selected profile name and/or image.
4. When no profile is configured, show only the device label. When a profile name is configured, show it in addition to the device label. The device label remains visible as the secondary device descriptor.
5. Device label, profile name and profile image are never authentication credentials. The persistent P-256 private key, public key and SHA-256 fingerprint remain unchanged by profile edits.

### 2. Discovery and privacy

1. Before authentication, the discovery list may show the Android Wi-Fi P2P platform device name as untrusted metadata only.
2. PEYVAND's app-scoped device label and optional profile are exchanged only after bilateral SAS approval and pinned mutual TLS authentication.
3. The device label is shared with an authenticated peer. Profile name and image are included only when the local user enables profile sharing with trusted peers.
4. Disabling profile sharing sends an empty profile at the next secure identity exchange; the remote cache then removes previously shared profile values while retaining the device label.
5. No profile metadata or image is sent to an unknown or unauthenticated peer.

### 3. Local persistence and image handling

1. Store the local editable profile in the existing private Room database; no account, backend, cloud, relay or third-party profile service is introduced.
2. Use Android's system Photo Picker. Do not request broad image-library access solely for profile selection.
3. Import through the selected content URI, inspect image bounds before decoding, apply EXIF orientation, center-crop, and re-encode to canonical 256×256 JPEG at no more than 32 KiB.
4. Store the normalized bytes rather than a provider URI, so the profile remains available after temporary picker grants expire.
5. Store profile revision in the same Room transaction as the profile values. A changed profile increments the revision exactly once; an unchanged profile does not.
6. Validate peer avatars for bounded length, canonical JPEG dimensions/MIME, successful decode and SHA-256 digest before caching.

### 4. Authenticated exchange and peer cache

1. Extend the existing post-SAS, post-mTLS persistent identity HELLO to version 2.
2. Bind the device label, profile revision, optional profile name and optional avatar digest into the persistent identity proof transcript.
3. Carry the optional avatar bytes in the same bounded HELLO frame. Do not use the CHAT logical channel for bulk image bytes and do not create another TLS socket, socket reader or multiplexer.
4. Key remote profile data by the existing persistent peer fingerprint. Profile updates must never create a new trusted peer or conversation.
5. Apply strictly monotonic profile revisions. Older revisions cannot roll back a current profile; conflicting content at the same revision is rejected.
6. An established secure session does not receive live profile pushes. A local edit is synchronized on the next secure connection and the UI must communicate this clearly.
7. HELLO v2 is not silently downgraded to v1. Both devices must use a compatible protocol implementation.

## Consequences

- Users can identify both the physical device label and the optional user profile.
- A profile can be changed or removed without changing cryptographic identity, pairing trust or chat history.
- The remote profile cache is local-only and associated with the authenticated peer fingerprint.
- Profile updates are not instantaneous across an already active session; next-connection synchronization is the accepted initial behavior.
- The profile image is a bounded thumbnail, not a general file attachment or media-sharing channel.

## Validation gates

- Domain tests for NFC, Persian joiners, supplementary Unicode characters, control/bidi characters, name limits, default-label fallback and avatar bounds.
- Room tests for local profile persistence, monotonic revisions, clear/remove behavior, trusted-peer profile storage, stale revision rejection and same-revision conflict rejection.
- Migration tests must verify the v2-to-v3 schema update without destructive fallback.
- Protocol tests must verify HELLO v2 size limits, malformed lengths/UTF-8, profile digest, signature binding and v1 rejection.
- Physical Galaxy A12 ↔ ASUS X017DA tests must cover both pairing roles, no profile leakage before SAS/TLS, device label + profile name/photo display, sharing disabled, profile removal, revision update on reconnect, wrong hash/oversized image rejection and identity fingerprint stability.
- Verify TalkBack, Persian RTL, English LTR, large font scaling and image fallback when no profile exists.
