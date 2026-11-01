# ADR-0004 — QR bootstrap for local-network pairing

- **Status:** Proposed — not approved for implementation.
- **Date:** 2026-11-01
- **Scope:** Evaluate a user-visible QR flow for creating or joining an offline local transfer session. This ADR does not replace ADR-0002 or authorize a transport change.

## Context

Physical-device testing on a Samsung Galaxy A06 and ASUS X017DA reported that the current Wi-Fi Direct DNS-SD discovery flow is not interoperating: the Galaxy reports the framework BUSY state and the ASUS does not discover a PEYVAND peer. The product owner also supplied a SHAREit screen where the receiving side displays a QR code and offers connection through a Wi-Fi hotspot.

The QR screen is evidence of a user experience that distributes local network/session information. It does not establish SHAREit's private wire protocol or prove which Android API that particular build uses. PEYVAND must not copy SHAREit's proprietary payload, protocol, branding, or UI composition.

Wi-Fi Direct (P2P) and an infrastructure/local-only Wi-Fi hotspot both use the Wi-Fi radio, but they are different Android connection mechanisms. They have different permission, lifecycle, compatibility and concurrency constraints.

## Options to evaluate

### A. Keep Wi-Fi Direct as the primary path and use QR only for authenticated pairing

- Continue to discover/connect using Wi-Fi P2P.
- After a peer is available, use QR or a short authentication string to bind the user's approval to the intended session.
- The QR must not be treated as proof of identity by itself.
- This option stays closest to the current connectivity ADR, but does not resolve devices that fail to discover each other.

### B. One device advertises a temporary local-only hotspot; the other joins using QR

- A designated host creates an Android local-only hotspot using the supported platform API where available.
- The host presents a PEYVAND-owned QR payload containing only the minimum short-lived connection bootstrap data needed by the other device.
- The joining device requests or joins the network through the supported Android flow. The app must not assume that it can silently connect or silently change system Wi-Fi settings.
- This could avoid DNS-SD discovery as the initial rendezvous mechanism, but adds a distinct hotspot lifecycle, Android version differences, user prompts and device/OEM compatibility concerns.

### C. Request an existing local Wi-Fi network

- Use a user-mediated Android network request where supported, or a clearly instructed manual join path for older Android versions.
- Both devices must be on the same local network and local peer-to-peer traffic must be permitted by that network.
- This is a useful optional route, not a replacement for offline nearby transfer: a shared router is not guaranteed and client isolation may block peers.

## QR payload and security requirements

Any PEYVAND QR format must be independently designed, versioned, bounded and strictly parsed. It must not contain user files, file names, persistent credentials, private keys or reusable device secrets.

A proposed payload may carry:
- protocol/bootstrap version;
- a random, short-lived, single-use session identifier;
- selected connection mode;
- a local endpoint or network bootstrap reference;
- temporary network credentials only when the selected Android connection mode requires them;
- an ephemeral public-key/fingerprint reference or a value used to bind the follow-up authenticated handshake;
- expiry and capability limits.

The QR is public to anyone who can view or photograph it. Therefore:
- use high-entropy short-lived credentials and invalidate them on expiry, cancellation or successful pairing;
- authenticate the peer with a reviewed cryptographic handshake and validate the TLS peer identity;
- bind receiver consent to the authenticated session and the displayed peer;
- reject replay, expired sessions, downgrade, malformed or oversized payloads and repeated attempts;
- do not trust an SSID, IP address, QR scan, device name or hotspot password as peer identity;
- never log QR contents, temporary credentials, private keys or full pairing secrets;
- keep incoming file consent separate from network joining and pairing.

## Multi-receiver constraint

The confirmed product scope includes one sender to multiple receivers. A topology where each receiver independently hosts a hotspot and the sender scans each receiver's QR may force the sender to switch Wi-Fi networks between receivers. It must not be assumed that a phone can maintain multiple independent Wi-Fi client connections or concurrent P2P/hotspot roles.

The prototype must compare at least:
1. sender-hosted local-only hotspot with multiple receivers joining one session;
2. receiver-hosted hotspot for one-to-one transfer;
3. Wi-Fi Direct group ownership and QR bootstrap;
4. capability-gated existing-LAN connection.

The final topology must be selected from physical-device evidence, including whether group membership and independent per-receiver consent/progress/cancel/recovery can be maintained without dropping existing receivers.

## Compatibility and permission investigation

- Android's WifiManager.startLocalOnlyHotspot is a supported local-only network API, but its availability, security configuration, lifecycle and client-join behavior must be verified on each target API/OEM family.
- Android 10+ WifiNetworkSpecifier is a user-mediated network request path with platform UI/approval behavior; do not assume silent association or universal concurrent connectivity.
- For older devices such as the ASUS X017DA family, verify the actual Android version/build and the supported join path. Do not infer compatibility from the model identifier alone.
- Re-check the current Android permissions for the app's target SDK and requested API path. Reuse only permissions required by the chosen transport.
- VPN presence is not sufficient evidence that a local transfer route is blocked. Validate actual sockets on the selected local network under VPN on/off, per-app VPN and lockdown configurations.

## Decision criteria

A transport is not selected until a representative physical-device matrix demonstrates:
- reliable host creation and peer join on every supported target;
- no public internet or cloud dependency;
- reliable session teardown and retry after cancellation, app backgrounding and process death;
- authenticated and encrypted peer connection with explicit receiver consent;
- no silent Wi-Fi setting changes or bypass of system approval;
- transfer streaming and integrity verification over the selected interface;
- one-to-one and one-to-many operation with independently tracked receivers;
- bounded battery, thermal, memory and radio use;
- clear, actionable UI when the OEM/OS does not support the mode.

## Current decision

Keep ADR-0002's Wi-Fi Direct implementation in place while diagnosing the physical BUSY failure. Treat local-only hotspot plus QR as a proposed alternative/fallback requiring a separate prototype and physical compatibility checkpoint. Do not add a hotspot transport or QR scanner to the app until this proposal is reviewed and the topology is approved.

## Android references

- Wi-Fi Direct: https://developer.android.com/develop/connectivity/wifi/wifi-direct
- Local-only hotspot: https://developer.android.com/develop/connectivity/wifi/localonlyhotspot
- WifiManager.startLocalOnlyHotspot: https://developer.android.com/reference/android/net/wifi/WifiManager#startLocalOnlyHotspot(android.net.wifi.WifiManager.LocalOnlyHotspotCallback,android.os.Handler)
- WifiNetworkSpecifier: https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier
