# PEYVAND Android foundation architecture

## Current scope

CP-001 establishes project/build structure, language preference persistence, the first-run language gate, and the visual foundation. It does not implement device discovery, Wi-Fi Direct, pairing, sockets, encryption, file streaming, APK extraction, installation, or transfer history.

## Project modules

```text
:app
 ├── :core:domain
 ├── :core:data ───────> :core:domain
 └── :core:designsystem
```

- `:app` owns Activity entry, presentation state, Compose screen routing, and composition of implementations.
- `:core:domain` contains the `AppLanguage` target catalogue, `LanguageRepository` contract, and language-selection use cases. It is pure Kotlin/JVM and must stay independent of Android and Compose.
- `:core:data` owns the Android-specific Preferences DataStore implementation and AppCompat locale adapter. It implements the domain contract; it must not depend on app presentation.
- `:core:designsystem` owns semantic PEYVAND colors and the Compose theme. It contains no business or transfer logic.

Keep the dependency graph acyclic. Add connectivity, security, transfer, file access, and package-sharing modules only when their domain contracts and implementation ownership are defined in an ADR.

## Language startup lifecycle

1. `MainActivity` creates the language repository and asynchronously reads Preferences DataStore.
2. A saved language wins. If there is no saved choice, Persian is applied as the initial UI locale but is not recorded as a user choice.
3. If the AppCompat locale differs from the desired locale, `AppCompatDelegate.setApplicationLocales` is called from the `AppCompatActivity`; the Activity recreation completes before Compose content is exposed.
4. `MainViewModel` starts in the language-selection state when no explicit choice exists, otherwise it starts in Home.
5. The language list is sourced from the domain catalogue. Only languages with a complete resource bundle are selectable.
6. On confirmation, the use case validates readiness, DataStore persists the choice, and AppCompat applies the locale.

The initial release exposes only `fa` in `locale_config.xml`. Other target languages must not become system-selectable until their resources and layout direction are complete.

## UI state ownership

`MainViewModel` owns the current onboarding/home state and selection transaction state. Compose renders immutable state and emits a selection intent. UI code does not access DataStore or AppCompat directly. `MainActivity` owns app startup and the composition root; the repository owns persistence and the Android locale adapter.

## Motion

CP-001 uses a short, state-driven transition between language selection and the foundation home surface. It is navigation feedback, not a representation of network discovery or file-transfer progress. Transfer-related motion must be added only with real domain state in later milestones.

## Deferred responsibilities

No transport APIs, permissions, background services, crypto, local socket protocols, file access, PackageManager visibility declarations, or PackageInstaller integration are introduced in this milestone. These require dedicated threat models, ADRs, and physical-device validation.
