# CP-001 — Initial Android foundation

- **Status:** `IN_PROGRESS`
- **Started:** 2026-11-01
- **Branch:** `feature/foundation-bootstrap`
- **Base commit:** `bcb2510b9868c9ae48a8e8a3402e68570bacdf3f`
- **Implementation authorization:** Explicitly granted by the product owner in the conversation on 2026-11-01.

## Objective

Create the first reproducible native Android project foundation for PEYVAND (پیوند), with clean module boundaries, pinned toolchain, Persian-first language selection/persistence, the blue-and-white design-system entry point, CI, and a documented checkpoint. Do not implement transfer features in this checkpoint.

## Acceptance criteria

- [ ] Gradle Wrapper is committed, checksum-pinned, and executable on Unix.
- [ ] AGP, Kotlin, Compose, Gradle, AndroidX, and JDK baseline are pinned and documented.
- [ ] Project syncs and `assembleDebug` succeeds on the CI runner.
- [ ] `testDebugUnitTest` succeeds; language catalogue/locale-resolution tests pass.
- [ ] `lintDebug` succeeds without errors.
- [ ] Module dependency graph matches `docs/architecture/FOUNDATION.md` and has no cycles.
- [ ] Language preference is stored asynchronously; first-run selector is shown only before an explicit choice.
- [ ] Persian is selectable; incomplete languages are visibly disabled and excluded from `locale_config.xml`.
- [ ] PEYVAND blue/white theme is centralized in `:core:designsystem`.
- [ ] No Wi-Fi/transfer/security/installation implementation, release signing, backend, or unrelated permissions are added.
- [ ] README and project status describe the actual scope and outstanding risks.

## Implemented scope (initial commit)

Pending code commit SHA and GitHub Actions result. This section will be completed after read-back and CI verification.

## Test evidence

Pending. Tests are not considered passed until a completed GitHub Actions run reports success for `testDebugUnitTest`, `lintDebug`, and `assembleDebug` on a named runner with Android SDK 37 and JDK 17.

## Risks and deferred decisions

- `minSdk = 26` is a provisional scaffolding assumption only; obtain explicit owner approval after device coverage and Wi-Fi Direct capability analysis.
- `com.peyvand.app` is a provisional application ID/namespace only; confirm identity and ownership before any store submission.
- Kurdish script/variant remains unresolved; the Kurdish option is disabled and has no locale tag.
- No physical-device test has been run in CP-001.
- No real discovery, pairing, transfer, integrity verification, resume, APK handling, or performance benchmarking is implemented in CP-001.
- Google Play/API target requirements must be rechecked before a release milestone.

## Status rule

Keep this checkpoint `IN_PROGRESS` until every applicable acceptance criterion has reproducible evidence. Use `BLOCKED` if a mandatory criterion cannot be completed; never mark it `PASSED` merely because files were committed.
