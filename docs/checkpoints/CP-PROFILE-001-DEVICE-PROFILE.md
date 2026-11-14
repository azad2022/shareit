# CP-PROFILE-001 — Local Device Label and Authenticated User Profile

- **Status:** IN_PROGRESS
- **Branch:** feature/cp-profile-001-device-profile
- **Base commit:** b721b1ff8b4188e4fd729b0a4751c9997f6eac3d
- **Scope:** PEYVAND-only editable device label; optional profile name and image; local Room storage; authenticated peer exchange; dual-label UI.

## Goal

Every peer retains a visible device label. A user may additionally configure a profile name and/or image. A profile must never replace or mutate the persistent cryptographic identity.

## Acceptance criteria

1. Device label defaults to manufacturer/model and can be overridden inside PEYVAND without changing Android system settings.
2. Profile name and image are optional; without them, only the device label appears.
3. When a profile name exists, the UI shows it together with the device label in conversation list and conversation header.
4. Local edits survive process restart and increment a monotonic revision only when content changes.
5. Photo selection uses Android Photo Picker, normalizes EXIF orientation, crops to 256×256 JPEG and caps the encoded avatar at 32 KiB.
6. Unknown/untrusted peers receive neither PEYVAND profile metadata nor profile image before bilateral SAS and pinned mTLS authentication.
7. The authenticated identity HELLO v2 binds device label, revision, profile name and avatar digest to the existing persistent identity proof.
8. Remote profile cache remains bound to the same persistent fingerprint; stale revisions cannot roll back profile data; same-revision conflicts fail closed.
9. Disabling profile sharing or removing a profile clears the remote optional profile on the next secure connection.
10. Profile edits do not change peer fingerprint, pairing, conversation history, queued chat messages or file-transfer state.
11. No second socket, TLS connection, raw TLS reader, backend, cloud or relay is introduced.
12. UI states, accessibility, Persian RTL and English LTR are validated.

## Current implementation record

- Local Room schema version 3 adds the local profile row and optional trusted-peer profile fields; migration 2→3 is registered.
- DeviceProfileRepository and RoomDeviceProfileRepository store editable profile values and canonical avatar bytes.
- PeerIdentityHandshake exchanges profile values only after the approved SAS and pinned mTLS stages; avatar bytes are bounded and SHA-256 checked.
- The identity proof binds the profile metadata digest and versioned HELLO payload. HELLO v1 is not accepted as an implicit fallback.
- UI adds a profile editor, a profile shortcut, avatar rendering, and distinct device/profile labels.
- Profile edits synchronize on the next secure connection, not over a new live profile channel.

## Validation and evidence

- Domain unit tests authored in DeviceProfileRulesTest; execution pending.
- Room Android instrumentation tests authored in DeviceProfileRepositoryTest; execution pending.
- Exact-head Android CI: pending.
- API 26 and API 36 instrumentation: pending.
- Physical device matrix: Galaxy A12 ↔ ASUS X017DA; not yet tested for this feature.
- APK SHA-256 and install/retest evidence: pending.

## Risks and follow-up

- HELLO v2 is incompatible with HELLO v1; both devices must upgrade together. No downgrade path is added.
- Profile data changes do not propagate during an already established connection. A live PROFILE logical channel would require a separately reviewed protocol/UX scope.
- Database migration 2→3 must pass automated validation on API 26 and API 36 before the milestone can pass.
- A profile is presentation data and never a trust indicator; the verified badge continues to mean cryptographic peer authentication only.
