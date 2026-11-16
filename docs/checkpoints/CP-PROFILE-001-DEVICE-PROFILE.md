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

- **Exact implementation source SHA:** `fccb9776125afdcfd24e1e957578ce1bf66fe378` on `feature/cp-profile-001-device-profile`.
- **Exact-head CI:** [Run #43910295903](https://github.com/azad2022/shareit/actions/runs/43910295903) completed successfully on 2026-11-16; all three jobs passed: Build/unit tests/lint, Android instrumentation API 26, and Android instrumentation API 36.
- **JVM unit tests:** 172/172 passed; zero failures, errors or skips. `DeviceProfileRulesTest` passed 8/8.
- **Lint:** zero errors across configured modules. Warnings: app 28, core:data 2, core:storage 1, core:designsystem 1, core:connectivity 1; core:domain and core:transfer reported no issues.
- **API 26 instrumentation:** 35 tests reported, zero failures and errors, one conditional skip. `:core:data` passed 27/27, including the v2→v3 migration test (1/1), DeviceProfileRepositoryTest (3/3), and RoomChatRepositoryTest (23/23). Storage passed 2/2. Connectivity reported 6 tests with one conditional TLS 1.3 skip because the Android provider does not support TLS 1.3; the remaining five passed.
- **API 36 instrumentation:** 35/35 passed with zero failures, errors or skips. `:core:data` 27/27 (migration 1/1, profile 3/3, chat 23/23); storage 2/2; connectivity 6/6.
- **Migration evidence:** the version-2 database fixture was opened through the production Room factory and migrated to schema v3 on both APIs. The test confirms the existing trusted-peer record and conversation/message/outbox/crypto-state records remain available, new trusted-peer profile fields default safely, and the local profile begins with the defined empty/default state.
- **Debug APK:** artifact [14247532067](https://github.com/azad2022/shareit/actions/runs/43910295903/artifacts/14247532067); 15,088,036 bytes; SHA-256 `a429ce8f831c6f2bdf5b4372a8fe7e386e8c31ce69c5c553562230c2c62c9179`. The extracted APK checksum matches the CI-provided `.sha256` file and the APK archive passed ZIP integrity validation. This is a standard Debug build, not a release build.
- **Physical-device matrix:** Galaxy A12 ↔ ASUS X017DA profile exchange has not yet been tested for this milestone. No physical-device, TalkBack, font-scaling, RTL/LTR or OEM-specific profile evidence is claimed.

## Risks and follow-up

- HELLO v2 is incompatible with HELLO v1; both devices must upgrade together. No downgrade path is added.
- Profile data changes do not propagate during an already established connection. A live PROFILE logical channel would require a separately reviewed protocol/UX scope.
- The incoming HELLO avatar path enforces a bounded JPEG envelope and SHA-256 match, but the current receiver path does not decode the image or independently enforce canonical 256×256 dimensions before caching. Close this validation gap and add focused malformed/oversized/hash/dimension tests before considering the profile-security gate complete.
- The exact-head CI above does not replace the protocol-negative matrix (pre-auth profile non-disclosure, malformed HELLO fields, identity-proof binding and version rejection) or two-device/OEM interoperability testing.
- Room migration 2→3 passed automated instrumentation on API 26 and API 36. Physical OEM database/lifecycle validation remains outstanding.
- A profile is presentation data and never a trust indicator; the verified badge continues to mean cryptographic peer authentication only.
