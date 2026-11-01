# ADR-0001 — Initial Android build and application baseline

- **Status:** Accepted for the foundation milestone; provisional product identifiers and minimum SDK require owner confirmation before distribution.
- **Date:** 2026-11-01
- **Scope:** CP-001 foundation only.

## Context

The repository previously contained product documents but no executable Android project. A reproducible native Android baseline is required before implementing networking, transfer, or package-sharing features.

## Decisions

### Toolchain

| Component | Pinned version | Rationale |
|---|---:|---|
| Android Gradle Plugin | 9.5.0 | Current stable project baseline documented by Android Developers. |
| Gradle Wrapper | 9.7.1 | Exact wrapper JAR checksum is verified against Gradle's published checksum list; Gradle 9.7.1 is newer than AGP 9.5's minimum Gradle 9.6.0. |
| Kotlin | 2.4.10 | Required language; Compose compiler plugin is aligned to the same Kotlin version. |
| Compose BOM | 2026.09.00 | Current stable Compose dependency alignment published by Android Developers. |
| AppCompat | 1.8.0 | Per-app locale support for older Android versions and compatibility with Compose hosting Activity. |
| Activity Compose | 1.13.0 | Stable AndroidX Activity Compose release. |
| Lifecycle | 2.11.0 | Stable ViewModel/lifecycle APIs. |
| DataStore Preferences | 1.3.0 | Asynchronous persistence for the user's selected language. |
| AndroidX Core | 1.19.1 | Current stable core support APIs. |
| JDK | 17 | Conservative supported JVM baseline for Android Gradle builds. |

Versions are pinned in `gradle/libs.versions.toml`; do not use dynamic version selectors.

### SDK levels and identity

- `compileSdk = 37` to compile against Android 17 (API 37).
- `targetSdk = 36` for the first foundation build. Android 17 target behavior, including mandatory large-screen resizability changes for apps targeting API 37, will be handled in an explicit adaptive-UI and compatibility milestone before raising target SDK.
- `minSdk = 26` is a **provisional scaffolding assumption**, not an approved support promise. API 26 was previously discussed as a proposal only. The supported device matrix and Wi-Fi Direct capability coverage must inform the final minimum API decision.
- `applicationId`/namespace `com.peyvand.app` are **provisional scaffolding identifiers**. Confirm ownership, store availability, and final package identity before publication. Do not treat this as an approved distribution identity.
- Version baseline: `versionCode = 1`, `versionName = 0.1.0` for internal Debug development only.

Google Play's current policy (accessed 2026-11-01) requires new mobile apps and updates to target API 36 or higher from August 31, 2026; the API 37 targeting timeline should be rechecked before release. See https://support.google.com/googleplay/android-developer/answer/11926878?hl=en. Android 17 is API 37 and was announced as released on June 16, 2026: https://android-developers.googleblog.com/2026/06/Android-17.html.

### Module boundaries

- `:app`: Android entry point, presentation state, first-run language selection, and explicit dependency composition.
- `:core:domain`: pure Kotlin language model, repository contract, and selection use case; no Android dependency.
- `:core:data`: Preferences DataStore and AndroidX per-app locale adapter implementing the domain contract.
- `:core:designsystem`: Compose Material 3 theme and PEYVAND blue/white semantic palette.

Dependency direction: `:app` depends on core modules; `:core:data` depends on `:core:domain`; `:core:designsystem` is UI-only; `:core:domain` depends on no other project module. Do not add feature modules until stable responsibilities and contracts justify them.

### Localization bootstrap

- Persian is the only selectable language in CP-001 because only Persian UI resources exist.
- All seven target languages are represented in the domain language catalog. The six incomplete translations remain disabled and are not declared as production-supported app locales.
- Persist the explicit user choice in Preferences DataStore. Use AndroidX `AppCompatDelegate.setApplicationLocales` for locale application, with the activity hosted by `AppCompatActivity`.
- Apply the stored locale before calling `setContent`; use an asynchronous DataStore read and allow the Activity recreation required by AppCompat. Do not perform a blocking SharedPreferences read on the main thread.
- `locale_config.xml` exposes only Persian until other complete bundles pass localization acceptance checks.
- The repository's own stored choice takes precedence over the device locale. Settings-based language switching must update the same repository in a later milestone.

### Dependency injection

Use explicit dependency construction in `MainActivity` for this small foundation. No DI framework is introduced before there is enough object-graph complexity to justify one. Keep Android framework dependencies behind core/data adapters.

### Build and distribution

- Debug build only. Do not add a release signing configuration, keystore, signing secret, analytics, cloud, network permission, or transfer permission before a feature requires it.
- CI must run unit tests, Android lint, and `assembleDebug` on a hosted runner with Android SDK 37.
- Gradle Wrapper distribution checksum and wrapper JAR checksum are pinned/verified.

## Consequences and follow-up

- The foundation can evolve without moving Android APIs into domain logic.
- Only Persian is user-selectable until the other translations, layout directions, fonts, and accessibility pass review.
- The min SDK and package ID are intentionally not final decisions; obtain owner approval before release configuration or public distribution.
- Before CP-002, decide the first connectivity ADR topics (Wi-Fi Direct lifecycle, runtime permissions, peer identity/pairing, and capability checks). Do not start sockets or transfer protocol in CP-001.

## References

- AGP versions and Gradle compatibility: https://developer.android.com/build/releases/about-agp
- Compose compiler plugin/Kotlin alignment and Compose BOM: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- Per-app languages and custom storage handling: https://developer.android.com/guide/topics/resources/app-languages
- AndroidX AppCompat releases: https://developer.android.com/jetpack/androidx/releases/appcompat
- AndroidX Activity releases: https://developer.android.com/jetpack/androidx/releases/activity
- AndroidX Lifecycle releases: https://developer.android.com/jetpack/androidx/releases/lifecycle
- AndroidX DataStore releases: https://developer.android.com/jetpack/androidx/releases/datastore
- Android app modularization: https://developer.android.com/topic/modularization
- Gradle distribution and wrapper checksums: https://gradle.org/release-checksums/
