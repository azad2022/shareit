# CP-001 — Initial Android foundation

- **Status:** `IN_PROGRESS`
- **Started:** 2026-11-01
- **Branch:** `feature/foundation-bootstrap`
- **Foundation verification commit:** `b9523e084382b2efe14c837b3f345bb33e1cb79f`
- **Implementation authorization:** Explicitly granted by the product owner in the conversation on 2026-11-01.

## Objective

Create the first reproducible native Android project foundation for PEYVAND (پیوند), with clean module boundaries, pinned toolchain, Persian-first language selection/persistence, the blue-and-white design-system entry point, CI, and a documented checkpoint. No transfer features are part of CP-001.

## Acceptance criteria

- [x] Gradle Wrapper is committed, checksum-pinned, and executable on Unix.
- [x] AGP, Kotlin/Compose compiler, Gradle, AndroidX, and JDK baseline are pinned and documented.
- [x] Project `assembleDebug` succeeds on GitHub Actions.
- [x] Android 17 SDK install uses `platforms;android-37.0` and validates the AGP-compatible path.
- [x] `:core:domain:testDebugUnitTest` succeeds.
- [x] Module lint tasks succeed without errors on the final CP-001 validation run.
- [x] Android modules use AGP built-in Kotlin; legacy `org.jetbrains.kotlin.android` is not applied.
- [x] Module graph matches the foundation architecture and is acyclic.
- [x] Language preference is stored asynchronously; startup handles preference corruption/read errors.
- [ ] First-run selector is not shown after an explicitly selected language on a cold relaunch (physical re-launch verification pending).
- [x] Persian is selectable; incomplete languages are visibly disabled and excluded from `locale_config.xml`.
- [x] PEYVAND blue/white theme is centralized in `:core:designsystem`.
- [x] The CP-001 baseline contains no Wi-Fi/transfer/security/installation implementation, release signing, backend, or unrelated permissions.
- [x] README and project status documented the CP-001 foundation scope.

## Implemented scope

- Initial foundation: `949a95523831013bf35e8ebb844c523e5ce6b2b9`.
- Gradle wrapper executable-mode correction: `8284210288c561f0d9d3f5c9fa502a1795843188`.
- AGP built-in Kotlin, source-root, and CI corrections: `202ce736a53a3416a48571dd9d6967968cb2e2c9`.
- Android 17 SDK package/path correction: `765e0ba163c269af3a3fbd54dfafb33d7b5b759b`.
- Compile smart-cast correction: `71538591fcc2a3473920b26ba3388781dc2cfac1`.
- Startup error handling, DataStore corruption recovery, and launcher icon: `d0096698757314297d5b4cd00710b2e48a69165b`.
- Adaptive icon resource/layer correction: `b9523e084382b2efe14c837b3f345bb33e1cb79f`.

## Test evidence

- GitHub Actions run `41281083149` on commit `b9523e084382b2efe14c837b3f345bb33e1cb79f`: completed successfully; `testDebugUnitTest`, all CP-001 lint tasks, and `:app:assembleDebug` succeeded. The debug APK and validation reports were uploaded.
- Product owner reports that the resulting APK installed on a physical phone, Persian selection succeeded, and the app entered the first home surface without an observed error.
- Exact device model, Android API level, and cold-relaunch persistence result have not yet been recorded.
- Local build was not run in this workbench; the hosted CI runner supplied the reproducible build evidence.

## Deferred decisions and risks

- `minSdk = 26` remains a provisional scaffolding assumption; confirm against device coverage and Wi-Fi Direct capability analysis.
- `com.peyvand.app` remains a provisional application ID/namespace; confirm ownership and store identity before publication.
- Kurdish script/variant remains unresolved; the Kurdish option is disabled and has no locale tag.
- Cold-relaunch confirmation of saved-language persistence is still pending.
- CP-002 separately covers discovery. No pairing, authenticated transport, transfer, integrity verification, resume, APK handling, or performance benchmark belongs to CP-001.
- Google Play/API target requirements must be rechecked before release.

## Source verification

- CP-001 baseline commit: `b9523e084382b2efe14c837b3f345bb33e1cb79f`.
- Branch at baseline: `feature/foundation-bootstrap`.
- Gradle wrapper file mode was verified as `100755`.
- Final CP-001 CI run: https://github.com/azad2022/shareit/actions/runs/41281083149

## Status rule

Keep `IN_PROGRESS` while the cold-relaunch acceptance item is open. Do not mark `PASSED` until the selected language persists across a repeat launch and that result is recorded with device/API details.
