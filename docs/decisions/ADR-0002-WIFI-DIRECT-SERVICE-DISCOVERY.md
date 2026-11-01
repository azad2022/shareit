# ADR-0002 — Wi-Fi Direct service discovery

- **Status:** Accepted for CP-002 implementation; physical-device compatibility remains unverified.
- **Date:** 2026-11-01
- **Scope:** Runtime capability checks and PEYVAND service discovery only.

## Context

The foundation is built and installed on a product-owner phone. The next user-visible capability should identify nearby devices that are running PEYVAND. Listing every Wi-Fi P2P peer would also list devices that do not run PEYVAND, and a discovery result must not be confused with an authenticated peer.

## Decision

Use Android `WifiP2pManager` DNS-SD service discovery for CP-002:

- Each actively discoverable PEYVAND instance registers a `WifiP2pDnsSdServiceInfo`.
- The DNS-SD instance name is `PEYVAND`; the service type is `_peyvand._tcp`.
- The public TXT record contains only `app=peyvand` and `discovery=1`.
- Each device registers a service request for this service type.
- The app displays a peer only after both the service-response callback and matching TXT callback have been received for the same transient P2P device address.
- Discovery metadata is a compatibility filter, not a secret or an authentication credential. Any nearby peer can forge it.

Do not include a listening port or transport endpoint in this milestone. No socket is opened and no connection is initiated.

## Runtime requirements

- Initialize the P2P channel before P2P operations.
- Check `PackageManager.FEATURE_WIFI_DIRECT` and the framework service at runtime; Wi-Fi Direct is optional hardware and must not be required for installation.
- On Android 13/API 33 and later, declare/request `NEARBY_WIFI_DEVICES` and assert `neverForLocation`.
- On Android 12L/API 32 and earlier, declare/request `ACCESS_FINE_LOCATION` with `maxSdkVersion=32`.
- Declare `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE`.
- Check Location Mode because Android documents it as required for `discoverServices()`.
- Observe P2P enabled/disabled state while the app is foregrounded. Stop discovery, remove P2P service requests/local services, unregister the receiver, and discard transient peer results when leaving the foreground or cancelling.
- Handle missing permission, disabled Location Mode, unsupported hardware, disabled P2P, BUSY, channel loss, and other platform failures as distinct states.
- Never silently toggle Wi-Fi or Location settings. Provide user-directed settings links where appropriate.

## Network and security boundary

No `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, socket, hotspot, or TLS code is added by this ADR. Android's sample service-discovery guide includes INTERNET because its full sample subsequently uses Java sockets; CP-002 stops before that transport step. When the P2P socket transport is designed, re-check current Android requirements and add only the permission needed for local sockets. That permission does not imply use of public internet.

Discovery does not establish trust. Before any later connection or data exchange, PEYVAND must perform explicit peer authentication, a cryptographically protected session, and receiver consent. Discovery TXT values, P2P device names, and device addresses are not proof of identity.

## Alternatives considered

- **Raw `discoverPeers()` list:** rejected for the first user-facing list because it does not establish that a peer runs PEYVAND.
- **Network Service Discovery (NSD) on an existing LAN:** deferred; it requires a shared local network and therefore does not fulfill the direct nearby discovery goal by itself.
- **Local-only hotspot fallback:** deferred to a separate ADR after device/API capability testing.

## Consequences and limitations

- Devices must have PEYVAND open and actively advertising to appear in this version.
- Device names and discovery behavior vary by vendor, Android version, and Wi-Fi chipset.
- Wi-Fi Direct support and reliable discovery are not guaranteed across all devices.
- The transient P2P address is used only to correlate callbacks within a scan; it is not persisted or displayed as an identity.
- A two-physical-device test is mandatory before CP-002 can be marked PASSED.
