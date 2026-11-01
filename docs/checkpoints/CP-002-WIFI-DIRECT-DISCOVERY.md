# CP-002 — Wi-Fi Direct service discovery

- **Status:** `IN_PROGRESS`
- **Started:** 2026-11-01
- **Branch:** `feature/foundation-bootstrap`
- **Implementation authorization:** Explicitly granted by the product owner in the conversation on 2026-11-01.
- **Depends on:** CP-001 foundation (installed and opened by the product owner; full CP-001 persistence acceptance remains open).

## Objective

Implement a first real nearby-device discovery path using Android Wi-Fi Direct DNS-SD. Show only compatible PEYVAND advertisements, request the correct runtime permission, report platform capability blockers, and release P2P resources when scanning stops or the app leaves the foreground.

## Acceptance criteria

- [ ] `:core:connectivity` is a focused Android adapter module and the dependency graph remains acyclic.
- [ ] Domain discovery contracts and API-level permission policy are Android-free and covered by unit tests.
- [ ] Android 13+ uses `NEARBY_WIFI_DEVICES`; API 32 and earlier use `ACCESS_FINE_LOCATION`.
- [ ] Wi-Fi Direct hardware is optional in the manifest and checked at runtime.
- [ ] Location Mode, P2P enabled state, channel readiness, and permission status are represented accurately.
- [ ] DNS-SD advertisement contains only PEYVAND application/schema compatibility fields.
- [ ] A displayed peer has both a matching service callback and matching TXT callback.
- [ ] No peer is described as authenticated; no socket, transport, or transfer is added in CP-002.
- [ ] Scan cancellation, activity stop, permission loss, and channel loss clear pending peer data and release system registrations.
- [ ] Compose UI exposes loading, idle, scanning, empty, permission-denied, Location-disabled, unsupported, and error states.
- [ ] Radar animation is driven only by real discovery state and respects Android animation-disable settings.
- [ ] Persian UI strings remain in resources; mixed-direction device names are bidi-safe.
- [ ] CI unit tests, all module lint tasks, and Debug APK build pass.
- [ ] Two physical Android devices with model/API details demonstrate reciprocal PEYVAND advertisements before the checkpoint is marked PASSED.

## Implemented scope

To be updated after source and CI read-back. This milestone must not silently expand into peer connection or file transfer.

## Test evidence

- Local build: not run in the current remote workbench.
- Unit/instrumentation/physical-device results: pending.
- Emulator-only evidence, if later added, cannot replace the required physical-device test.

## Risks and deferred work

- Wi-Fi P2P support and DNS-SD behavior are vendor/chipset/OS dependent.
- Current `minSdk=26` and application ID `com.peyvand.app` remain provisional.
- Discovery service metadata is forgeable; peer authentication is not implemented.
- Local socket connection, TLS, pairing, receiver consent, transfer protocol, files, APKs, and resume are not implemented.
- No fallback transport is selected by this checkpoint.

## Source verification

- Commit SHA: pending.
- CI run ID and artifact: pending.
- Read-back of committed files: pending.

## Status rule

Keep `IN_PROGRESS` until every applicable criterion has reproducible evidence. Use `BLOCKED` if a mandatory criterion cannot be completed; never mark it `PASSED` merely because files were committed.
