# CP-002 — Wi-Fi Direct service discovery

- **Status:** `IN_PROGRESS`
- **Started:** 2026-11-01
- **Branch:** `feature/foundation-bootstrap`
- **Implementation authorization:** Explicitly granted by the product owner in the conversation on 2026-11-01.
- **Depends on:** CP-001 foundation (installed and opened by the product owner; full CP-001 persistence acceptance remains open).

## Objective

Implement a first real nearby-device discovery path using Android Wi-Fi Direct DNS-SD. Show only compatible PEYVAND advertisements, request the correct runtime permission, report platform capability blockers, and release P2P resources when scanning stops or the app leaves the foreground. Present VPN route diagnostics as informational context only; they must not be used as a substitute for testing local connectivity.

## Acceptance criteria

- [ ] `:core:connectivity` is a focused Android adapter module and the dependency graph remains acyclic.
- [ ] Domain discovery contracts and API-level permission policy are Android-free and covered by unit tests.
- [ ] Android 13+ requests only `NEARBY_WIFI_DEVICES`; API 32 and earlier declare/request `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` together, while discovery proceeds only if FINE is granted.
- [ ] Wi-Fi Direct hardware is optional in the manifest and checked at runtime.
- [ ] Location Mode, P2P enabled state, channel readiness, and permission status are represented accurately; unknown P2P state remains CHECKING until the sticky system state is observed.
- [ ] DNS-SD advertisement contains only PEYVAND application/schema compatibility fields and a process-local collision-resistant instance suffix.
- [ ] A displayed peer has both a matching service callback and matching TXT callback.
- [ ] No peer is described as authenticated; no socket, transport, or file transfer is added in CP-002.
- [ ] Scan cancellation, Activity stop, permission loss, and channel loss clear pending peer data and release system registrations.
- [ ] Compose UI exposes loading, idle, scanning, empty, permission-denied, Location-disabled, unsupported, and error states.
- [ ] Radar animation is driven only by real discovery state and respects Android animation-disable settings.
- [ ] Persian UI strings remain in resources; mixed-direction device names are bidi-safe.
- [ ] VPN route diagnosis observes only the app-default network and shows a non-blocking warning; it does not block discovery or assert that local traffic is blocked.
- [ ] `ACCESS_NETWORK_STATE` is declared by the connectivity module that calls ConnectivityManager APIs.
- [ ] CI unit tests, all module lint tasks, and Debug APK build pass.
- [ ] Two physical Android devices with model/API details demonstrate reciprocal PEYVAND advertisements before the checkpoint is marked PASSED.

## Implemented scope

- Kotlin/Compose Android foundation with language bootstrap and PEYVAND design-system entry point.
- Wi-Fi Direct DNS-SD local service advertisement and discovery with PEYVAND compatibility filtering.
- Version-aware discovery permission checks, optional hardware check, Location Mode handling, P2P state observer, bounded pending peer records, and lifecycle cleanup.
- Discovery UI states and real-state radar feedback.
- App-default VPN route diagnostics with a non-blocking Persian warning and user-directed VPN settings shortcut.
- No socket, pairing, TLS, file transfer, integrity verification, resume, or APK handling.

## Test evidence

- CI run `41283231293`: domain/data/design-system/connectivity source compilation and the then-current 9 unit tests passed; app Kotlin compilation failed on the initial radar animation-spec type.
- CI run `41283693851`: the animation API mismatch remained and was diagnosed.
- CI run `41284094525`: Kotlin compilation, domain tests, core module lint, and `:app:assembleDebug` completed; `:app:lintDebug` failed on the legacy `CoarseFineLocation` manifest rule.
- CI run `41284620513`: the legacy permission correction was committed; run failed. Its failure was superseded by the next identified error.
- CI run `41285857803` on commit `e21b07a8345657eee7a055e2d81e9c0c3c01bf61`: failed with two `MissingPermission` lint errors for `ACCESS_NETWORK_STATE` at `registerDefaultNetworkCallback` and `getActiveNetwork` in `AndroidNetworkEnvironmentRepository.kt`. The permission existed in the app manifest but not in `:core:connectivity`'s manifest.
- Fix commit `7abd10a4aebdc1dd94c7158b8f44d080f7b0ec92`: created `core/connectivity/src/main/AndroidManifest.xml` declaring `ACCESS_NETWORK_STATE`. GitHub read-back verified the file (blob `7425a54c56618a73f28a34c3818a04bde8d71b1c`).
- CI run `41287911881` for the fix commit was queued when this checkpoint was updated. Do not treat it as passed until its completed result is inspected.
- Local build: not run in the current workbench.
- Physical-device discovery test results: pending. Emulator-only evidence cannot replace the required two-device test.

## Risks and deferred work

- Wi-Fi P2P support and DNS-SD behavior are vendor/chipset/OS dependent.
- Current `minSdk=26` and application ID `com.peyvand.app` remain provisional.
- Discovery service metadata is forgeable; peer authentication is not implemented.
- VPN route status is not a local-network reachability test. VPN-on/off, split-tunnel, LAN exception, and lockdown cases must be tested against the future socket transport.
- Local socket connection, TLS, pairing, receiver consent, transfer protocol, files, APKs, and resume are not implemented.
- No fallback transport is selected by this checkpoint.

## Source verification

- Permission correction commit: `7abd10a4aebdc1dd94c7158b8f44d080f7b0ec92`.
- Manifest read-back blob: `7425a54c56618a73f28a34c3818a04bde8d71b1c`.
- CI run after correction: `41287911881` (queued at checkpoint update; result pending).
- Physical two-device evidence: pending.

## Status rule

Keep `IN_PROGRESS` until every applicable criterion has reproducible evidence. Use `BLOCKED` if a mandatory criterion cannot be completed; never mark it `PASSED` merely because files were committed.
