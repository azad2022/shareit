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
- [ ] A valid service callback with the exact PEYVAND instance-name and Bonjour registration-type format creates a discovery candidate without waiting for a TXT callback.
- [ ] TXT is treated as optional metadata. A missing TXT callback never suppresses a valid service candidate.
- [ ] When TXT is received, its full domain is parsed strictly, arbitrary substring matches are rejected, and schema metadata is validated separately from candidate discovery.
- [ ] Service/TXT correlation is order-independent and keyed by transient source-device address plus normalized instance name; late, duplicate, missing and mismatched callbacks are handled with bounded, generation-scoped state.
- [ ] The selected WifiP2pDnsSdServiceRequest factory overload is justified against Android's documented semantics and physically tested. The implementation must not assume newInstance(serviceType) automatically requests TXT data for every discovered instance.
- [ ] The UI distinguishes an unverified discovery candidate from a metadata-compatible peer and from an authenticated peer. Discovery metadata never authorizes a connection.
- [ ] Diagnostics distinguish local advertisement registration, service-request registration, discovery-start action results, raw callbacks, filter results, source correlation and peer publication without exposing full MAC addresses or user-chosen device names.
- [ ] Discovery metadata is never described as peer authentication; no socket, pairing, transfer or APK operation is part of CP-002.
- [ ] Cancellation, Activity stop, permission loss, Location Mode loss, P2P disabled state, channel loss and action failure invalidate stale callbacks, clear pending peer data and perform independent best-effort cleanup of P2P requests and local service advertisements.
- [ ] A lost P2P channel can be reinitialized on a later foreground availability refresh.
- [ ] Compose UI exposes loading, idle, scanning, empty, permission-denied, Location-disabled, unsupported and error states.
- [ ] Radar animation is driven only by real discovery state and respects Android animation-disable settings.
- [ ] Persian UI strings remain in resources; mixed-direction device names are bidi-safe.
- [ ] VPN route diagnosis observes only the app-default network and shows a non-blocking warning; it does not block discovery or assert that local traffic is blocked.
- [ ] `ACCESS_NETWORK_STATE` is declared by the connectivity module that calls ConnectivityManager APIs.
- [x] CI unit tests, all module lint tasks and Debug APK build passed on checkpoint tree `4251e6df630747b8a78bb7a1161a3cedbd4909ad` (run `41317903875`).
- [ ] Two physical Android devices demonstrate discovery in each supported sender/receiver direction. Test both role assignments separately; require reciprocal list visibility only if the approved UX explicitly needs it.

## Implemented scope

- Kotlin/Compose Android foundation, Persian-first language bootstrap and PEYVAND design-system entry point.
- Wi-Fi Direct DNS-SD local service advertisement and PEYVAND service discovery.
- Version-aware runtime permissions, optional hardware detection, Location Mode/P2P state/channel checks and retry after a channel loss.
- Strict validation of Bonjour registration type and TXT full domain. A validated service callback creates an unverified candidate without waiting for TXT; compatible/incompatible/missing TXT is separate metadata.
- Bounded, Android-free candidate correlation by transient device address plus normalized service instance; supports service-before-TXT and TXT-before-service callback ordering.
- Discovery UI states, bidi-safe device-name display and actual-state radar feedback.
- App-default VPN route diagnostics with a non-blocking Persian warning and user-directed VPN settings shortcut.
- No socket, pairing, TLS, receiver-consent protocol, file transfer, integrity verification, resume or APK handling.

## Test evidence

