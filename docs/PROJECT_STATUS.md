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
- Exact handset model/API and cold-relaunch language-persistence evidence have not yet been recorded; keep CP-001 `IN_PROGRESS`.

### CP-002 — Wi-Fi Direct service discovery

- Native foundation, Compose discovery surface, runtime capability reporting, and Wi-Fi Direct DNS-SD service discovery are implemented on `feature/foundation-bootstrap`.
- The discovery list filters PEYVAND's public compatibility marker. It is not peer authentication and does not establish trust.
- API 33+ uses `NEARBY_WIFI_DEVICES`; API 32 and earlier request FINE and COARSE together but proceed only with FINE granted. Location Mode and P2P state are checked; the radar is driven by actual discovery state.
- Adjacent network diagnostics report whether PEYVAND's app-default network is VPN-backed. This produces a non-blocking warning; VPN presence alone does not prove local Wi-Fi Direct or future P2P socket traffic is blocked. See ADR-0003.
- No peer socket, pairing, TLS session, file transfer, integrity verification, resume, or APK handling is implemented.
- Latest completed CI run before the current fix: run `41285857803` (commit `e21b07a8345657eee7a055e2d81e9c0c3c01bf61`) failed `:core:connectivity:lintDebug` because `ACCESS_NETWORK_STATE` was declared in the app manifest but not the connectivity library manifest.
- Corrective change: commit `7abd10a4aebdc1dd94c7158b8f44d080f7b0ec92` adds that permission to `core/connectivity/src/main/AndroidManifest.xml`. The file was read back from GitHub (blob `7425a54c56618a73f28a34c3818a04bde8d71b1c`). CI run `41287911881` was queued at the time of this status update; its result is not yet known.
- Two-device physical discovery validation remains mandatory before CP-002 can be marked `PASSED`.

## Module boundaries

- `:app`: Android entry point, lifecycle ownership, ViewModels, and Compose presentation.
- `:core:domain`: Android-free language, discovery, and network-diagnostic models/policies.
- `:core:data`: Preferences DataStore and AppCompat locale adapter.
- `:core:connectivity`: Android Wi-Fi Direct/DNS-SD adapter and app-default network diagnostics.
- `:core:designsystem`: PEYVAND Compose theme and semantic colors.

## Security and distribution limits

- Local network and discovery records are untrusted; device names and advertised compatibility fields are not credentials.
- Receiver consent, peer authentication, encrypted transport, and integrity verification must precede any future transfer.
- No file transfer or APK installation exists yet. No release keystore or signing secrets; Debug signing only.
- No account, backend, cloud upload, relay, or public-internet requirement for the core transfer goal.
- CP-002 does not require `INTERNET`; a later local-socket transport ADR must review Android socket requirements explicitly. `ACCESS_NETWORK_STATE` is used only for the separate app-default network diagnostic and does not imply public internet access.

## Open decisions

- Final `minSdk` and supported physical-device matrix (current 26 is provisional).
- Final application ID (current `com.peyvand.app` is provisional).
- Kurdish variant/script and locale tag.
- Fallback transport order if Wi-Fi Direct is unavailable.
- Peer authentication/pairing, local socket binding, and protocol framing.
- Transfer durability/resume and background execution model.
- Verified local socket behavior while VPN configurations are active.

See `docs/checkpoints/CP-001-FOUNDATION.md`, `docs/checkpoints/CP-002-WIFI-DIRECT-DISCOVERY.md`, and `docs/decisions/ADR-0003-VPN-ROUTE-DIAGNOSTICS.md` for acceptance criteria, evidence, and remaining risks.
