# ADR-0006 — Bidirectional DNS-SD discovery and foreground session recovery

- **Status:** Accepted for implementation; physical interoperability validation pending.
- **Date:** 2026-11-04
- **Scope:** CP-002 discovery only. No peer connection, pairing, TLS, local socket, file transfer, APK handling, or fallback transport is introduced.

## Context

The owner reports that the current one-way role model works, at best, only when the receiver starts advertising before the sender starts browsing. Simultaneous start and late receiver advertisement are not reliably discovered. The owner also reports that a later discovery attempt often fails until both applications are exited and reopened.

The active implementation encoded a one-way split:
- Receive registered a local PEYVAND DNS-SD service only.
- Send registered a DNS-SD service request and browsed only.

That split unnecessarily couples discovery to the screen role. Android documents that a registered local service automatically responds to peer service-discovery requests, and separately supports registering service requests and invoking `discoverServices()`. There is no product requirement that the local app be advertiser-only or browser-only.

A second lifecycle defect exists in the Compose screen: the one-shot `automaticStartConsumed` flag remains set while the Activity is stopped. `MainActivity.onStop()` stops repository monitoring and discovery, but returning to the same composed screen does not necessarily re-run the one-shot start effect. A process restart resets this transient flag, which can make relaunch appear to repair discovery.

## Decision

1. Both Send and Receive screens register the same PEYVAND local service and a PEYVAND DNS-SD service request, then call `discoverServices()`.
2. DNS-SD service/TXT listeners are installed for both screen roles. Existing strict PEYVAND instance and service-type validation remains mandatory; TXT remains optional metadata and is not identity or authentication.
3. Keep the narrow typed service request `newInstance(SERVICE_TYPE)`. The prior all-Bonjour request experiment did not resolve the owner's reported order dependency.
4. The scan lifecycle controller observes discovery-state transitions and re-arms the active session after a confirmed STOPPED state for either Send or Receive.
5. Auto-start is governed by Activity foreground state, current permission/platform readiness, IDLE session state, and a user-stop flag. A user-initiated stop is respected; a foreground return or a newly entered discovery screen starts a fresh session when ready.
6. The selected-file collection remains independent. Clearing or retaining selected items must not be a prerequisite for discovery. The file-selection ViewModel does not own the Wi-Fi P2P repository.
7. Keep CP-002 `IN_PROGRESS` until both devices discover each other repeatedly in both timing orders on the target device matrix. CI is necessary but insufficient.

## Expected behavior

- Receiver first, sender second: both advertise and browse; candidates should appear without relying on start order.
- Sender first, receiver second: the already-running browse should be able to receive the later advertisement; both devices also browse for each other.
- Simultaneous launch: both register and browse independently; service callback and TXT callback order remain independent.
- Leave the app and return: the old foreground scan is stopped; after the Activity resumes and platform readiness is re-established, discovery restarts unless the user explicitly stopped it.
- Stop then start on the same screen: the explicit Stop is respected, and a subsequent user Start can begin a fresh session.

## Validation required

- Unit tests for role-independent re-arm and foreground auto-start gates.
- Exact-head CI: unit tests, all configured lint tasks, and Debug APK assembly.
- Physical tests on Samsung Galaxy A12 (SM-A125F / Android 12) and ASUS X017DA: at least five trials for each timing order (receiver first, sender first, simultaneous); at least five stop/start repetitions without force-stopping either app; repeat after background/foreground transition.
- Record exact APK SHA-256, firmware/API, permissions, Location Mode, VPN state, per-device action outcomes, raw/accepted service callback counts, and time-to-first-candidate.
- Reject any test run in which unrelated Bonjour service records pass the PEYVAND filter.
- Do not mark CP-002 PASSED from CI or emulator evidence alone.

## Risks

- Wi-Fi P2P scheduling and DNS-SD interoperability vary across OEMs/chipsets/Android builds. Bidirectional service registration removes an application-imposed role asymmetry; it cannot guarantee identical radio timing or instantaneous discovery on every handset.
- A discovery candidate remains unverified. Pairing/authentication and consent are later security gates.
- Current CP-002 still does not establish a peer connection or transfer files.
