# CP-002 — Wi-Fi Direct service discovery

- **Status:** `IN_PROGRESS`
- **Started:** 2026-11-01
- **Branch:** `feature/dns-sd-wildcard-query-experiment` (Debug-only experiment; integration target: `feature/foundation-bootstrap`)
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
- [ ] Browser diagnostics distinguish accepted `discoverServices()` calls from actual framework discovery state; a confirmed `STOPPED` state re-arms service discovery once with bounded backoff, without duplicate service requests or overlapping starts.
- [ ] Browser radar animation and status text reflect only a confirmed framework `RUNNING` state; `STOPPED` and `UNKNOWN` are visibly distinct and do not imply active radio scanning.
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

## Owner installation report — P2P readiness deadlock (2026-11-02)

- Owner installed the Debug APK built from `062116df0c763ac9b8a2a8d390aba33487f1f731`. The UI stayed at the Wi-Fi Direct availability-check message and did not visibly enter scan/advertise.
- Source root cause: the adapter read initial P2P enabled state only from the optional sticky-intent return when registering the receiver. If no initial intent was returned, `p2pEnabled` remained null, availability stayed `CHECKING`, and role start returned before `addLocalService()` or `discoverServices()`.
- API 29+ now resolves initial state with `WifiP2pManager.requestP2pState()`; the receiver remains active for subsequent changes. API 29+ is supported on Galaxy A12 / Android 12 (API 31).
- Send/Receive routes automatically start their selected DNS-SD role after permission and P2P readiness. Empty browse results remain active; a user stop is not automatically undone.
- CI and physical retest for this correction are pending. CP-002 remains `IN_PROGRESS`.

## Latest owner diagnostic evidence and source review

- **Owner pre-fix diagnostic retest (2026-11-02):** one phone reported service responses 1, accepted service responses 1, TXT responses 0, accepted TXT domains 0, compatible TXT records 0, peers 0. The other phone reported all six counters as 0. No additional compatible test device is available. These observations do not establish a model/firmware cause.
- **Fixed source-level publication defect:** the previous publication method required both service and compatible TXT records. DiscoveryCandidateRegistry now creates a candidate on the validated service callback alone, updates it when matching TXT arrives, supports either callback order, and keeps candidate status separate from metadata compatibility. Unit coverage has been added; exact-head CI is pending.
- **Request construction clarification:** WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE) is retained because it is the typed Bonjour service/PTR search used to discover service instances. It does not request TXT data. Android treats TXT as optional. newInstance(instanceName, serviceType) is for TXT data after a particular instance name is known, so zero TXT callbacks are not by themselves a discovery failure.
- **Interpretation boundary:** one accepted service callback proves only that a callback passed the PEYVAND instance-name and registration-type filters. New diagnostics group callbacks by scan-local pseudonymous source token; the token does not by itself identify the physical handset, so controlled advertiser/browser tests remain required.
- **Zero-callback phone:** all raw callback counters at zero remains before app-side name/type/domain filters. New diagnostics expose local advertisement registration, service-request registration, discoverServices() result, per-source pseudonymous callback counts and missing source-address events. The physical cause still requires retesting.
- **Historical role/lifecycle observation:** an earlier revision used the same advertisement-and-browse operation on both routes. The current source has explicit directional roles: Receive advertises only; Send browses only. Preserve these roles for controlled physical tests. Cleanup/restart overlap was investigated separately and its source ordering has been serialized.
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


## Owner field retest — reliability failure (2026-11-02)

- The owner reports approximately 20–50 manual attempts across multiple Android phone models, switching which phone is in Receive/advertiser mode and which is in Send/browser mode. Only one attempt produced a visible device candidate.
- In that single candidate result, the peer model name appeared, TXT count was zero, and the peer was shown as unverified/not authenticated. This is the correct trust state: Android documents TXT as optional, and CP-002 does not implement peer authentication. The absence of TXT did not prevent that one candidate from appearing.
- This hit rate is a release-blocking reliability defect for the discovery milestone. The earlier passing CI run did not prove physical discovery.
- A source audit found a material mismatch in commit `2ac10cdb9e9abb2d7a7e11ad23f362b42ef62623`: the ADR/status text claimed `requestP2pState()` was used on API 29+, but the adapter did not invoke it. The adapter could stay at `CHECKING` when no sticky state intent was returned. The follow-up source change implements the API call with lifecycle/generation checks and a finite timeout.
- Transient `WifiP2pManager.BUSY` responses were previously terminal. The follow-up retries advertise registration, typed service-request registration, service discovery and both P2P cleanup operations with bounded backoff (500/1,000/2,000 ms; at most three retries per operation). Retries are cancelled when the scan generation or cleanup pass becomes stale.
- Keep the current directional roles: Receive advertises only; Send browses only. Do not add a second transport, connect, authentication or transfer as an unreviewed response to this discovery defect.
- CP-002 remains `IN_PROGRESS`. Required next physical test: one device stays on Receive while the other starts Send; capture both screens and counters. Then reverse roles. Record exact model/API, app build SHA, permission/Location Mode state and elapsed time to first candidate. Repeat enough trials to measure reliability, not a single success.


