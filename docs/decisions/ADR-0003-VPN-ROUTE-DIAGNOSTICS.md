# ADR-0003 — Non-blocking VPN route diagnostics

- **Status:** Accepted for the discovery milestone; local socket behavior remains deferred to the transport ADR and physical-device tests.
- **Date:** 2026-11-01
- **Scope:** Detect whether PEYVAND's application-default network is a VPN and present a user-directed warning. This does not select, bypass, disable, or reconfigure a VPN.

## Context

Some VPN products or user configurations can restrict traffic to local networks. However, the mere presence of a VPN on a device does not establish that PEYVAND's Wi-Fi Direct service discovery or a future local P2P socket is blocked. VPNs may use per-app inclusion/exclusion, split tunneling, or local-network exceptions.

A hard block or mandatory request to turn off VPN would therefore produce false positives and could disrupt a user's security configuration.

## Decision

Observe only the network Android selects as the default for the PEYVAND app UID:

- Use `ConnectivityManager.registerDefaultNetworkCallback` while the Activity is foregrounded and unregister it on stop.
- Classify the app's current default network using `NetworkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)`.
- Represent the result as `CHECKING`, `ACTIVE_FOR_APP`, `NOT_ACTIVE_FOR_APP`, or `UNKNOWN`.
- Show a non-blocking warning only when the app-default network is reported as VPN-backed.
- Explain that some VPN policies may restrict local-network traffic, while explicitly stating that VPN detection alone does not prove a PEYVAND local connection is blocked.
- Link to Android's VPN settings. The user may allow local-network/LAN access in the VPN configuration or temporarily disable that VPN when troubleshooting an observed failure.
- Never turn off, bypass, or reconfigure a third-party VPN automatically.

Declare `ACCESS_NETWORK_STATE` in the `:core:connectivity` library manifest because its ConnectivityManager adapter calls APIs guarded by that permission. Keep the declaration in the app manifest as well if app-owned code later directly uses those APIs; duplicate manifest permission declarations are merged.

## Interpretation limits

- This observes the default network applicable to PEYVAND's UID, not a universal device-wide VPN-on/off switch.
- A VPN can be active on the device while PEYVAND is excluded from it; in that case the app-default network may not report `TRANSPORT_VPN`.
- `ACTIVE_FOR_APP` does not prove that local Wi-Fi, Wi-Fi Direct discovery, or P2P sockets are blocked.
- `NOT_ACTIVE_FOR_APP` does not prove every network path is unrestricted.
- The status is diagnostic context only. It must not prevent discovery or be treated as a connection failure.
- This feature does not use `VpnService`; that API is for applications implementing a VPN, not for controlling another provider's VPN.

## Deferred validation

Before implementing the local transport, a separate ADR must define how PEYVAND establishes and binds sockets to the Wi-Fi Direct network. Validate at least:

- A VPN that tunnels all app traffic.
- A VPN with a local-network/LAN allow-list or exception.
- Per-app VPN inclusion and exclusion.
- Always-on VPN and lockdown configurations where available.
- Permission and behavior differences across the supported Android/API and device matrix.
- Actual peer reachability, connection establishment, and data integrity with VPN active.

Do not infer transport success or failure from `TRANSPORT_VPN` alone. Base future recommendations on a reproducible local-connectivity test and report the exact condition that failed.

## Android references

- `ConnectivityManager.registerDefaultNetworkCallback` observes the application's default network, which can be physical or a VPN, and requires `ACCESS_NETWORK_STATE`: https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)
- `NetworkCapabilities` and transport types: https://developer.android.com/reference/android/net/NetworkCapabilities
- Read network state and callback lifecycle: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
