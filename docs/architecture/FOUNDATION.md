# PEYVAND Android foundation and connectivity architecture

## Current implementation scope

The repository contains the native Android foundation and the first connectivity vertical slice:

- CP-001: Gradle/Kotlin/Compose foundation, Persian-first locale bootstrap and PEYVAND design-system entry point.
- CP-002: Wi-Fi Direct runtime capability checks, version-aware permissions and PEYVAND Bonjour/DNS-SD service discovery.
- App-default network diagnostics: observes whether the network Android selects for the PEYVAND app UID is VPN-backed and shows a non-blocking warning. This diagnostic is not a local peer reachability test.

CP-002 discovers PEYVAND service advertisements. It does **not** connect peers, authenticate peers, create sockets or transfer files. A discovery advertisement is untrusted input and is not proof of identity.

## Project modules

```text
:app
 ├── :core:domain
 ├── :core:data ─────────> :core:domain
 ├── :core:connectivity ─> :core:domain
 └── :core:designsystem
```

- `:app`: Android entry point, Compose presentation, lifecycle ownership and explicit composition.
- `:core:domain`: Android-free language, discovery and network-diagnostic models/policies. It exposes `StateFlow` in repository contracts and must not import Android or Compose APIs.
- `:core:data`: Preferences DataStore and AppCompat per-app locale adapter.
- `:core:connectivity`: Android `WifiP2pManager` adapter, P2P state receiver, DNS-SD advertisement/discovery, runtime capability reporting and app-default network diagnostics through `ConnectivityManager`. It does not depend on app presentation.
- `:core:designsystem`: PEYVAND semantic colors and Compose theme.

Keep the project graph acyclic. Add security, transfer, storage and package-sharing modules only when their contracts and ownership are defined in ADRs.

## CP-002 Wi-Fi Direct discovery lifecycle

1. The Activity starts monitoring while the app is foregrounded and releases monitoring when it stops.
2. The connectivity adapter initializes a Wi-Fi P2P channel and observes the P2P enabled/disabled state.
3. Before a scan, it checks hardware/API availability, version-appropriate runtime permission, and Location Mode because Android requires it for P2P service discovery.
4. The selected screen starts one directional DNS-SD role: Receive registers the local PEYVAND service advertisement; Send registers a Bonjour service request and starts `discoverServices()`. The two roles are intentionally not symmetric.
5. A service callback is accepted only for a valid PEYVAND instance name and exact Bonjour registration type. A valid service callback is sufficient to publish an **unverified** candidate. A TXT callback is optional metadata: if received, its full domain and schema are validated separately.
6. When TXT arrives, it is correlated by source-device address and normalized instance name to enrich the existing candidate. Missing TXT leaves metadata status `NOT_RECEIVED`; it never suppresses a valid service candidate and never implies authentication.
7. Cancellation, leaving the foreground, revoking permission, losing Location Mode/P2P availability or action failure invalidates the active scan generation, clears pending data and independently attempts to remove service requests and local advertisements.
8. If the P2P channel is lost, the adapter attempts immediate reinitialization and retries on a later foreground availability refresh if necessary.
9. The UI renders actual platform state. A scan callback or animation never fabricates a discovered device.

Service TXT fields are public compatibility markers. They contain no file names, account identifiers, long-lived secrets or cryptographic identity.

## Permission model

- Android 13/API 33 and later: `NEARBY_WIFI_DEVICES` runtime permission with `neverForLocation`.
- Android 12L/API 32 and earlier: `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` declared/requested together; Wi-Fi Direct discovery proceeds only when FINE is granted.
- P2P system access: `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE`.
- App-default network diagnostic: `ACCESS_NETWORK_STATE`, declared in the `:core:connectivity` library manifest because that module uses ConnectivityManager APIs. This permission is not required by DNS-SD and does not imply a public-internet dependency.
- Wi-Fi Direct hardware is declared optional; capability is checked at runtime.

### Future local-socket permission gate

The current app targets SDK 36. Do not add `ACCESS_LOCAL_NETWORK` before the target-SDK policy requires it. Android's current guidance makes local-network protection opt-in during Android 16, then mandatory for apps targeting Android 17 / SDK 37 or higher. Direct TCP connections to and from local devices require the new runtime `ACCESS_LOCAL_NETWORK` permission at that target level. The first-file transport checkpoint must add its manifest/runtime permission flow, denial/revocation handling and Android 16 opt-in / Android 17 enforcement tests before raising `targetSdk` to 37. The current Wi-Fi Direct discovery APIs and a future raw local TCP socket must be evaluated separately; permission granted for discovery must not be assumed to cover every transport operation.

Official guidance: https://developer.android.com/privacy-and-security/local-network-permission
- No `INTERNET` permission is added for this discovery-only milestone. Local socket APIs and their permission requirements belong to a later, reviewed transport milestone.

## VPN diagnostic boundary

The app observes its own default network and checks whether it carries `TRANSPORT_VPN`. It does not attempt to discover or control every VPN configured on the device. A VPN-backed app route may have a policy that restricts local traffic, but the status alone cannot establish that Wi-Fi Direct discovery or a future P2P socket is blocked. Therefore the warning is informational and must not block scanning or transfer. See `docs/decisions/ADR-0003-VPN-ROUTE-DIAGNOSTICS.md`.

## Deferred responsibilities

No peer connection, pairing, TLS, local socket, transfer protocol, file selection, streaming, integrity verification, resume, APK extraction, PackageInstaller or background transfer is implemented in CP-002. These responsibilities require later ADRs, threat review and physical-device interoperability tests.
