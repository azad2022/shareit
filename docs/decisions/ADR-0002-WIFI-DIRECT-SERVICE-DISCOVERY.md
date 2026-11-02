# ADR-0002 — Wi-Fi Direct service discovery

- **Status:** Accepted for Wi-Fi Direct DNS-SD candidate discovery; amended 2026-11-02 to make TXT optional and clarify request overload semantics. Physical-device compatibility remains unverified.
- **Date:** 2026-11-01
- **Scope:** Runtime capability checks and PEYVAND Bonjour/DNS-SD service discovery only.

## Context

The first user-visible nearby capability should identify compatible PEYVAND app instances without listing every unrelated P2P peer. A discovery result must not be confused with authenticated identity. DNS-SD callback data is untrusted network input and can be forged.

## Decision

Use Android `WifiP2pManager` DNS-SD service discovery for CP-002:

- Each actively discoverable PEYVAND instance registers a `WifiP2pDnsSdServiceInfo`.
- The instance name is `PEYVAND-` plus an eight-character random process-local hexadecimal suffix; service type is `_peyvand._tcp`.
- Public TXT data contains only `app=peyvand` and `discovery=1`.
- Each device registers a service request for the PEYVAND service type.
- The service-response callback must have a valid PEYVAND instance name and the exact Bonjour registration type Android documents in the form `_service._tcp.local.`.
- The TXT listener's full domain must parse as the exact same PEYVAND instance under `_peyvand._tcp.local.`; a substring match is insufficient.
- Treat the service-response callback as the primary discovery signal: after strict PEYVAND instance-name and Bonjour registration-type validation, create an unverified discovery candidate without waiting for TXT.
- TXT is optional metadata. Its absence must not suppress a service candidate. When a TXT callback arrives, parse its full domain strictly, correlate it by transient source-device address plus normalized service instance name, and validate app/schema values as a separate metadata status.
- A candidate or compatible public TXT record is not authentication. UI must not imply trust; connection and transfer require a separate authenticated peer handshake and explicit consent.
- Keep service/TXT association order-independent, bounded, generation-scoped and resilient to delayed, duplicated or absent callbacks.
- Select the service-request factory overload intentionally. Android documents newInstance() as all Bonjour services, newInstance(serviceType) as the typed Bonjour service/PTR query, and newInstance(instanceName, serviceType) as a TXT-data query for one known instance. The current one-argument form is retained for initial typed service discovery; it does not request TXT. Do not treat a zero TXT callback count as proof that the typed service query failed. If TXT is required in a future change, issue a bounded per-instance TXT request only after learning the instance name and account for request removal, duplicate callbacks and limits.
- Use directional DNS-SD roles for the current product flow: the Receive screen registers a local PEYVAND advertisement only; the Send screen registers a typed service request and browses only. Do not make one device advertise and browse during the same directional scan. A device can take the opposite role in a later user action. This gives a controlled advertiser/browser test and avoids confusing a sender's own advertisement with the receiver's service.
- Bound pending peer records and visible peers. Pending records are process-local and transient.
- A monotonically increasing scan generation invalidates callbacks after stop, availability loss, channel loss or failure.

No port or transport endpoint is advertised. No socket is opened and no connection is initiated.

## Lifecycle and cleanup

- Initialize the P2P channel before P2P operations.
- Observe the P2P enabled state only while PEYVAND is foregrounded and the version-appropriate permission is available.
- When the app stops monitoring, discovery is cancelled, the permission is revoked, Location Mode becomes unavailable, P2P is disabled or an action fails, invalidate the active scan generation and clear pending records.
- Independently attempt to clear registered service requests and local service advertisements. Failure to clear one resource must not prevent attempting to clear the other.
- If the framework channel is lost and immediate reinitialization fails, retry channel initialization on a later foreground availability refresh.
- Never silently toggle Wi-Fi or Location settings. Provide a user-directed settings path.

## Runtime requirements