## Final retry-path review (2026-11-02)

- A review of the retry implementation found that an ENABLED P2P state broadcast was cancelling active BUSY retries even though the radio remained available. The correction limits discovery-retry cancellation to P2P DISABLED and explicit lifecycle/scan invalidation. The implementation is in commit `6b14c8b75942e43b4cbaebd33462bb508ad124e1`. A subsequent source review also ensures a missing P2P channel or receiver-registration failure does not leave availability at CHECKING.
- Cleanup now submits `clearServiceRequests()` and only after its completion submits `clearLocalServices()`. This removes concurrent cleanup mutations as a source of avoidable framework contention. Each operation retains bounded BUSY retry.
- These are source-level corrections only; physical discovery reliability remains unverified. CP-002 stays `IN_PROGRESS`.
## 2026-11-02 implementation — event-driven scan lifecycle

- Added handling for `WIFI_P2P_DISCOVERY_CHANGED_ACTION` to the existing foreground Wi-Fi P2P receiver. The adapter records framework `RUNNING` and `STOPPED` transitions separately from the app-level `discoverServices()` action result.
- On API 29+, the browser reconciles the current discovery state with `requestDiscoveryState()` after the framework accepts a service-discovery request when no broadcast state has been observed. On API 26–28, it relies on the discovery-state broadcast, available from API 16.
- A single pending re-arm is scheduled only after a confirmed STOPPED state, only for the active browser role, and only while the original scan generation remains valid. The existing service request is reused; duplicate requests and `discoverPeers()` are not introduced.
- Re-arm uses bounded 1/2/4/8/15-second backoff. STARTED resets consecutive-stop backoff. A start already in flight, cleanup, permission loss, P2P disable, channel loss, explicit stop, or screen teardown suppresses/cancels re-arm.
- Added on-screen diagnostics for actual framework discovery state and stop/successful-restart counts.
- Added pure domain tests for the re-arm backoff policy.
- This source update has not been compiled or run against physical phones in this environment; CP-002 remains `IN_PROGRESS`.
- The browser radar is now driven by the actual platform scan state: confirmed RUNNING animates; STOPPED shows recovery; UNKNOWN remains non-animated. This avoids presenting a successful API request as proof that the radio is currently scanning.
- The re-arm eligibility decision is centralized in an Android-free domain policy with unit coverage for role, foreground monitoring, cleanup, in-flight starts, accepted discovery requests and confirmed STOPPED state.
- The restart counter is explicitly described as accepted re-arm API requests, not confirmed platform starts.


## 2026-11-02 experiment — all-Bonjour request

- An isolated Debug branch replaces the typed `newInstance("_peyvand._tcp")` request with Android's documented no-argument `newInstance()` request for all Bonjour services.
- PEYVAND-only publication remains protected by the existing exact registration-type and instance-name filters. TXT remains optional metadata.
- This is a diagnostic compatibility experiment, not an accepted production change. It does not add peer discovery, a fallback transport, a socket or transfer behavior.
- Required next evidence: exact-head CI plus repeated late-advertiser and late-browser tests on the Galaxy A12 / ASUS X017DA pair. Keep CP-002 `IN_PROGRESS` until repeatable physical discovery is demonstrated.
- See `docs/decisions/ADR-0005-WILDCARD-BONJOUR-EXPERIMENT.md`.


## Owner retest — order dependency and repeat-session failure (2026-11-04)

- Owner observation: Receive must start before Send; simultaneous starts and a Receive session started after Send are not reliably discovered.
- Owner observation: a later attempt may fail until both apps are exited and reopened.
- File-selection hypothesis audited: the selection-clear action only empties the ShareSelectionViewModel's selectedItems map. No call path from clearSelection() reaches DeviceDiscoveryRepository; selection retention is not a discovery precondition.
- Source-level cause addressed: the old role split made Receive advertise-only and Send browse-only. Both flows now register the PEYVAND service and browse for PEYVAND peers. Existing instance/type checks and TXT-optional semantics remain unchanged.
- Source-level cause addressed: a one-shot Compose auto-start flag was not reset when the Activity lifecycle stopped discovery in the background. The new foreground-aware start policy restarts an IDLE ready session on resume unless the user explicitly stopped it.
- The wildcard Bonjour experiment is rolled back to the typed PEYVAND service request because the owner's latest observation does not demonstrate that the wildcard query resolved discovery ordering.
- Required physical acceptance remains: both timing orders and simultaneous start, at least five repetitions per mode, repeated stop/start without process restart, and foreground/background recovery on Galaxy A12 and ASUS X017DA.
- CI can validate compilation and pure policy tests only; it cannot validate Wi-Fi P2P interoperability. Keep CP-002 `IN_PROGRESS`.
