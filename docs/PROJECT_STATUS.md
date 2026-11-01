# PEYVAND project status

- **Product:** PEYVAND (پیوند)
- **Repository:** `azad2022/shareit`
- **Current status:** `IN_PROGRESS`
- **Current checkpoint:** CP-002 — Wi-Fi Direct service discovery
- **Current branch:** `feature/foundation-bootstrap`
- **Last updated:** 2026-11-01 (discovery diagnostics build; two-device retest still failing)

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
- The previous source revision `0363d7ff83ccca019cdf775d293d7f06945d6c7d` passed GitHub Actions run `41294583841`; that validates the prior implementation, not subsequent diagnostics/lifecycle changes.
- Product-owner physical test on 2026-11-01: Samsung Galaxy A12 (SM-A125F, Android 12 / One UI Core 4.1) reports the app-level `Wi-Fi Direct busy` error; ASUS X017DA remains scanning and finds no PEYVAND peer. This is a failed interoperability observation, not a passing test. Samsung model/software identification was corrected using the owner's reported model number and Samsung's official update record: https://doc.samsungmobile.com/SM-A125F/018293210323/eng.html.
- The owner subsequently supplied the exact model `SM-A125F`, which identifies the test handset as Galaxy A12, not Galaxy A06. Samsung's official update record confirms Android 12 and One UI Core 4.1 for this model. The earlier A06 references in this checkpoint were a model-identification error and are superseded by Galaxy A12 / SM-A125F.
- For the reported Android 12 environment (API 31/32), Wi-Fi P2P discovery requires `ACCESS_FINE_LOCATION` at runtime and Location Mode enabled; approximate-only location is insufficient. The app manifest caps the legacy location permissions at API 32 and the UI requests FINE and COARSE together. Official Android reference: https://developer.android.com/develop/connectivity/wifi/wifi-direct.
- The observed Samsung message maps to `WifiP2pManager.BUSY`. The discovery adapter now logs and displays which of its five asynchronous P2P operations failed; this improves diagnosis but does not itself guarantee that the framework BUSY condition is resolved. It also stops the active scan when the discovery screen leaves composition. App-source fixes are committed through `6e781732afd91b41c331b2c22d5445fc0b3d85df`.
- GitHub Actions run `41317903875` passed for branch tree `4251e6df630747b8a78bb7a1161a3cedbd4909ad`: 21 domain unit tests passed (0 failures/errors/skips), all configured lint tasks succeeded with 8 warnings and 0 errors, and `:app:assembleDebug` succeeded. Debug APK artifact `13087339471` was verified; APK SHA-256 `0a20f9f63530774b46d659c9d4523ae9812acf4ab15b78387784a97018d1eb12`.
- The follow-up fix commit `73f030c1e81b9302d08a9d6130f926fb2725d0e1` removes unconditional `clearServiceRequests`/`clearLocalServices` calls from scan startup and avoids cleanup when `stopDiscovery()` is called while idle. CI run `41322529714` completed successfully for this exact commit: 21 domain unit tests passed (0 failures/errors/skips), all configured lint tasks passed with no reported lint errors, and `:app:assembleDebug` completed successfully. Debug APK artifact `13089684423` was extracted and the APK archive validated; SHA-256 `ce77391803c6c23a1c105f2546dc7a102e39a1f7ef3bc6cf5338529c3b08202d`.
- ADR-0004 proposes (but does not approve) a PEYVAND-specific QR/local-only-hotspot alternative for physical compatibility evaluation. No hotspot or QR transport has been implemented.
- Product-owner retest after the `73f030c` Debug APK: the Galaxy A12 and ASUS X017DA were both left actively searching (one in receive mode, one in send mode), but neither device appeared on the other. This confirms the discovery interoperability issue remains unresolved.
- Diagnostics instrumentation commit `58c4d86e655e6f3e6800c845ce4792661d9b07f3` adds visible counters for raw DNS-SD service responses, accepted service responses, TXT responses, accepted TXT domains, compatible TXT records, and final peers. CI run `41327944528` succeeded: 21 unit tests passed (0 failures/errors/skips), lint reported 8 warnings and 0 errors, and `:app:assembleDebug` succeeded. Debug APK artifact `13092725538`; APK SHA-256 `e36988f49f55b05b6e9ec87f119c932f878bc07f042afa1a4e966c15cd123187`.
- The current implementation still contains no Wi-Fi P2P `connect()` flow, local socket transport, TLS peer authentication, recipient consent protocol, or file streaming. The diagnostics APK does not implement file transfer.
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

See `docs/checkpoints/CP-001-FOUNDATION.md`, `docs/checkpoints/CP-002-WIFI-DIRECT-DISCOVERY.md`, and the ADRs under `docs/decisions/` (including proposed ADR-0004) for acceptance criteria, decisions, evidence and remaining risks.
