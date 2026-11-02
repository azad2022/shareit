# ADR-0005 — Wildcard Bonjour query compatibility experiment

- **Status:** Experimental, Debug-only; not accepted as the production discovery strategy.
- **Date:** 2026-11-02
- **Branch:** `feature/dns-sd-wildcard-query-experiment`
- **Base:** `feature/foundation-bootstrap` at `df7c595462ed445a721cc0ddb95bc754ae3b4ec8`
- **Scope:** Isolate whether typed Bonjour PTR filtering contributes to the observed Wi-Fi Direct DNS-SD interoperability failure.

## Context

PEYVAND currently registers a typed DNS-SD request with `WifiP2pDnsSdServiceRequest.newInstance("_peyvand._tcp")`. This overload is supported by Android and is the narrow request expected for the product. Despite accepted local advertisement/request/start actions, physical tests on a Samsung Galaxy A12 (SM-A125F, Android 12) and an ASUS X017DA have repeatedly failed to discover each other. The current evidence does not identify a definitive root cause.

The Android Wi-Fi Direct guide demonstrates the no-argument `WifiP2pDnsSdServiceRequest.newInstance()` overload, which searches all Bonjour services. The API reference describes the typed overload as searching only the supplied service type and the no-argument overload as searching all Bonjour services:

- https://developer.android.com/reference/android/net/wifi/p2p/nsd/WifiP2pDnsSdServiceRequest
- https://developer.android.com/develop/connectivity/wifi/nsd-wifi-direct

A broader query can help distinguish a request-filtering interoperability problem from failures lower in P2P discovery or service advertisement. It may also increase response traffic and expose additional Bonjour callback metadata to the app process.

## Experimental decision

On this isolated Debug experiment branch only:

1. Register the no-argument all-Bonjour request.
2. Keep Receive as `ADVERTISE_ONLY` and Send as `BROWSE_ONLY`.
3. Keep the service response validation unchanged: only the exact PEYVAND service registration type and a valid PEYVAND instance suffix may produce a visible candidate.
4. Keep TXT optional and metadata-only. Do not treat it as identity or authentication.
5. Do not add `discoverPeers()`, another transport, connection, socket, or file transfer in this experiment.
6. Do not merge this behavior into the production line until the controlled physical comparison and review below are complete.

This is an experiment, not a claim that the typed request is defective or that the wildcard query fixes the issue.

## Test procedure

Run the exact same app build on both physical devices and record its commit SHA.

**Test A — late advertiser**
- Device 1: Send / BROWSE_ONLY. Start scanning and confirm `serviceRequestRegistered=true`, `discoveryStarted=true`, and actual platform state `RUNNING`.
- Wait 10 seconds.
- Device 2: Receive / ADVERTISE_ONLY. Confirm `localServiceRegistered=true` and status `ADVERTISING`.
- Observe for at least 120 seconds and capture the service-response and candidate counters on the sender.

**Test B — late browser**
- Stop both scans and allow teardown to finish.
- Device 1: Receive / ADVERTISE_ONLY. Confirm local service registration.
- Wait 10 seconds.
- Device 2: Send / BROWSE_ONLY. Confirm service-request registration and actual scan state.
- Observe for at least 120 seconds and capture response/candidate counters.

Repeat each order at least five times, keeping permissions, Location Mode, Wi-Fi state, VPN configuration, physical distance and app build constant. Record firmware/API details. A single hit is not a reliability pass.

## Acceptance and rollback

- CI must pass the exact experiment commit: domain tests, all configured lint tasks, and Debug APK assembly.
- Physical results must demonstrate repeatable discovery in both timing orders on the target device pair. Separately verify that non-PEYVAND Bonjour services never become visible PEYVAND candidates.
- Compare callback volume and scan behavior against the typed-request baseline where the same baseline APK is available.
- If there is no repeatable improvement, revert the request to the typed overload and investigate advertisement registration, P2P state, radio coexistence, and OEM firmware using synchronized device diagnostics.
- CP-002 remains `IN_PROGRESS` until physical interoperability acceptance criteria are met.
- No production/release signing or release build is authorized by this ADR.

