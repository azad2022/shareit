# PEYVAND project status

- **Product:** PEYVAND (پیوند)
- **Repository:** `azad2022/shareit`
- **Current status:** `IN_PROGRESS`
- **Current checkpoint:** CP-002 — Wi-Fi Direct service discovery
- **Current branch:** `feature/foundation-bootstrap`
- **Last updated:** 2026-11-01

## Milestones

### CP-001 — Initial Android foundation

- Debug APK built and CI passed on commit `b9523e084382b2efe14c837b3f345bb33e1cb79f`.
- Product owner reports successful installation, Persian selection, and first entry to the app on a physical phone.
- The exact phone model/API and a cold-relaunch persistence check have not yet been reported; keep CP-001 `IN_PROGRESS` until those details are verified.

### CP-002 — Wi-Fi Direct service discovery

- Implementing version-aware runtime permission checks and Wi-Fi Direct DNS-SD discovery.
- Device list filters for the PEYVAND service marker; this is not peer authentication.
- No socket, pairing, TLS, file transfer, or APK handling yet.
- CP-002 builds are currently being validated; latest failure was app lint's legacy Coarse/Fine location permission rule. The permission request/manifest correction is in progress.
- Requires physical two-device validation across API/manufacturer combinations before discovery can be called working.

## Module boundaries

- `:app`: Activity lifecycle, ViewModels, Compose presentation, explicit composition.
- `:core:domain`: Android-free language and device-discovery contracts/policies.
- `:core:data`: Preferences DataStore and AppCompat locale adapter.
- `:core:connectivity`: Android Wi-Fi Direct/DNS-SD platform adapter.
- `:core:designsystem`: PEYVAND Compose theme and semantic colors.

## Security and distribution limits

- Local network is untrusted; discovery metadata and device names are not credentials.
- No authenticated connection, TLS session, file transfer, or resume is implemented yet.
- Debug signing only; no release keystore or signing secrets.
- No backend, account, cloud upload, relay, or public-internet requirement for the core transfer goal.
- No `INTERNET` permission is added in CP-002; reassess for local sockets in a later transport ADR.

## Open decisions

- Final `minSdk` and supported physical-device matrix (current `26` is provisional).
- Final application ID (current `com.peyvand.app` is provisional).
- Kurdish variant/script and locale tag.
- Fallback transport order if Wi-Fi Direct is unavailable.
- Peer authentication/pairing and local socket protocol.
- Transfer durability/resume and background execution model.

See `docs/checkpoints/CP-001-FOUNDATION.md` and `docs/checkpoints/CP-002-WIFI-DIRECT-DISCOVERY.md` for acceptance criteria, evidence, and remaining risks.
