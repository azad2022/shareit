# PEYVAND (پیوند)

Native Android application for discovering nearby devices and securely transferring user-selected files and eligible Android application packages directly between devices.

- Product name: **PEYVAND** (Persian: **پیوند**)
- Repository name remains: `azad2022/shareit`
- Language: Kotlin
- UI: Jetpack Compose
- Brand direction: blue and white
- Core connectivity target: offline peer-to-peer, with Wi-Fi Direct as the primary transport

## Current development status

The project is in **CP-001 — Initial Android foundation**. The current milestone establishes the Gradle/Kotlin/Compose project, module boundaries, Persian-first language preference flow, and design-system baseline. Device discovery and transfer are not implemented yet.

## Modules

- `:app` — Android entry point and presentation.
- `:core:domain` — Android-independent language and domain contracts.
- `:core:data` — Android persistence and platform adapters.
- `:core:designsystem` — PEYVAND Compose theme and semantic colors.

## Build requirements

- JDK 17
- Android SDK Platform 37
- Gradle Wrapper (pinned distribution and checksum)

Build the Debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests and lint:

```bash
./gradlew testDebugUnitTest lintDebug
```

## Project documentation

- Engineering skill: `.agents/skills/shareit-android/SKILL.md`
- Product requirements: `docs/PRODUCT_REQUIREMENTS.md`
- Identity, localization, and motion: `docs/PRODUCT_IDENTITY_AND_LOCALIZATION.md`
- Foundation architecture: `docs/architecture/FOUNDATION.md`
- Project status: `docs/PROJECT_STATUS.md`
- Checkpoints: `docs/checkpoints/`
- Architecture decisions: `docs/decisions/`

No release signing material belongs in this repository. Do not treat the provisional `minSdk` or application ID as approved distribution decisions.
