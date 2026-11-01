# PEYVAND (پیوند)

Native Android application for discovering nearby PEYVAND devices and securely transferring user-selected files and eligible Android application packages directly between devices.

- Product name: **PEYVAND** (Persian: **پیوند**)
- Repository name remains: `azad2022/shareit`
- Language: Kotlin
- UI: Jetpack Compose
- Brand direction: blue and white
- Primary connectivity target: offline peer-to-peer using Wi-Fi Direct where supported

## Current development status

The project has the native Android foundation and is implementing **CP-002 — Wi-Fi Direct service discovery**. The app can inspect runtime capability/permission state and discover compatible PEYVAND DNS-SD advertisements. Discovery is not authentication. Peer connection and file transfer are not implemented yet.

## Modules

- `:app` — Android entry point and Compose presentation.
- `:core:domain` — Android-free language and discovery contracts/policies.
- `:core:data` — Android persistence and locale adapter.
- `:core:connectivity` — Wi-Fi Direct DNS-SD platform adapter.
- `:core:designsystem` — PEYVAND theme and semantic colors.

## Build requirements

- JDK 17
- Android SDK Platform 37
- Gradle Wrapper (pinned distribution and checksum)

Build the Debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests, lint, and Debug build:

```bash
./gradlew :core:domain:testDebugUnitTest lintDebug assembleDebug
```

## Project documentation

- Engineering skill: `.agents/skills/shareit-android/SKILL.md`
- Product requirements: `docs/PRODUCT_REQUIREMENTS.md`
- Identity, localization, and motion: `docs/PRODUCT_IDENTITY_AND_LOCALIZATION.md`
- Foundation/connectivity architecture: `docs/architecture/FOUNDATION.md`
- Project status: `docs/PROJECT_STATUS.md`
- Checkpoints: `docs/checkpoints/`
- Architecture decisions: `docs/decisions/`

No release signing material belongs in this repository. Do not treat the provisional `minSdk` or application ID as approved distribution decisions.
