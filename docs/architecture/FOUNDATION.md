# PEYVAND Android foundation and connectivity architecture

## Current implementation scope

The repository currently contains the native Android foundation and the first connectivity vertical slice:

- CP-001: Gradle/Kotlin/Compose foundation, Persian-first locale bootstrap, and the PEYVAND design-system entry point.
- CP-002: runtime Wi-Fi Direct availability checks, version-aware permissions, and PEYVAND DNS-SD service discovery.

CP-002 discovers PEYVAND service advertisements. It does **not** connect peers, authenticate peers, create sockets, or transfer files. A discovered advertisement is untrusted input and is not proof of identity.

## Project modules

```text
:app
 ├── :core:domain
 ├── :core:data ─────────> :core:domain
 ├── :core:connectivity ─> :core:domain
 └── :core:designsystem
```

- `:app` owns Activity entry, Compose presentation, lifecycle ownership, and explicit composition of implementations.
- `:core:domain` contains Android-free language and discovery models, repository contracts, and policies. It exposes `StateFlow` in its repository contract and must not import Android or Compose APIs.
- `:core:data` owns Preferences DataStore and AppCompat per-app locale integration.
- `:core:connectivity` owns the Android `WifiP2pManager` adapter, P2P state receiver, DNS-SD service advertisement and discovery, and runtime capability reporting. It does not depend on app presentation.
- `:core:designsystem` owns PEYVAND semantic colors and the Compose theme.

Keep the project graph acyclic. Add security, transfer, storage, and package-sharing modules only when their contracts and ownership are defined in ADRs.

## CP-002 Wi-Fi Direct discovery lifecycle

1. The Activity starts monitoring while the app is foregrounded and releases monitoring when it stops.
2. The connectivity adapter initializes a Wi-Fi P2P channel and observes the P2P enabled/disabled system state.
3. Before a scan, it checks hardware/API availability, the runtime permission appropriate to the Android version, and Location Mode because Android requires it for P2P service discovery.
4. The user explicitly starts a scan. The app advertises a minimal PEYVAND DNS-SD service record and registers a matching service request.
5. DNS-SD service and TXT callbacks are correlated only within the active scan. A peer is shown only when both callback records identify the expected PEYVAND service and discovery schema.
6. Cancelling, leaving the foreground, revoking permission, or losing the P2P channel invalidates the scan generation, clears pending peer data, and removes registered requests/services.
7. The UI renders actual platform state. A scan callback or animation never fabricates a discovered device.

The service TXT fields are public, spoofable compatibility markers. They contain no file names, account identifiers, long-lived secrets, or cryptographic identity.

## Permission model

- Android 13/API 33 and later: `NEARBY_WIFI_DEVICES` runtime permission with `neverForLocation`.
- Android 12L/API 32 and earlier: `ACCESS_FINE_LOCATION` runtime permission, capped at API 32.
- P2P system access: `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE`.
- Wi-Fi Direct hardware is declared optional; capability is checked at runtime.
- No `INTERNET` permission is added for this discovery-only milestone. Local socket APIs and the INTERNET permission belong to a later, reviewed transport milestone; INTERNET permission does not imply public-internet dependency.

## Deferred responsibilities

No peer connection, pairing, TLS, local socket, transfer protocol, file selection, file streaming, integrity verification, resume, APK extraction, PackageInstaller, or background transfer is implemented in CP-002. These responsibilities require later ADRs, threat review, and physical-device interoperability tests.
