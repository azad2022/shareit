# ADR-0008 — P2P-interface-bound TCP channel

- **Status:** Accepted for the CP-003B transport substage; physical interoperability and security validation remain mandatory.
- **Date:** 2026-11-04
- **Scope:** Establish exactly one raw, bidirectional TCP socket inside an already formed Wi-Fi Direct group. This ADR does not authorize sending application data before peer authentication and receiver consent.

## Context

CP-003A defines a bounded, versioned transfer protocol and streaming primitives. CP-003B already establishes a Wi-Fi Direct group, but a formed group is not a TCP session and does not authenticate either peer. The first live transport must work regardless of which device Android elects as Group Owner.

Android may anonymize the local Group Owner address in `WifiP2pInfo.groupOwnerAddress`. The Group Owner therefore must not bind to that value or to a guessed default address. Android exposes the interface name associated with the group through `WifiP2pGroup.getInterface()`; the framework's group and connection information are queried only after group formation is confirmed.

## Decisions

### 1. Resolve the actual Wi-Fi Direct interface

1. Reuse the existing `WifiP2pManager` and `Channel` owned by the discovery repository. Do not initialize a second P2P channel or register a competing receiver.
2. Call `requestGroupInfo()` and require the group role to match the role already observed via `requestConnectionInfo()`.
3. Read `WifiP2pGroup.getInterface()` and resolve that exact name using `NetworkInterface.getByName()`.
4. Require the interface to be up and non-loopback. Select an IPv4 address only from that interface. Require a private/site-local unicast address and a usable prefix length from 1 through 30.
5. Fail closed if the group, interface, local address, role or route information cannot be verified. Never enumerate unrelated interfaces as a fallback, use a guessed `192.168.49.1`, or bind to `0.0.0.0`.

### 2. Server/client role

1. The device that Android reports as Group Owner hosts one `ServerSocket`, bound to its exact local P2P IPv4 address and protocol port `49273`; backlog is one.
2. The Group Client creates one `Socket`, binds its source address to its own local P2P IPv4 address, then connects only to the remote Group Owner address obtained from `requestConnectionInfo()`.
3. Both local and remote IPv4 addresses must be in the same private P2P subnet according to the actual local interface prefix. Reject loopback, link-local, multicast, wildcard, public and out-of-subnet endpoints.
4. A single full-duplex TCP socket is used regardless of which product user initiated the P2P group. Group Owner/client role is a network role, not sender/receiver role.
5. Port `49273` is a provisional, versioned PEYVAND transport port. It is not advertised through DNS-SD and is not exposed to the UI.

### 3. Timeouts, retries and resource ownership

- Group-info resolution: 5-second timeout.
- Group Owner accept: 20-second overall timeout, polled at finite intervals so cancellation and group teardown can close the listener.
- Group Client connection: 15-second overall deadline, 1.2-second attempts and 250 ms bounded retry delay while the Group Owner listener starts.
- Reject after three accepted TCP peers whose source is outside the expected P2P subnet.
- The connectivity repository owns the listener, pending client socket and accepted/connected socket. All are closed on P2P group removal, permission loss, channel loss, monitoring teardown, cancellation, replacement or failure.
- Only one active raw TCP session is allowed. No queue or unbounded connection pool is introduced.
- Do not tune TCP buffer sizes or claim a speed benefit without device measurements.

### 4. Security boundary

1. A successful TCP connect is transport readiness only. It is not peer authentication, SAS approval, receiver consent, or permission to exchange file metadata.
2. This stage sends no application payload, manifest, filename, MIME type, size, protocol frame, or file byte.
3. The connected socket remains private to the connectivity/security boundary. The UI receives only an address-free state snapshot and cannot access the socket or streams.
4. The socket is retained for the next session-authentication stage, which must establish a fresh session identity, bilateral SAS approval and pinned mutual TLS before any application data is exchanged.
5. The socket timeout is finite and is reserved for the upcoming authentication handshake. No cleartext bootstrap record is implemented in this stage.

### 5. Permissions and local-network policy

- Declare the normal `INTERNET` permission for Android TCP socket APIs. It does not imply or create public-Internet connectivity, a backend, analytics upload, cloud storage or relay.
- The app currently targets SDK 36. Do not declare or request `ACCESS_LOCAL_NETWORK` at this target.
- Test Android 16's opt-in local-network restrictions using the existing `NEARBY_WIFI_DEVICES` permission flow.
- Before raising target SDK to 37, add and test `ACCESS_LOCAL_NETWORK` runtime permission declaration, request, denial and revocation handling as a separate compatibility gate.
- Generic file access remains URI-based through SAF/ContentResolver; no broad storage permission is added by this ADR.

## Implementation and validation

- Platform adapter: `WifiDirectTcpChannelController` in `:core:connectivity`.
- Domain policy/state: `P2pTcpChannelModels` in `:core:domain`.
- Unit tests: private IPv4 address selection, excluded address/prefix classes and same-subnet enforcement.
- Required physical tests: both Group Owner outcomes across the Galaxy A12 (SM-A125F) and ASUS X017DA, repeated reconnect/teardown, VPN enabled/disabled, socket timeout and permission denial/revocation. Record the APK SHA-256 and Android build for every trial.
- CI can verify compilation and pure policy tests, but cannot prove that an OEM exposes the expected interface or that packets route through Wi-Fi Direct on the target phones.

## Consequences and deferred work

This ADR establishes only the raw local channel. It does not complete CP-003 and does not satisfy the file-transfer acceptance criteria. Keystore identity, SAS exchange/UI, mutual TLS/pinning, receiver consent, framed control/data protocol integration, URI streaming, private staging, SHA-256 publication verification and authenticated completion receipt remain mandatory follow-on work under ADR-0007.
