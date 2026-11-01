# PEYVAND project status

- **Product:** PEYVAND (پیوند)
- **Repository:** `azad2022/shareit`
- **Current status:** `IN_PROGRESS`
- **Current checkpoint:** CP-002 — Wi-Fi Direct service discovery
- **Current branch:** `feature/foundation-bootstrap`
- **Last updated:** 2026-11-01 (physical-device feedback and discovery diagnostics)

## Milestones

### CP-001 — Initial Android foundation

- Debug APK and CI passed on foundation commit `b9523e084382b2efe14c837b3f345bb33e1cb79f`.
- Product owner reported successful installation, Persian selection, and first entry to the app on a physical phone.
- Exact handset model/API and cold-relaunch language-persistence evidence remain unrecorded; CP-001 stays `IN_PROGRESS`.

### CP-002 — Wi-Fi Direct service discovery

- Kotlin/Compose foundation, version-aware runtime permission handling, Wi-Fi Direct capability checks, local DNS-SD advertisement and peer service discovery are implemented on `feature/foundation-bootstrap`.
- The discovery adapter now records the exact asynchronous P2P operation that returns a framework failure; the discovery UI displays that stage. The screen now stops discovery and releases registrations when it is removed from the navigation composition.
- Discovery advertises only the PEYVAND service/schema markers. These are public and spoofable compatibility metadata, not identity or authentication.
- Service responses are accepted only for the exact PEYVAND Bonjour registration type (Android API example format: `_service._tcp.local.`) and a valid PEYVAND instance suffix.
- TXT responses are accepted only when their full DNS-SD domain identifies a valid PEYVAND instance. The service and TXT callbacks are correlated using both the same transient P2P device address and the same normalized service instance name.
- Callback generation is invalidated before failure cleanup. Pending peer records are cleared and P2P service requests/local advertisements are independently cleared on cancellation, active-scan availability loss, P2P disabled state, and failure paths.
- A disconnected P2P channel can be reinitialized during a later foreground availability refresh; failure of the first immediate reinitialization no longer permanently blocks retry until process restart.
- API 33+ uses `NEARBY_WIFI_DEVICES`; API 32 and earlier request FINE and COARSE together, but scanning proceeds only when FINE is granted. Location Mode, P2P enabled state, channel readiness and permission status are checked.
- App-default VPN diagnostics are informational only. A VPN-backed route does not by itself prove that Wi-Fi Direct or local socket traffic is blocked. See ADR-0003.
- No peer socket, pairing, TLS session, receiver consent flow, file transfer, integrity verification, resume, or APK extraction/installation is implemented.
- The module-level `ACCESS_NETWORK_STATE` declaration was added in commit `7abd10a4aebdc1dd94c7158b8f44d080f7b0ec92` after CI exposed that the ConnectivityManager adapter's module manifest lacked the permission.
- The latest source hardening is recorded through code commits ending at `a8518d473e20ca1159b16607284c211893b6e78e`. A completed CI result for this exact source revision had not yet been confirmed when this status was written; the earlier successful run 19 validates a prior source revision only.
- Product-owner physical test on 2026-11-01: Samsung Galaxy A06 reports the app-level `Wi-Fi Direct busy` error; ASUS X017DA remains scanning and finds no PEYVAND peer. This is a failed interoperability observation, not a passing test. Exact Android versions, firmware builds and device logs are still pending.
- The observed Samsung message maps to `WifiP2pManager.BUSY`; the previous source did not expose which P2P operation returned it. Current source head `6e781732afd91b41c331b2c22d5445fc0b3d85df` adds operation-stage reporting; its exact CI result and a retest on both handsets are pending.
- Reciprocal two-device physical discovery remains mandatory before CP-002 can be marked `PASSED`.

## Module boundaries

- `:app`: Android entry point, lifecycle ownership, ViewModels and Compose presentation.
- `:core:domain`: Android-free language, discovery and network-diagnostic models/policies.
- `:core:data`: Preferences DataStore and AppCompat locale adapter.
- `:core:connectivity`: Android Wi-Fi Direct/DNS-SD adapter and app-default network diagnostics.
- `:core:storage`: URI-based MediaStore/SAF catalog and launcher-visible installed-app catalog; this module does not yet extract or send APK bytes.
- `:core:designsystem`: PEYVAND Compose theme and semantic colors.

## Security and distribution limits

- Local networks, advertised service records, device names and transient P2P addresses are untrusted. None is a credential.
- Peer authentication, cryptographically protected transport and explicit receiver consent are required before any future data transfer.
- No file transfer or APK installation exists yet. Debug signing only; no release keystore or signing secrets.
- No account, backend, cloud upload, relay or public-internet requirement for the core transfer goal.
- CP-002 does not require `INTERNET`. A later local-socket transport ADR must explicitly review Android socket permissions and network binding. `ACCESS_NETWORK_STATE` is used only by the separate app-default network diagnostic and does not imply public internet access.

## Open decisions

- Final `minSdk` and supported physical-device matrix (current 26 is provisional).
- Final application ID (current `com.peyvand.app` is provisional).
- Kurdish variant/script and locale tag.
- Fallback transport order if Wi-Fi Direct is unavailable.
- Peer authentication/pairing, local socket binding and protocol framing.
- Transfer durability/resume and background execution model.
- Verified local socket behavior with VPN, split-tunnel, LAN-exception and lockdown configurations.

See `docs/checkpoints/CP-001-FOUNDATION.md`, `docs/checkpoints/CP-002-WIFI-DIRECT-DISCOVERY.md`, and the ADRs under `docs/decisions/` for acceptance criteria, decisions, evidence and remaining risks.