- Check `PackageManager.FEATURE_WIFI_DIRECT` and the framework service at runtime; Wi-Fi Direct is optional hardware.
- Android 13/API 33 and later: declare/request `NEARBY_WIFI_DEVICES` with `neverForLocation`.
- Android 12L/API 32 and earlier: declare FINE and COARSE Location permissions with `maxSdkVersion=32`, request them together, and proceed only when FINE is granted. The legacy P2P service discovery API requires precise location permission even though PEYVAND does not read location coordinates.
- Declare `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE`.
- Check Location Mode because Android documents it as required by `discoverServices()`.
- Handle permission denial, disabled Location Mode, unsupported hardware, disabled P2P, BUSY, channel loss and platform errors as distinct states.
- Do not present a peer as authenticated based on service metadata, P2P address or device name.

## Network and security boundary

No `INTERNET`, socket, TLS, pairing or transfer code is added by this ADR. Android's sample guide uses `INTERNET` for the subsequent Java socket transport, not because DNS-SD requires public internet. When the P2P socket transport is designed, re-check current Android requirements and declare only the required permission. The app-default VPN diagnostic and its `ACCESS_NETWORK_STATE` permission are separately defined in ADR-0003; they are not requirements of DNS-SD discovery.

Discovery is not trust. Any later connection or data exchange requires explicit peer authentication, a cryptographically protected session and receiver consent.

## Alternatives considered

- **Raw `discoverPeers()` list:** rejected for the first user-facing list because it does not establish that a peer runs PEYVAND.
- **Network Service Discovery (NSD) on an existing LAN:** deferred; it requires a shared local network and does not fulfil the direct nearby discovery goal by itself.
- **Local-only hotspot fallback:** deferred to a separate ADR after device/API capability testing.

## Consequences and limitations

- Both devices must have PEYVAND open and actively advertising to appear in this version.
- Device names and discovery behavior vary by vendor, Android version and Wi-Fi chipset.
- The advertised app/schema fields are public compatibility markers, not secrets or credentials.
- Transient P2P address and service instance name are used only to correlate callbacks within an active scan; neither is persisted or treated as identity.
- Reciprocal discovery on two physical devices is mandatory before CP-002 can be marked PASSED.

## 2026-11-02 amendment — initial P2P state and route activation

- Do not rely exclusively on the optional sticky-intent return from receiver registration to learn the current Wi-Fi P2P enabled state.
- On API 29+, obtain the current state with `WifiP2pManager.requestP2pState()`; retain `WIFI_P2P_STATE_CHANGED_ACTION` for subsequent changes. Reject stale query callbacks after receiver, channel, permission, or monitoring lifecycle changes.
- After permissions, Location Mode, current P2P state and channel readiness are established, entering Send starts the browse-only role and entering Receive starts the advertise-only role.
- A zero-peer result does not stop an active browse. Explicit user stop cancels the current route's operation and must not be immediately undone by automatic start behavior.
- Galaxy A12 / Android 12 (API 31) supports `requestP2pState()`. Physical verification remains mandatory.

## 2026-11-02 amendment — TXT optionality and request semantics

The original decision incorrectly made a matching TXT record a hard prerequisite for listing a service response. That is now superseded. Android's Wi-Fi Direct guide states that TXT may optionally be broadcast and demonstrates service discovery through separate service and TXT listeners. The API reference further distinguishes the three request factory overloads: all Bonjour services, services of a specified type, and TXT data for a specified instance.

Implementation amendment (2026-11-02):

1. The TXT-gated publication defect is fixed. DiscoveryCandidateRegistry publishes a candidate immediately after strict service-name/type validation. A matching TXT callback received before or after the service callback only sets metadata status; an incompatible or missing TXT record does not suppress the candidate.
2. The typed serviceRequest overload remains the correct initial PTR/service-type query. It does not ask for TXT. The absence of TXT callbacks is expected unless a separate per-instance TXT query is issued; TXT itself is optional, so no TXT request is required for candidate discovery.
3. The adapter reports successful local-service registration, service-request registration and discoverServices() start independently. Callback counters are grouped by scan-local pseudonymous source tokens. The raw P2P address and user-chosen device name are not surfaced in these summaries.
4. Cleanup of service requests and local advertisements is awaited before an explicit queued restart is allowed. Repeated cleanup requests trigger another pass to avoid overlapping clear/restart actions.

Owner-reported pre-fix diagnostic result on 2026-11-02: one test phone reported service=1, accepted service=1, TXT=0, valid TXT domain=0, compatible TXT=0, peers=0; the other reported all counters=0. This does not identify the second phone's root cause. Keep CP-002 IN_PROGRESS pending CI and physical retest.