- CI run `41288015067` on the earlier discovery/VPN diagnostic revision completed successfully: unit tests, app/core lint tasks and `:app:assembleDebug` succeeded; artifacts were uploaded. This run predates the DNS-SD callback and lifecycle hardening recorded below.
- The source hardening added domain unit tests for the exact Bonjour registration type, valid/invalid TXT full domains and rejected instance names. The previous green CI source revision had 21 domain tests across language, discovery, permission and VPN policies.
- Product-owner physical test on Samsung Galaxy A12 (SM-A125F, Android 12 / One UI Core 4.1) and ASUS X017DA failed reciprocal discovery: Galaxy A12 returns the app's `Wi-Fi Direct busy` state; ASUS X017DA scans but finds no PEYVAND peer. This corrects the earlier mistaken A06 model label. Samsung's official update record confirms Android 12 / One UI Core 4.1 for SM-A125F: https://doc.samsungmobile.com/SM-A125F/018293210323/eng.html. Exact firmware build and `logcat` evidence are still pending.
- The owner supplied model `SM-A125F`; the device is Galaxy A12, not Galaxy A06. Android 12 and One UI Core 4.1 are confirmed by Samsung's update record. All earlier A06 references in this checkpoint are superseded by this exact model identification.
- On Android 12/API 31-32, verify `ACCESS_FINE_LOCATION` is granted (not approximate-only) and system Location Mode is enabled before retesting `discoverServices()`. Android's Wi-Fi Direct documentation specifies `ACCESS_FINE_LOCATION` for targets below API 33 and requires Location Mode for service discovery: https://developer.android.com/develop/connectivity/wifi/wifi-direct.
- The discovery adapter now exposes and logs which P2P action failed (`clearServiceRequests`, `clearLocalServices`, `addLocalService`, `addServiceRequest`, or `discoverServices`). `DeviceDiscoveryScreen` releases its scan when the navigation destination is removed. Exact-head CI and physical retest are pending.
- Several intermediate CI runs were cancelled by newer pushes. Do not treat a cancelled run or the earlier successful run as validation of the latest source revision.
- Follow-up implementation commit `73f030c1e81b9302d08a9d6130f926fb2725d0e1` removes unconditional startup cleanup (`clearServiceRequests`/`clearLocalServices`) and skips teardown calls when discovery was already idle. Exact-head GitHub Actions run `41322529714` completed successfully: 21 unit tests passed, 0 failures/errors/skips; configured lint tasks passed with 0 lint errors; `:app:assembleDebug` succeeded. Artifact `13089684423` (`peyvand-debug-apk`) was downloaded and its APK archive passed `unzip -t`; SHA-256 `ce77391803c6c23a1c105f2546dc7a102e39a1f7ef3bc6cf5338529c3b08202d`.
- The last previously CI-verified source revision was `0363d7ff83ccca019cdf775d293d7f06945d6c7d` (run `41294583841`). Discovery diagnostics/lifecycle fixes are in app-source commit `6e781732afd91b41c331b2c22d5445fc0b3d85df`. The final source tree at `4251e6df630747b8a78bb7a1161a3cedbd4909ad` passed run `41317903875`; subsequent documentation updates do not change app source.
- Product-owner retest after Debug APK `73f030c`: Galaxy A12 and ASUS X017DA both remained in active search (receiver on one phone, sender on the other) but did not discover each other.
- Diagnostics source commit `58c4d86e655e6f3e6800c845ce4792661d9b07f3` adds visible DNS-SD callback/filter counters. GitHub Actions run `41327944528` passed: 21 unit tests, 0 failures/errors/skips; 8 lint warnings, 0 lint errors; Debug APK assembled and uploaded as artifact `13092725538`. APK SHA-256: `e36988f49f55b05b6e9ec87f119c932f878bc07f042afa1a4e966c15cd123187`.
- The diagnostics APK does not add peer connection, sockets, TLS, pairing, receiver consent, file transfer, integrity verification, resume or APK handling. Those features remain unimplemented.
- The latest physical discovery result remains failed. The new candidate/diagnostics build has not yet been tested on both physical phones. Emulator-only evidence cannot replace this requirement.

## Latest owner diagnostic evidence and source review

- **Owner pre-fix diagnostic retest (2026-11-02):** one phone reported service responses 1, accepted service responses 1, TXT responses 0, accepted TXT domains 0, compatible TXT records 0, peers 0. The other phone reported all six counters as 0. No additional compatible test device is available. These observations do not establish a model/firmware cause.
- **Fixed source-level publication defect:** the previous publication method required both service and compatible TXT records. DiscoveryCandidateRegistry now creates a candidate on the validated service callback alone, updates it when matching TXT arrives, supports either callback order, and keeps candidate status separate from metadata compatibility. Unit coverage has been added; exact-head CI is pending.
- **Request construction clarification:** WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE) is retained because it is the typed Bonjour service/PTR search used to discover service instances. It does not request TXT data. Android treats TXT as optional. newInstance(instanceName, serviceType) is for TXT data after a particular instance name is known, so zero TXT callbacks are not by themselves a discovery failure.
- **Interpretation boundary:** one accepted service callback proves only that a callback passed the PEYVAND instance-name and registration-type filters. New diagnostics group callbacks by scan-local pseudonymous source token; the token does not by itself identify the physical handset, so controlled advertiser/browser tests remain required.
- **Zero-callback phone:** all raw callback counters at zero remains before app-side name/type/domain filters. New diagnostics expose local advertisement registration, service-request registration, discoverServices() result, per-source pseudonymous callback counts and missing source-address events. The physical cause still requires retesting.
- **Role/lifecycle issue to audit:** both send and receive routes currently call the same discovery operation; that operation registers a local PEYVAND service and a service request before calling discoverServices(). Thus each side presently advertises and browses at the same time. This may be intentional, but it obscures one-way diagnostics. Test advertiser-only versus browser-only roles in a controlled A/B matrix before changing product behavior. Also inspect whether asynchronous clearServiceRequests/clearLocalServices teardown can race with an immediate new scan and produce BUSY or stale registrations; this is a hypothesis, not a confirmed root cause.
- **Diagnostics update:** callback counters are now grouped by pseudonymous source token (P1, P2...), and the UI reports the three registration/start action outcomes. No raw MAC address or user-selected device name is surfaced.
- The existing green CI run 41327944528 validates source commit 58c4d86e655e6f3e6800c845ce4792661d9b07f3 only (21 unit tests passed; 8 lint warnings and 0 errors; Debug APK assembled). It does not validate the new owner-reported physical results or any source changes made after that commit.

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
- CI `41317903875` completed successfully on branch tree `4251e6df630747b8a78bb7a1161a3cedbd4909ad`: 21 unit tests, 0 failures/errors/skips; 8 lint warnings, 0 lint errors; Debug APK assembled and uploaded (artifact `13087339471`, SHA-256 `0a20f9f63530774b46d659c9d4523ae9812acf4ab15b78387784a97018d1eb12`).
- Physical two-device result: discovery remains unresolved on the owner's Samsung Galaxy A12 (SM-A125F) and ASUS X017DA. The 2026-11-02 owner-reported diagnostic counters are recorded above; full firmware/build details for the ASUS test phone and source-correlated callback evidence remain unavailable.

## Status rule

Keep `IN_PROGRESS` until every applicable criterion has reproducible evidence. Use `BLOCKED` if a mandatory criterion cannot be completed; never mark it PASSED merely because files were committed or a build succeeded.
