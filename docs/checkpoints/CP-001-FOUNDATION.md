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
- [ ] Android 17 SDK install uses the published `platforms;android-37.0` package and verifies the AGP-compatible platform path.
- [ ] `testDebugUnitTest` succeeds; language catalogue/locale-resolution tests pass.
- [ ] `lintDebug` succeeds without errors.
- [ ] Android modules use AGP built-in Kotlin without applying the legacy `org.jetbrains.kotlin.android` plugin.
- [ ] Module dependency graph matches `docs/architecture/FOUNDATION.md` and has no cycles.
- [ ] Language preference is stored asynchronously; first-run selector is shown only before an explicit choice.
- [ ] Persian is selectable; incomplete languages are visibly disabled and excluded from `locale_config.xml`.
- [ ] PEYVAND blue/white theme is centralized in `:core:designsystem`.
- [ ] No Wi-Fi/transfer/security/installation implementation, release signing, backend, or unrelated permissions are added.
- [ ] README and project status describe the actual scope and outstanding risks.

## Implemented scope (initial commit)

Implemented on `feature/foundation-bootstrap` (not merged to `main`).

- Initial foundation commit: `949a95523831013bf35e8ebb844c523e5ce6b2b9`.
- Gradle wrapper executable-mode correction: `8284210288c561f0d9d3f5c9fa502a1795843188`.
- AGP built-in Kotlin, canonical Kotlin source roots, and CI setup correction: `202ce736a53a3416a48571dd9d6967968cb2e2c9`.
- Android 17 SDK package-name and SDK-path compatibility correction: `765e0ba163c269af3a3fbd54dfafb33d7b5b759b`.

Files created include the root Gradle configuration/version catalog/wrapper, four module build files, Android manifest/resources, first-run language flow, DataStore language repository, domain language catalogue/use cases, Compose design-system theme, unit tests, Android CI workflow, README, ADR-0001, foundation architecture, and project status.

The feature scope intentionally excludes all discovery, network, security, file-transfer, and APK work.

## Test evidence

Local build: not run. The available remote workbench has no `java`, `gradle`, `ANDROID_HOME`, or `ANDROID_SDK_ROOT` installation.

GitHub Actions:

- Run `41278935375` (initial foundation): failed before Gradle at Android SDK setup because the setup action attempted to install the removed SDK package `tools`.
- Run `41278975695` (wrapper executable fix): same SDK setup failure, before Gradle.
- Run `41279407754` (AGP/Kotlin and CI correction): SDK setup passed, then failed because the workflow requested `platforms;android-37`, while the Android 17 SDK package is named `platforms;android-37.0`.
- Run `41279528083` (latest): `Set up Android SDK`, `Install Android 17 SDK platform`, and `Validate Gradle wrapper and build` succeeded. The command step `:core:domain:testDebugUnitTest`, module lint tasks, and `:app:assembleDebug` was still `in_progress` at the last check (2026-11-01 06:08 UTC). No test/build conclusion is available yet.

Latest run: https://github.com/azad2022/shareit/actions/runs/41279528083

The test/build acceptance criteria remain unchecked until the latest workflow reaches a completed success. No test is reported as passed based on an in-progress job.

## Risks and deferred decisions

- `minSdk = 26` is a provisional scaffolding assumption only; obtain explicit owner approval after device coverage and Wi-Fi Direct capability analysis.
- `com.peyvand.app` is a provisional application ID/namespace only; confirm identity and ownership before any store submission.
- Kurdish script/variant remains unresolved; the Kurdish option is disabled and has no locale tag.
- No physical-device test has been run in CP-001.
- No real discovery, pairing, transfer, integrity verification, resume, APK handling, or performance benchmarking is implemented in CP-001.
- Google Play/API target requirements must be rechecked before a release milestone.

## Commit and source-of-truth verification

- Latest known branch commit: `765e0ba163c269af3a3fbd54dfafb33d7b5b759b`.
- The branch tip was verified through the Git reference API.
- The `gradlew` file mode was verified as `100755` in the branch tree.
- Key app, DataStore adapter, and checkpoint files were read back from GitHub after commit.
- Branch URL: https://github.com/azad2022/shareit/tree/feature/foundation-bootstrap

## Status rule

Keep this checkpoint `IN_PROGRESS` until every applicable acceptance criterion has reproducible evidence. Use `BLOCKED` if a mandatory criterion cannot be completed; never mark it `PASSED` merely because files were committed.
