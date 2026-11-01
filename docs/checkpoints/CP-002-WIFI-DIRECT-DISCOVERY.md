# CP-002 — Wi-Fi Direct service discovery

- **Status:** `IN_PROGRESS`
- **Started:** 2026-11-01
- **Branch:** `feature/foundation-bootstrap`
- **Implementation authorization:** Explicitly granted by the product owner in the conversation on 2026-11-01.
- **Depends on:** CP-001 foundation. The app has been installed and opened on one physical phone; device/API and cold-relaunch persistence evidence remain incomplete.

## Objective

Implement a real nearby-device discovery path using Android Wi-Fi Direct DNS-SD. Advertise a minimal PEYVAND service, find compatible PEYVAND advertisements, report current capability/permission state accurately, and release P2P registrations when scanning stops, availability is lost, or the app leaves the foreground.

## Acceptance criteria

- [ ] `:core:connectivity` is a focused Android adapter module and the dependency graph remains acyclic.
- [ ] Domain discovery contracts and API-level permission policy are Android-free and covered by unit tests.
- [ ] Android 13+ requests `NEARBY_WIFI_DEVICES`; API 32 and earlier request `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` together, while discovery proceeds only if FINE is granted.
- [ ] Wi-Fi Direct hardware is optional in the manifest and checked at runtime.
- [ ] Location Mode, P2P enabled state, channel readiness and permission status are represented accurately; unknown P2P state remains CHECKING until the sticky system state is observed.
- [ ] DNS-SD advertisement contains only PEYVAND application/schema compatibility fields and a process-local random instance suffix.
- [ ] Service callback registration type exactly matches the PEYVAND Bonjour type in Android's documented `_service._tcp.local.` form.
- [ ] TXT callback full domain is parsed and validated as a PEYVAND instance; arbitrary domains containing a substring are rejected.
- [ ] Service and TXT callbacks are correlated by both transient device address and the same normalized instance name.
- [ ] Discovery metadata is never described as peer authentication; no socket, pairing, transfer or APK operation is part of CP-002.
- [ ] Cancellation, Activity stop, permission loss, Location Mode loss, P2P disabled state, channel loss and action failure invalidate stale callbacks, clear pending peer data and perform independent best-effort cleanup of P2P requests and local service advertisements.
- [ ] A lost P2P channel can be reinitialized on a later foreground availability refresh.
- [ ] Compose UI exposes loading, idle, scanning, empty, permission-denied, Location-disabled, unsupported and error states.
- [ ] Radar animation is driven only by real discovery state and respects Android animation-disable settings.
- [ ] Persian UI strings remain in resources; mixed-direction device names are bidi-safe.
- [ ] VPN route diagnosis observes only the app-default network and shows a non-blocking warning; it does not block discovery or assert that local traffic is blocked.
- [ ] `ACCESS_NETWORK_STATE` is declared by the connectivity module that calls ConnectivityManager APIs.
- [ ] CI unit tests, all module lint tasks and Debug APK build pass on the exact checkpoint source commit.
- [ ] Two physical Android devices with model/API details demonstrate reciprocal PEYVAND advertisements before this checkpoint is marked PASSED.

## Implemented scope

- Kotlin/Compose Android foundation, Persian-first language bootstrap and PEYVAND design-system entry point.
- Wi-Fi Direct DNS-SD local service advertisement and PEYVAND service discovery.
- Version-aware runtime permissions, optional hardware detection, Location Mode/P2P state/channel checks and retry after a channel loss.
- Strict validation of Bonjour registration type and TXT full domain; callback matching by transient device address plus service instance.
- Bounded pending peer records, generation-based stale-callback invalidation and best-effort cleanup of service requests and local advertisements.
- Discovery UI states, bidi-safe device-name display and actual-state radar feedback.
- App-default VPN route diagnostics with a non-blocking Persian warning and user-directed VPN settings shortcut.
- No socket, pairing, TLS, receiver-consent protocol, file transfer, integrity verification, resume or APK handling.