### Required verification
- Test Galaxy A12 advertiser-only with ASUS browser-only; then reverse the roles. Confirm local-service registration occurs only on the advertiser and request/discovery callbacks only on the browser.
- Verify a browser-only scan does not register a local PEYVAND service, and advertiser-only mode does not call addServiceRequest() or discoverServices().

- Test the current service-type-only request against the official no-argument all-Bonjour request and/or an explicitly added bounded per-instance TXT request. Record exact request overload and per-action outcomes.
- Verify that a valid service callback becomes a visible, explicitly unverified candidate even when no TXT arrives.
- Test no-TXT, compatible-TXT, invalid-domain, incompatible-schema, delayed-TXT, duplicate callbacks and callbacks arriving in either order.
- Test one phone advertising while the other browses, then reverse the roles; separately test both advertising+browsing.
- Verify source callback context with redacted/pseudonymized identifiers. Do not display a callback count as proof that the intended second phone was discovered.
- Audit asynchronous cleanup/restart ordering for stale registrations and BUSY; no workaround may weaken validation or security.

References:
- https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct
- https://developer.android.com/reference/android/net/wifi/p2p/nsd/WifiP2pDnsSdServiceRequest
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.DnsSdServiceResponseListener
- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.DnsSdTxtRecordListener

## Android references

- Wi-Fi Direct overview and runtime permissions: https://developer.android.com/develop/connectivity/wifi/wifi-direct
- Bonjour service discovery format and local registration: https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct
- `DnsSdServiceResponseListener` documents registration type examples such as `_ipp._tcp.local.`: https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.DnsSdServiceResponseListener
- `WifiP2pManager` documents that clearing all service requests stops a running service discovery when no requests remain: https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager


## 2026-11-02 corrective implementation amendment — readiness query and transient BUSY

A follow-up source audit found that the preceding amendment described `WifiP2pManager.requestP2pState()` as implemented, but the adapter at commit `2ac10cdb9e9abb2d7a7e11ad23f362b42ef62623` did not call it. The source and decision text were inconsistent. The follow-up implementation corrects this:

- On API 29+, query the enabled state using `requestP2pState()` after permission, receiver and channel readiness. Reject stale results after lifecycle/channel changes. If no callback arrives within five seconds, expose an actionable unavailable state instead of remaining in `CHECKING` forever. A later explicit foreground availability refresh may retry the query.
- On API levels before 29, retain the version-appropriate broadcast/action-result behavior; do not call an unavailable API.
- Android defines `BUSY` as the system being too busy to process the requested operation. The adapter now retries `addLocalService`, `addServiceRequest`, `discoverServices`, `clearServiceRequests`, and `clearLocalServices` with bounded 500/1,000/2,000 ms delays (three retries maximum). Every delayed retry is scoped to the active scan generation or cleanup pass and is cancelled/ignored after invalidation.
- Retry exhaustion remains a visible failure; the app does not display an advertisement, active scan or peer unless the corresponding Android action succeeds.
- No broad Bonjour query substitution is made in this amendment. The current typed PTR query remains standards-supported; comparison with Android's no-argument all-Bonjour sample remains an explicit controlled experiment, not an assumed fix.
- No fallback transport, socket, connection, authentication or transfer feature is introduced. Physical reciprocal discovery is still required before CP-002 can be PASSED.


## 2026-11-02 amendment — retry lifecycle and serialized cleanup

- A P2P ENABLED state broadcast invalidates any outstanding state query, but must not cancel a BUSY retry belonging to an otherwise current discovery generation. Discovery retries are now cancelled by P2P DISABLED, explicit stop, channel loss, permission loss, or generation invalidation.
- Cleanup operations are serialized: clear service requests first, then clear local services, and complete the cleanup pass only after both callbacks (or bounded terminal failures). Do not issue both framework mutations concurrently.
- These ordering changes are intended to reduce self-induced BUSY contention and stale STARTING states. They are implemented in commit `6b14c8b75942e43b4cbaebd33462bb508ad124e1`. A subsequent source review moves the missing-channel check ahead of the unknown P2P-state branch and treats receiver-registration failure as unavailable, avoiding an unresolvable CHECKING state. These changes still require physical-device confirmation; they do not establish discovery performance or reliability by themselves.
