# PEYVAND project status

- **Product:** PEYVAND (پیوند)
- **Repository:** `azad2022/shareit`
- **Current status:** `IN_PROGRESS`
- **Current checkpoint:** CP-004 — Multi-item one-to-one transfer
- **Current branch:** `feature/secure-file-transfer` (stacked on the owner-updated CP-002 discovery branch; PR #3 remains open as Draft and is not merged)
- **Last updated:** 2026-11-07 (CP-004 started; CP-003C basic one-file smoke and exact-head CI recorded)

## Milestones

### CP-001 — Initial Android foundation

- Debug APK and CI passed on foundation commit `b9523e084382b2efe14c837b3f345bb33e1cb79f`.
- Product owner reported successful installation, Persian selection, and first entry to the app on a physical phone.
- Exact handset model/API and cold-relaunch language-persistence evidence remain unrecorded; CP-001 stays `IN_PROGRESS`.

### CP-002 — Wi-Fi Direct service discovery

- Kotlin/Compose foundation, version-aware runtime permission handling, Wi-Fi Direct capability checks, local DNS-SD advertisement and peer service discovery are implemented on `feature/foundation-bootstrap`.
- The discovery adapter now records the exact asynchronous P2P operation that returns a framework failure; the discovery UI displays that stage. The screen now stops discovery and releases registrations when it is removed from the navigation composition.
- Discovery advertises only the PEYVAND service/schema markers. These are public and spoofable compatibility metadata, not identity or authentication.
- Service responses are accepted only for the exact PEYVAND Bonjour registration type (Android API example format: `_service._tcp.local.`) and a valid PEYVAND instance suffix. A valid service response immediately creates an explicitly unverified discovery candidate; TXT is not a publication prerequisite.
- TXT responses are accepted only when their full DNS-SD domain identifies a valid PEYVAND instance. The service and TXT callbacks are correlated in an Android-free, bounded registry using both the same transient P2P device address and the same normalized service instance name; either callback order is supported. TXT only sets metadata state (not received/compatible/incompatible).
- Per-scan diagnostics expose the successful local-service registration, service-request registration and discovery-start actions plus callback totals grouped by pseudonymous source tokens (P1, P2...). Raw device addresses and device names are not included in diagnostic summaries.
- Callback generation is invalidated before failure cleanup. Cleanup requests and local-service cleanup remain independent; a new scan is deferred until both asynchronous cleanup callbacks have completed. Pending correlation state and raw-address-to-token mappings are cleared on scan teardown.
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

## Owner installation report — P2P readiness (2026-11-02)

- Owner installed Debug APK from commit `062116df0c763ac9b8a2a8d390aba33487f1f731` and reported that the discovery screen remained at "در حال بررسی پشتیبانی Wi-Fi Direct…" without entering active search.
- Source inspection localized a readiness gap: the adapter depended on the optional sticky-intent result from receiver registration to initialize `p2pEnabled`. When no initial intent was returned, availability remained `CHECKING`; `startDiscovery(role)` returned before any DNS-SD operation.
- Android's `WifiP2pManager.requestP2pState()` is available from API 29. Galaxy A12 / Android 12 is API 31. The adapter now queries current P2P state through this API and retains the broadcast receiver for later state changes.
- The send/receive route now starts its directional DNS-SD role once permission and P2P readiness are established. Empty results do not terminate browsing; an explicit user stop is respected.
- The code correction has not yet been tested on both physical phones. CP-002 remains `IN_PROGRESS`.

## Latest discovery review (2026-11-02)

- Owner-reported pre-fix diagnostics: one phone received one service callback and one accepted service callback, but no TXT callback and no peer publication; the second phone received no service or TXT callbacks. The aggregate counters did not identify the callback source.
- **Fixed source-level publication defect:** the old publishPeerIfCompatible() path required a matching compatible TXT record. It has been replaced with DiscoveryCandidateRegistry. A strictly validated service callback now publishes an unverified candidate immediately; a TXT callback before or after the service response updates metadata without changing candidate existence. Incompatible TXT metadata does not silently remove the candidate.
- **Request overload clarification:** newInstance(SERVICE_TYPE) is the typed Bonjour PTR search and is retained for service discovery. It does not request TXT. TXT is optional in Android Wi-Fi Direct DNS-SD. The two-argument overload is for a known instance's TXT data and is not a substitute for the initial service-type query. Zero TXT callbacks alone are not proof that service discovery failed.
- The phone with zero raw callbacks remains unlocalized. New diagnostics expose each registration/start action result and group service/TXT responses by scan-local pseudonymous source tokens. They still cannot prove the intended physical handset was the source without controlled one-advertiser/one-browser tests.
- Cleanup now defers a new scan until the asynchronous request/local-service cleanup callbacks finish. This removes one identified clear/restart overlap, but no physical evidence yet proves it was the root cause of the second phone's zero callbacks.
- **Historical note superseded by current implementation:** the earlier revision advertised and browsed on both routes. The current source assigns `ADVERTISE_ONLY` to Receive and `BROWSE_ONLY` to Send. Preserve these directional roles in the next physical test; do not treat the historical behavior as current.
- CI and physical testing of the latest source tree are pending. CP-002 remains IN_PROGRESS; no connection, pairing, TLS or transfer is implemented.

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


## Owner field retest — persistent DNS-SD discovery failure (2026-11-02)

- The owner reports approximately 20–50 manual attempts across multiple phone models, alternating sender/receiver roles. Only one attempt produced a visible device candidate. That candidate displayed the other handset's model name, TXT count was zero, and trust/authentication was correctly shown as unverified.
- TXT count zero is not by itself a discovery failure: Android documents the TXT record as optional. A validated service response can publish an unverified candidate. CP-002 does not implement peer authentication or connection; the unverified label is expected and must remain.
- A source audit of commit `2ac10cdb9e9abb2d7a7e11ad23f362b42ef62623` found that its documentation claimed an API 29+ `requestP2pState()` query, but its adapter did not actually invoke the API. The adapter could therefore remain in `CHECKING` if receiver registration yielded no sticky state intent. This documentation/implementation mismatch is corrected in the follow-up source change.
- The follow-up also introduces bounded retries for transient Wi-Fi P2P `BUSY` responses during advertise, service-request registration, service browsing and cleanup. It does not add a fallback transport or connection/authentication/transfer code.
- This correction has not yet been physically validated. Keep CP-002 `IN_PROGRESS`; green CI alone is not evidence of reliable two-phone discovery.


## DNS-SD retry and cleanup ordering correction (2026-11-02)

- A final lifecycle review found that the first bounded-retry implementation cancelled pending discovery retries on every P2P state broadcast, including an ENABLED broadcast. Since ENABLED does not invalidate a scan, this could leave the operation in STARTING without its scheduled retry. Retry cancellation now occurs only when P2P becomes disabled or the scan/lifecycle generation is invalidated.
- P2P cleanup now serializes `clearServiceRequests()` followed by `clearLocalServices()`, rather than submitting both framework mutations concurrently. BUSY remains bounded-retry with the same retry policy.
- The retry cancellation and serialized cleanup correction is in commit `6b14c8b75942e43b4cbaebd33462bb508ad124e1` and is pending CI validation. A subsequent source review also makes missing P2P channel or receiver-registration failure resolve to UNAVAILABLE instead of CHECKING.
## 2026-11-02 — event-driven DNS-SD lifecycle correction

- The browser now observes `WIFI_P2P_DISCOVERY_CHANGED_ACTION` and records actual platform STARTED/STOPPED transitions. It no longer treats the initial `discoverServices()` ActionListener success as proof that the system scan remains active indefinitely.
- API 29+ additionally reconciles state using `requestDiscoveryState()` when the initial accepted request has no observed state transition. API 26–28 use the discovery-state broadcast.
- Confirmed STOPPED events cause a single delayed re-arm for the still-active browser role, reusing the existing service request and using bounded 1/2/4/8/15-second backoff. Duplicate starts, duplicate service requests, `discoverPeers()`, and blind periodic rescans are avoided.
- The discovery screen exposes actual platform state and stop/restart counters. Pure unit coverage was added for the re-arm-delay policy.
- CP-002 remains `IN_PROGRESS`. This connector-only source update has not been compiled or run on physical phones; CI was intentionally not awaited per product-owner instruction.
- Follow-up audit tightened the scan-state presentation: the browser radar now animates only after the framework reports RUNNING; STOPPED and UNKNOWN have separate Persian status copy. The re-arm gate is a pure domain policy with negative-case unit coverage. The displayed restart counter counts framework-accepted re-arm requests, not measured radio-start confirmations.

## Current sender/receiver role clarification (2026-11-02)

- The active implementation has asymmetric roles: Receive registers only the local PEYVAND DNS-SD advertisement; Send registers only the PEYVAND service request and browses. Older text in this status document that described both routes advertising and browsing has been superseded.
- The current lifecycle controller applies only to the browser role. No role change, fallback transport, connection, pairing, or file-transfer implementation is implied.
- The scan-state controller has been extracted from the Wi-Fi Direct repository adapter into a focused connectivity class. The repository retains role ownership, platform permission checks, DNS-SD registration, error policy and snapshot publication; the lifecycle controller owns state queries, STARTED/STOPPED handling, bounded re-arm scheduling and cancellation.


## 2026-11-02 — wildcard Bonjour request experiment

- Created isolated branch `feature/dns-sd-wildcard-query-experiment` from `feature/foundation-bootstrap` at `df7c595462ed445a721cc0ddb95bc754ae3b4ec8`.
- The browser now uses Android's no-argument `WifiP2pDnsSdServiceRequest.newInstance()` to request all Bonjour services for a controlled compatibility test. The PEYVAND-only instance-name and registration-type checks in the service callback are unchanged, so unrelated Bonjour services must never be published as PEYVAND candidates.
- The sender/receiver roles, local service advertisement, TXT-optional policy, lifecycle/retry behavior and cleanup ordering are unchanged.
- This is not a confirmed root-cause fix. The purpose is to determine whether a device/firmware combination mishandles the narrower typed PTR request. A broader query may increase callback traffic.
- ADR-0005 records the controlled two-device test and rollback criteria. CI and physical validation of the experiment are pending. CP-002 remains `IN_PROGRESS`.


## 2026-11-02 — first-file transport production gate

- Confirmed the current Android app configuration is `compileSdk=37`, `targetSdk=36`, `minSdk=26`; the Debug application ID remains provisional (`com.peyvand.app`).
- Android's current Local Network Protection guidance states that Android 16 has an opt-in phase and Android 17 enforces runtime `ACCESS_LOCAL_NETWORK` for apps targeting SDK 37 or higher when performing direct local-network TCP/UDP operations. The future socket-transfer checkpoint must cover this permission, denial/revocation, and Android 16 opt-in tests before raising targetSdk to 37. Do not add that permission prematurely while still targeting SDK 36.
- No connection, TLS, pairing, socket or file-transfer implementation is added by the current DNS-SD experiment. First-file work remains gated on stable two-device discovery and a reviewed secure-session ADR.
- Architecture note: `docs/architecture/FOUNDATION.md` now reflects that a valid service response publishes an unverified candidate without waiting for optional TXT metadata.


## Owner retest — discovery order and repeated-session failures (2026-11-04)

- The owner reports that discovery requires Receive/advertiser to start before Send/browser; simultaneous start or a late-starting receiver is not discovered reliably.
- The owner also reports that a second discovery attempt often fails until both apps are exited and reopened.
- The owner suspects that retaining selected files contributes to the failure. Source review does not support that hypothesis: `ShareSelectionViewModel.clearSelection()` only clears `selectedItems`; it does not invoke or mutate Wi-Fi P2P discovery.
- Source review identified an application-imposed asymmetric role split: Receive registered only a local service, while Send registered only a service request and browsed only. The follow-up change makes both routes advertise and browse while preserving strict PEYVAND callback filtering.
- Source review also identified a foreground-resume gap: the discovery screen used a one-shot auto-start flag that survives Activity backgrounding while `MainActivity.onStop()` stops monitoring/discovery. The follow-up gates automatic start on Activity RESUMED, platform readiness, IDLE status, permission, and absence of an explicit user stop.
- The all-Bonjour wildcard request did not resolve the reported ordering failure on the owner's installed build; the follow-up restores the typed PEYVAND service request.
- These are source-level corrections, not physical acceptance evidence. CP-002 remains `IN_PROGRESS`; repeatability on both physical test devices is mandatory.

## Owner validation update — discovery currently operational (2026-11-04)

- The owner reports that the two phones now find one another easily across several manual operating scenarios after the bidirectional DNS-SD and foreground-restart changes on commit `690b932d4a6305fa04544ad0b93d2eb4fbe066b1`.
- The earlier discovery order-dependency and repeated-session symptoms are considered currently resolved in the owner's tested environment. This is not a universal OEM/API support claim.
- The latest report did not include a full device/API/build matrix, exact trial count, time-to-first-peer or repeatability evidence for every required case. CP-002 therefore remains `IN_PROGRESS`; see `docs/checkpoints/CP-002-WIFI-DIRECT-DISCOVERY.md` for the precise remaining matrix.
- Do not treat discovery as connection, authentication, consent, or transfer. The `NearbyPeer` identifier remains transient and untrusted.

## CP-003 — Secure one-to-one generic file transfer

- CP-003 is active on `feature/secure-file-transfer`, stacked on the owner-updated CP-002 discovery branch. Draft PR #3 remains open and unmerged.
- ADR-0007 defines the first transfer milestone: one-to-one, one arbitrary user-selected file, Wi-Fi Direct only, session pairing, mutually authenticated TLS, receiver consent, bounded streaming, SHA-256 verification and SAF publication. ADR-0008 defines the network-channel substage: one raw TCP socket bound exclusively to the active P2P interface.
- CP-003A protocol/streaming primitives are implemented in `:core:transfer`: bounded versioned frames, format-agnostic manifest, filename sanitation, pairing SAS transcript derivation, transfer state policy, bounded streaming with inline SHA-256, contiguous chunk validation, cumulative ACK model and a four-chunk/256 KiB sender window. The ACK/window models are not yet wired to an Android socket/staging data plane.
- CP-003B initial Wi-Fi Direct connection slice is implemented: a focused controller reuses the discovery adapter's manager/channel/receiver; sender selection is limited to a currently discovered compatible peer; group formation is confirmed only after the P2P connection broadcast and `requestConnectionInfo()`; the local Group Owner does not depend on Android's potentially anonymized local owner address; a minimal Persian connection-status UI is wired. Disconnect uses `removeGroup()`, can clear state from a connection-change notification, and now has a 10-second watchdog with an explicit timeout failure and best-effort cleanup. A delayed success/broadcast can still resolve a timeout; a late failure callback cannot overwrite a removal already confirmed by platform state. The pure pending-state policy is unit-tested, but dedicated framework-level tests and physical disconnect verification remain outstanding.
- The P2P-scoped raw TCP channel is now implemented in `:core:connectivity`: the Group Owner binds a listener only to the IPv4 address of the `WifiP2pGroup.getInterface()` interface; the Group Client binds its source address to its P2P interface and connects to the Group Owner address from Android. Both sides enforce same-subnet private IPv4, one peer at a time, finite timeouts/retry and socket cleanup on lifecycle/group teardown. The UI receives only state, not socket addresses or streams.
- A raw TCP socket is not authentication and carries no application data yet. Still not implemented: Android Keystore ephemeral identity, SAS exchange and user approval, pinned mutual TLS, receiver consent handshake, ContentResolver source streaming, private receiving staging, SAF publication and authenticated integrity receipt.
- CI run `41838844163` succeeded on source HEAD `f519e508792d3d495976b802091f66edddb3b646`: 88 unit tests passed (0 failures/errors/skips), configured lint completed with 9 warnings and 0 errors, and `:app:assembleDebug` succeeded. Validation evidence: https://github.com/azad2022/shareit/actions/runs/41838844163.
- Debug APK artifact `13327953810`: 14,010,025 bytes; SHA-256 `820b5064883b19defa8b2ee525800bf46c9483826ef4bb042af6da702886de89`. This is a connection-stage Debug build, not a file-transfer-ready release.
- Owner-reported physical smoke test (2026-11-04): two devices discover one another, the UI reports `Wi-Fi Direct group formed`, and a disconnect action is visible. Device/API/build details, group-owner role matrix, repeat count, APK SHA-256, and an observed disconnect result were not supplied. This is not a PASSED interoperability or teardown test.
- All end-to-end transfer tests remain outstanding. CP-003 stays `IN_PROGRESS`.
- Local TCP permission policy is recorded in ADR-0008 and `docs/architecture/FOUNDATION.md`: `INTERNET` is used only for direct local socket APIs, not public Internet. The app targets SDK 36 and does not declare `ACCESS_LOCAL_NETWORK`; Android 16 opt-in restrictions must be tested with `NEARBY_WIFI_DEVICES`, and target-SDK-37 permission handling remains a separate compatibility gate.
- Channel architecture decision: `docs/decisions/ADR-0008-WIFI-DIRECT-TCP-CHANNEL.md`.
- Checkpoint: `docs/checkpoints/CP-003-SECURE-FILE-TRANSFER.md`.

## Current module boundaries

- `:app`: Android entry point, lifecycle, ViewModels and Compose presentation.
- `:core:domain`: Android-free shared product models and policies.
- `:core:data`: language persistence.
- `:core:connectivity`: Wi-Fi Direct discovery and (in CP-003B) connection lifecycle adapters.
- `:core:storage`: URI-based MediaStore/SAF catalog and (in CP-003C) source/staging/publication adapters.
- `:core:transfer`: bounded transport-independent wire protocol and streaming core introduced during CP-003A.
- `:core:designsystem`: PEYVAND semantic palette and Compose theme.


## 2026-11-04 — CP-003B secure-session implementation update

- Active branch: `feature/secure-file-transfer`; draft PR #3 remains open and unmerged.
- Source commit: `848fb054d3e55de7fc797db5dcf318c9a32f29a5` (session pairing, Android Keystore ephemeral identity, SAS approval, pinned mutual TLS, UI state and bounded stale-key cleanup).
- The current implementation is a security-session substage, not a completed file-transfer feature. It does not yet exchange a file manifest, obtain per-file receiver consent, read a selected ContentResolver URI, stream bytes, stage/publish a destination, or return a verified completion receipt.
- GitHub Actions run `41896531270` completed successfully on exact branch-head `d25028b4a21e0f06f91bd02d1c9b56935d03e07c`: 98 JVM unit tests passed (0 failures/errors/skips), configured lint completed with 10 warnings and 0 errors, and `:app:assembleDebug` succeeded.
- Debug APK artifact `13355498653` is 14,109,243 bytes; extracted APK SHA-256 is `c2ab089c117d00fd80c5261783718ca62217dfb883d72ae7d07389730cddfd93`.
- The lint report includes a `CustomX509TrustManager` warning in `:core:connectivity`; complete a focused trust-manager security review and physical Keystore/SAS/TLS tests before treating session authentication as accepted. CI did not run Android instrumentation or physical-device tests.
- Successful build/test results do not imply end-to-end transfer. Receiver consent, socket manifest exchange, streaming, staging, SAF publication and authenticated integrity receipt remain unimplemented.
- No physical pairing or TLS interoperability evidence is recorded yet. CP-003 remains `IN_PROGRESS`.


## 2026-11-07 — CP-003C single-file transfer runtime integrated

- **Current source branch:** `feature/secure-file-transfer`; CP-003 remains `IN_PROGRESS`.
- **Source implementation:** the mutually authenticated TLS channel is now exclusively claimed by `SingleFileTransferCoordinator` and passed to `SingleFileTransferProtocol`. The app has a direct `:core:transfer` dependency.
- **One-to-one file path:** user-selected `content://` source → bounded manifest → explicit receiver consent → 64 KiB binary chunks with four-chunk cumulative ACK window → private app staging with sync-before-ACK → SHA-256/byte-count verification → SAF destination copy and read-back verification → authenticated completion receipt.
- **UI states:** sender ready/consent wait/transferring/verifying/completed; receiver waiting/consent/transferring/verifying/destination selection/publishing/completed; reject/cancel/failure are distinct. Failure copy warns the user to remove any incomplete destination artifact that a provider could not delete.
- **Storage boundary:** only content URIs are supported by this first transfer slice. The transfer data plane is format-agnostic; installed package sources are intentionally rejected until APK-set transfer has its own checkpoint. SAF provider atomicity varies; the app does not promise atomic publication.
- **Tests added:** JVM coordinator orchestration test over a paired full-duplex stream, plus existing protocol, streaming, frame, flow-control, manifest, integrity and state tests. The coordinator test is not a replacement for hardware or Android DocumentProvider tests.
- **Build correction:** initial CP-003C integration CI run `42389254385` failed because `:app` did not depend directly on `:core:transfer`. The direct dependency and stale lifecycle phase type were corrected. That failed run is not passing evidence. See the latest exact-head CI link in the PR before downloading an APK.
- **Physical evidence:** owner reported matching SAS and successful TLS between ASUS and Galaxy A12 (SM-A125F). No real file transfer using the new CP-003C runtime has yet been reported; CP-003 cannot be marked `PASSED`.
- **Known remaining gates:** exact-head CI and APK checksum, repeated real-file tests both directions, SAF provider failure/cleanup behavior, large and zero-byte files, rejection/cancellation, source permission revocation and integrity mismatch.
- **Deferred:** multi-file queue, group transfer, resume, background guarantees, APK/split-APK flow, discovery speed optimization and final design pass.


### CP-003C implementation refinement — 2026-11-07

This section supersedes earlier status bullets in this document that said the live transfer control/data path or SAF publication had not yet been implemented. Those statements are historical snapshots from before the CP-003C integration commits.

- The live sender/receiver path uses a maximum four-chunk/256 KiB receive window. The receiver writes the window to private staging, calls `sync()`, commits the contiguous offset, then sends one cumulative ACK. The final partial window is synced after the sender sends its completion record. The coordinator test checks four batch syncs plus the final staging seal for a 900,123-byte test payload.
- Both peers preserve an explicit `VERIFYING` state while the final ACK and authenticated receipt/publication steps finish.
- SAF publication uses bounded, cancellation-aware copy and read-back digest loops. Provider deletion is best-effort; a provider that will not confirm deletion cannot be treated as a successful cleanup.
- Exact-head CI and the owner’s physical file-transfer test remain required. CP-003 is still `IN_PROGRESS`; no physical real-file transfer is recorded yet.


## 2026-11-07 — Latest authoritative CP-003C acceptance update

- The product owner explicitly confirmed that file transfer is successful. Record the basic one-file, one-to-one functional smoke acceptance as `PASSED` based on that owner report.
- The exact source head is `0bb8d6582d860a45ded777ac9432d617c2106f55` on `feature/secure-file-transfer`. Exact-head GitHub Actions run [42392698691](https://github.com/azad2022/shareit/actions/runs/42392698691) completed successfully. All 3 jobs passed: build/unit-tests/lint; Android Keystore mTLS loopback on API 26; and Android Keystore mTLS loopback on API 36.
- Debug APK artifact `13581694883` was produced. Extracted APK size: 14,221,658 bytes. SHA-256: `bd279275f25554a374e04398d1aa9aab38f0b1f7d041653b10605769505661d0`. Artifact ZIP digest: `sha256:d7823624ae18034e132eeade96f3fd34d1b27e1983294675d919dd575a67e704`.
- **CP-003 overall remains `IN_PROGRESS`**, not `PASSED`: the owner confirmation does not include the exact handset/API/build, installed APK hash, sender/receiver direction, payload characteristics, repeat count, or coverage of the full acceptance matrix required by criterion 11. The earlier notes that said no physical transfer had been reported are superseded by this update; they remain historical statements about the earlier state.
- This update does not assert that zero-byte/large/unknown-MIME files, all SAF providers, rejection/cancellation, cleanup, source-permission revocation, hash mismatch, resume, group transfer, APK/split APK, or background operation have been physically validated.
- PR #3 remains open and Draft. Do not merge it without explicit owner authorization.

### Recommended next checkpoint sequence (planning only)

1. **CP-004 — Multi-item one-to-one transfer:** selection of multiple arbitrary user-authorized files, a stable queue/manifest model, per-item consent/status, deterministic order, per-item integrity receipts, cancellation/failure isolation, and queue recovery tests. Preserve the current secure single-file path.
2. **CP-005 — Android application-package transfer:** PackageManager visibility review, supported extraction of base and required split APKs, package/signing metadata, compatibility checks, and user-approved PackageInstaller handoff. Do not imply transfer of private app data or silent installation.
3. **CP-006 — One-sender-to-many group transfer:** independent consent, progress, cancellation, failure, integrity and completion per receiver; enforce bounded concurrency/backpressure and document that radio throughput is shared. No multi-sender group mode.
4. **CP-007 — Durable resume and long-running transfer reliability:** durable checkpoints, authenticated resume negotiation, source/destination identity validation, interrupted process/reconnect behavior, lifecycle/background policy, cleanup and transfer history.
5. **CP-008 — Product-wide UX and release hardening:** PEYVAND design system, all relevant UI states, accessibility, RTL/LTR and localization, purposeful motion, physical-device compatibility matrix, performance/thermal/battery measurements and distribution-policy review.

The ordering is a proposed dependency-aware plan, not authorization to skip architecture/security review. Keep each checkpoint independently scoped with acceptance criteria and evidence. This section supersedes older top-level summaries only where they conflict with the 2026-11-07 validation update.



## CP-004 — Multi-item one-to-one transfer (2026-11-07)

- CP-004 starts on the isolated branch `feature/cp-004-multi-item-transfer`, based on `7237115e1ce04ab225cbad223fad77e9ed8d344f`. The base contains only the latest CP-003 documentation updates beyond the CI-validated code SHA `0bb8d6582d860a45ded777ac9432d617c2106f55`.
- PR #3 remains open, Draft and unmerged. CP-004 is not being developed by modifying or merging PR #3.
- The scope is one sender, one receiver and 2–32 ordered generic `content://` files. A single selected file retains the existing version-1 implementation. Batch sessions use a separate, version-2 protocol.
- ADR-0009 records the two-step consent model: metadata-free offer approval before manifest disclosure, then per-item selection for content transfer; it also records immutable manifest order, item-scoped receipts/outcomes, serial resource ownership, and one receiver-selected SAF destination tree.
- The receiver first approves a metadata-free batch offer before any filename/MIME/size/count metadata is disclosed. A second batch-level decision selects individual items. Only accepted items reach ITEM_START/ITEM_READY; source InputStreams and private stages are not opened/created before their respective authorization/readiness.
- Files are processed sequentially with the existing 64 KiB chunk and maximum four-chunk/256 KiB flow-control caps. Item-local recoverable failures/cancellations are isolated; malformed protocol, channel or authentication failures terminate the batch.
- Durable queue restoration, resume/process-death recovery, group transfer, multiple senders and APK/split-APK remain explicitly out of scope.
- Version-2 protocol, initial UI, SAF tree storage, and JVM tests are now committed. CI compile/test/lint, final Debug build, complete failure-path evidence, APK artifact and physical-device evidence still require validation for the exact final SHA. Checkpoint status remains `IN_PROGRESS`; no CP-004 acceptance criterion is marked passed.
- See `docs/checkpoints/CP-004-MULTI-ITEM-ONE-TO-ONE-TRANSFER.md` and `docs/decisions/ADR-0009-MULTI-ITEM-ONE-TO-ONE-TRANSFER.md`.