## Test evidence

- CI run `41288015067` on the earlier discovery/VPN diagnostic revision completed successfully: unit tests, app/core lint tasks and `:app:assembleDebug` succeeded; artifacts were uploaded. This run predates the DNS-SD callback and lifecycle hardening recorded below.
- The source hardening added domain unit tests for the exact Bonjour registration type, valid/invalid TXT full domains and rejected instance names. The previous green CI source revision had 21 domain tests across language, discovery, permission and VPN policies.
- Product-owner physical test on Samsung Galaxy A06 and ASUS X017DA failed reciprocal discovery: Galaxy A06 returns the app's `Wi-Fi Direct busy` state; ASUS X017DA scans but finds no PEYVAND peer. Exact Android API levels, firmware versions and `logcat` evidence are pending.
- The discovery adapter now exposes and logs which P2P action failed (`clearServiceRequests`, `clearLocalServices`, `addLocalService`, `addServiceRequest`, or `discoverServices`). `DeviceDiscoveryScreen` releases its scan when the navigation destination is removed. Exact-head CI and physical retest are pending.
- Several intermediate CI runs were cancelled by newer pushes. Do not treat a cancelled run or the earlier successful run as validation of the latest source revision.
- The last previously CI-verified source revision was `0363d7ff83ccca019cdf775d293d7f06945d6c7d` (run `41294583841`). The discovery diagnostics/lifecycle fixes are in app-source commit `6e781732afd91b41c331b2c22d5445fc0b3d85df`. Subsequent commits only record documentation and the unapproved QR/hotspot proposal; the latest exact branch-head CI run must still complete successfully.
- Local build: not run in the current workbench.
- Two-physical-device reciprocal discovery: failed on the owner's Galaxy A06 + ASUS X017DA test pair; retest after the diagnostics build is required. Emulator-only evidence cannot replace this requirement.

## Risks and deferred work

- Wi-Fi P2P support, DNS-SD behavior, service naming, and radio concurrency vary by manufacturer, chipset and Android build.
- Current `minSdk=26` and application ID `com.peyvand.app` remain provisional.
- Public service markers can be forged; discovery is not authentication.
- VPN route status is not a local-network reachability test. VPN-on/off, per-app VPN, split-tunnel, LAN exception and lockdown conditions require transport-level tests in a later milestone.
- Local socket connection, TLS, pairing, receiver consent, transfer framing, files, APKs and resume are not implemented.
- No fallback transport is selected by this checkpoint. Proposed ADR-0004 evaluates QR plus Android local-only hotspot as a possible alternative; the proposal is not approved and no implementation exists.

## Source verification

- Manifest permission correction: `7abd10a4aebdc1dd94c7158b8f44d080f7b0ec92`.
- DNS-SD and lifecycle hardening: commits `e6d70738a042b0bfa22bfa788f414e09de426666`, `fc8bbf1a828cf9f20f778b8b7365fb1dfaa078f7`, `4690d5dd57a78da30ac7e71337076aeb3d45ae46`, `fb401a45c6972ed1f7929eece296f9e2e7dbd8e8`, `225fc83afc04f7d443c6a16f33dd15207b38571d`, `2897297ffb3d12a92b80a31d55ac1ec9a9884b0e`, and `a8518d473e20ca1159b16607284c211893b6e78e`.
- App-source diagnostics revision: `6e781732afd91b41c331b2c22d5445fc0b3d85df`.
- Exact branch-head CI after documentation-only commits: pending. The latest workflow run must be checked against the final branch SHA.
- Physical two-device result: reciprocal discovery failed on the owner's Galaxy A06 and ASUS X017DA; the diagnostics build has not yet been retested.

## Status rule

Keep `IN_PROGRESS` until every applicable criterion has reproducible evidence. Use `BLOCKED` if a mandatory criterion cannot be completed; never mark it PASSED merely because files were committed or a build succeeded.
