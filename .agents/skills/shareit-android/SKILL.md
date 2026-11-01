---
name: shareit-android-engineering
description: Engineering policy and product-specific guidance for the SHAREit Android project: offline peer-to-peer connectivity, secure file transfer, APK and split-APK sharing, Android storage/package APIs, reliability, privacy, testing, and release quality. Load whenever planning, designing, implementing, reviewing, testing, or documenting this repository.
---

# SHAREit Android — Engineering Skill

## 1. Mission and repository boundary

Act as the senior Android systems engineer for this repository. The product is an independently engineered, commercially reliable Android application for direct wireless transfer of user-selected files and eligible Android application packages between nearby devices.

The product is inspired by the category and user needs addressed by SHAREit. Do not copy proprietary source code, artwork, trademarks, exact screen designs, text, or other protected assets from SHAREit or any other product.

### Current repository state

This repository is in the product-definition and engineering-planning stage. It contains project guidance, not an application implementation.

- Do not create Android source code, Gradle scaffolding, mock implementations, screens, or build configuration unless the user explicitly asks to begin implementation.
- A request to discuss, review, plan, or refine the product does not authorize coding.
- When asked to plan, return a decision-ready engineering proposal and identify unresolved decisions.
- When asked to implement, make the smallest complete, testable change that satisfies the approved scope.
- Never claim that a feature works, a test passed, a build succeeded, or a device is supported without direct evidence from repository state or test output.

The product owner's explicit decisions take precedence over assumptions in this skill. If a material decision is unclear, explain its impact and ask one focused question rather than silently choosing a consequential behavior.

## 2. Confirmed product definition

### Confirmed scope

- Platform: Android only.
- Primary purpose: send and receive files and Android application packages between nearby devices.
- Connectivity: direct local wireless communication; the core transfer workflow must not require internet access.
- Quality priorities: security, reliability, data integrity, compatibility, predictable resource usage, and maintainability.
- Product ambition: a complete, polished file-and-app transfer product, not a throwaway demo.

### Core user capabilities

1. Discover nearby devices that are actively available to send or receive.
2. Establish an authenticated connection with explicit user consent.
3. Browse and select permitted files, media, folders, and eligible installed application packages.
4. Send and receive one or many items.
5. Show accurate transfer state, progress, throughput, and errors.
6. Pause, cancel, recover, and resume transfers where source and destination data remain valid.
7. Verify received data before publishing it as a completed file.
8. Inspect received application packages and request installation through Android's supported installation flow.
9. Review local transfer history and safely clean up abandoned temporary data.

### Explicit product boundaries

The app transfers files and installable package artifacts; it does not clone the complete operating system or bypass Android application sandboxing.

Never promise or implement, without a legitimate platform-supported mechanism:

- Extraction or transfer of another app's private sandbox data.
- Silent installation, silent updates, or bypassing Android's package installer and user consent.
- Reading credentials, private databases, or protected data belonging to other apps.
- Circumvention of Android package visibility, storage, permission, background-execution, or Google Play policy restrictions.
- Guaranteed operation after Force Stop, device shutdown, revoked permissions, or vendor-enforced process termination.
- Guaranteed Wi-Fi Direct support or identical throughput on every Android device.

Do not describe APK transfer as equivalent to app-data migration. Clearly explain that app data, accounts, permissions, protected assets, and external downloads may not be included.

## 3. Engineering principles

Apply these principles to every design and code review:

1. Correctness before speed. A fast transfer that silently corrupts or loses a file is a release blocker.
2. Security by design. Discovery is not authentication; being on the same Wi-Fi network does not make a peer trusted.
3. User consent. The sender chooses what to send and the receiver explicitly accepts incoming transfers. Installation is a separate user-controlled action.
4. Offline by default. The core workflow must not require accounts, cloud storage, analytics, or a remote relay.
5. Bounded resource use. Never load an entire large file into RAM. Bound queues, sockets, buffers, retries, and concurrent work.
6. Recoverable operations. Persist enough durable state to detect incomplete transfers and resume safely.
7. Platform compliance. Use supported Android APIs and honor current permission, package-installer, foreground-service, and distribution policies.
8. Observable but private. Diagnostics must explain failures without recording file contents, secrets, or unnecessary personal metadata.
9. Modular boundaries. Presentation, domain rules, storage, connectivity, security, and transfer mechanics must remain independently testable.
10. Evidence-based claims. Report measured performance by device and test conditions; do not turn best-case lab throughput into a universal promise.

## 4. Proposed architecture

Use native Android development. Kotlin and Jetpack Compose are the preferred starting choices, subject to an approved architecture decision record (ADR). Avoid adding a cross-platform framework merely for abstraction.

Keep the architecture layered:

- Presentation: Compose screens, navigation, ViewModels, and UI state.
- Domain: transfer policies, entities, use cases, validation rules, and repository contracts.
- Data: Room persistence, file-provider adapters, package metadata, and transfer history.
- Infrastructure: Wi-Fi discovery, connection lifecycle, sockets, TLS, protocol framing, streaming, and checkpointing.
- Platform adapters: Android permissions, MediaStore, Storage Access Framework, PackageManager, PackageInstaller, and service/job APIs.

Suggested logical components (names are illustrative; do not create them until implementation is explicitly authorized):

- DeviceDiscovery
- ConnectionManager
- PeerAuthentication
- TransferProtocol
- TransferCoordinator
- TransferWorker
- CheckpointStore
- FileAccessRepository
- PackageSharingRepository
- PackageCompatibilityChecker
- IncomingFilePublisher
- PermissionCoordinator
- TransferHistoryRepository

No UI class should own socket logic, file streaming, package extraction, or cryptographic policy. No transport class should depend on Compose or Android UI types.

### Architecture decisions requiring ADRs

Before implementation locks in a consequential choice, record an ADR for:

- Minimum supported Android API level and target SDK policy.
- Preferred connection path and fallback order.
- Protocol framing and serialization format.
- Peer identity, pairing, key lifecycle, and certificate validation.
- Transfer checkpoint durability and resume semantics.
- Background execution model for long-running user-initiated transfers.
- Data staging and publication strategy for MediaStore and SAF destinations.
- Package visibility and distribution-channel strategy.

The previously discussed API 26 minimum is a proposal only, not a confirmed product decision. Validate it against required APIs, device coverage, and support cost before committing to it.

## 5. Connectivity and discovery

### Candidate connection methods

Evaluate and support, where the device and OS allow:

1. Wi-Fi Direct through Android Wi-Fi P2P APIs.
2. A local-only hotspot flow where supported and permitted.
3. An existing local Wi-Fi network as an optional path.

Use a connection strategy abstraction. The app must detect capabilities at runtime and report actionable reasons when a method is unavailable.

Do not assume that:

- Every Wi-Fi chipset supports the same P2P behavior.
- Wi-Fi Direct discovery always works while another network mode is active.
- The app can silently change system Wi-Fi state or join arbitrary networks.
- Local-only hotspot creation and client joining are unrestricted on every Android release.
- A peer discovered by NSD or Wi-Fi P2P is authenticated.

Use the minimum permissions required for the selected APIs and Android version. Re-check current official Android documentation when implementation begins.

### Connection lifecycle

Model connection state explicitly. At minimum distinguish:

- idle
- checking_capabilities
- discovering
- peer_found
- awaiting_user_consent
- connecting
- authenticating
- connected
- transferring
- reconnecting
- disconnecting
- failed
- closed

Every transition must have defined timeout, cancellation, cleanup, and error behavior. Avoid hidden global connection state. Prevent duplicate connections and orphaned sockets.

Discovery advertisements should expose only the metadata needed to find a compatible app instance. Never expose file names, private identifiers, or long-lived secrets in discovery payloads.

## 6. Secure peer authentication

Treat the local network as hostile.

- Encrypt all transfer traffic using a reviewed TLS implementation.
- Prefer TLS 1.3 when the selected Android runtime/provider supports it; define a deliberately reviewed compatibility policy where required. Never weaken certificate checks to make a connection succeed.
- Authenticate the peer, not just the encrypted channel. Define an explicit pairing mechanism such as an authenticated QR bootstrap or short authentication string (SAS) comparison.
- Bind pairing approval to the current session and intended peer.
- Use cryptographically secure random values for session IDs, challenges, and ephemeral secrets.
- Protect persistent private keys with Android Keystore when persistent device identity is actually required.
- Keep ephemeral session secrets short-lived and erase them when the session closes.
- Defend against replay, downgrade, peer substitution, malformed messages, and resource-exhaustion attempts.
- A user-visible device name or MAC address is not proof of identity.

Security-sensitive code must be reviewed independently. Never invent a cryptographic protocol when a well-reviewed standard mechanism can be used.

## 7. Transfer protocol and data plane

The internal protocol may be called WTP (Wireless Transfer Protocol) during planning. The name is provisional.

The protocol must be versioned and transport-independent at the domain boundary. It should separate:

- Control plane: session negotiation, capabilities, manifest, acceptance, rejection, cancellation, acknowledgements, errors, and resume negotiation.
- Data plane: bounded binary file chunks with explicit item ID and offset.

Protocol requirements:

- Use unambiguous framing, such as a bounded length-prefixed frame format.
- Validate protocol version, message type, lengths, field types, and state transitions before acting.
- Apply a strict maximum size to control messages and reject oversized or malformed frames before allocation.
- Do not deserialize untrusted messages into unrestricted polymorphic object graphs.
- Include session ID, transfer ID, item ID, and sequence/offset information as appropriate.
- Make retries and duplicate chunk handling deterministic and idempotent.
- Negotiate only mutually supported capabilities.
- Reject unsupported protocol versions cleanly.
- Never accept paths supplied by a remote peer as trusted filesystem paths.

A final wire format, exact field names, chunk size, concurrency, and buffer tuning must be decided in an ADR and validated by interoperability tests. Do not hard-code an unmeasured chunk size as a universal optimum.

### Streaming and integrity

- Stream data from a file/content descriptor to the network using bounded buffers.
- Apply backpressure; do not accumulate unbounded queued chunks.
- Bound simultaneous file transfers and in-flight data.
- Handle partial reads/writes, timeouts, socket closure, and cancellation.
- Calculate a cryptographic digest over the completed file; SHA-256 is the baseline candidate.
- Verify the completed destination against the manifest before marking the item complete.
- Where useful, add per-chunk integrity checks for recovery and diagnosis, but retain full-file verification.
- A transfer is not complete merely because the sender finished writing to a socket.

### Resume semantics

Resume is a correctness feature, not an offset guess.

- Write incoming data to a controlled temporary artifact.
- Persist checkpoints only after the corresponding bytes are durably committed according to the chosen storage strategy.
- Store transfer ID, item ID, committed offset, expected length, and enough source/destination identity metadata to validate resume.
- On reconnect, authenticate again and negotiate the receiver's durable offset.
- Validate that the source has not changed; at minimum compare relevant stable metadata and verify final content.
- Reject stale, mismatched, or tampered resume state and restart safely when required.
- Never expose a partially received file as a completed destination item.
- Clean up abandoned temporary data using an explicit retention policy.

Define how durable writes and SAF document providers behave before promising crash-safe resume across all destinations.

## 8. Android file access and storage

Use Android's supported storage abstractions:

- MediaStore for user media collections where applicable.
- Storage Access Framework (SAF) and user-selected document/tree URIs for general documents and directories.
- App-specific storage for internal state and temporary artifacts.

Prefer URI-based access through ContentResolver. Do not assume that every user-visible file has a stable raw filesystem path.

Do not request broad all-files access merely for convenience. MANAGE_EXTERNAL_STORAGE requires a compelling eligible core use and is subject to distribution policy review. Request only permissions needed for an immediately understandable user action.

Permission behavior must be:

- Just-in-time, contextual, and explained.
- Version-aware.
- Resilient to denial, revocation, and restricted states.
- Tested on the minimum and latest supported Android versions.

For destination publishing, define a safe staging strategy. Some document providers do not provide atomic rename or identical durability semantics. Do not claim universal atomic publication without testing provider behavior.

## 9. Application package sharing

Application sharing is a first-class feature, but it must respect Android package and installation rules.

### Discovery and visibility

Use PackageManager APIs and the narrowest package visibility configuration that supports the documented user-facing feature. QUERY_ALL_PACKAGES is sensitive and may be restricted by Google Play policy. Its inclusion is not automatic; document the core use case and review current policy for the intended distribution channel.

Do not imply that the app can inspect every installed package on every device without qualification.

### Package extraction and manifest

For an eligible installed application, inspect package metadata and available APK artifacts through supported PackageManager information, including base and split APK paths where accessible.

A transferable package manifest should record at least:

- package name
- version name and version code
- min/target SDK information when available
- signing-certificate digest(s)
- base APK size and digest
- split APK names, roles, sizes, and digests
- required ABI/density/language configuration where relevant
- manifest format version

Transfer all required base and split APK components as a consistent set. Do not assume that a single base APK is installable on the target.

### Compatibility and installation

Before offering installation, check what can be checked:

- Android version and SDK compatibility.
- CPU ABI compatibility.
- Required split availability.
- Package name and version consistency.
- Signing certificate consistency across components and with an installed package if this is an update.
- Available storage.
- Whether package artifacts are complete and digest-verified.

Use Android's supported PackageInstaller session workflow. Installation must remain user-approved and subject to the OS confirmation flow. Never attempt silent installation or a security bypass.

Some apps depend on private data, expansion assets, downloaded content, licensing, Play services, device features, or account state that APK transfer does not include. Show this limitation accurately.

### Package security

Treat incoming APKs as untrusted files:

- Keep them in a controlled staging area until verified.
- Verify hashes and package metadata.
- Inspect signing certificate information.
- Do not execute or install automatically.
- Do not claim that a valid signature proves an app is benign.
- Keep installation separate from file receipt and require explicit user intent.

## 10. Background execution, lifecycle, and power

Long transfers must respect Android lifecycle and background execution limits.

- Design transfer work around explicit user initiation.
- Select the appropriate foreground-service type or User-Initiated Data Transfer mechanism based on the current Android API level and official policy.
- Do not assume a generic dataSync foreground service can run indefinitely; OS-version timeouts and restrictions must be accounted for.
- Present a truthful ongoing notification when required.
- Handle process death, app task removal, permission changes, Wi-Fi changes, battery restrictions, and vendor-specific process management.
- Support graceful cancellation and recovery after the app is reopened.
- Never promise continuation after Force Stop or other OS/user termination that prevents execution.

Use lifecycle-aware ownership and structured concurrency. Every coroutine, socket, descriptor, service, and temporary file must have a clear owner and cleanup path.

## 11. Implementation quality rules

When implementation is authorized:

- Use Kotlin with explicit nullability and typed domain models.
- Prefer immutable state and unidirectional UI state flow.
- Use structured concurrency; avoid GlobalScope and unmanaged threads.
- Keep I/O off the main thread.
- Use bounded dispatchers/queues for network and disk operations.
- Inject platform dependencies behind interfaces where this improves testability.
- Keep Android framework types out of pure domain rules.
- Avoid service locators, mutable global singletons, and giant ViewModels.
- Avoid premature abstraction and unnecessary dependencies.
- Pin and review dependencies; check licenses and known vulnerabilities.
- Never commit credentials, signing keys, keystores, private certificates, or user data.
- Run formatting, static analysis, unit tests, and relevant instrumentation tests before claiming a change is ready.

Do not add analytics, advertising SDKs, crash-reporting uploads, cloud sync, account systems, or remote APIs without explicit product approval and a privacy review.

## 12. Privacy and data handling

The default product posture is local-first and data-minimal.

- Do not upload user files or file contents to a server.
- Do not require account registration for local transfer.
- Do not collect contacts, location, installed-app inventory, or usage analytics unless a clearly approved feature requires it.
- Do not log file contents, access tokens, pairing secrets, private keys, or unnecessary sensitive paths.
- Keep transfer history local and provide user-controlled deletion.
- Document what metadata is retained, why, and for how long.
- Make incoming-file destination and retention behavior understandable.
- Ensure temporary artifacts are removed safely after cancellation or expiry.

Any proposed online feature requires a separate threat model, data-flow description, consent design, and product approval.

## 13. Testing strategy

A single emulator test is insufficient for a wireless transfer product. Use unit tests, integration tests, instrumentation tests, and physical-device testing.

### Required test dimensions

- Multiple Android API levels within the approved support range.
- Multiple manufacturers and Wi-Fi chipsets.
- Wi-Fi Direct discovery and connection.
- Local-only hotspot behavior where supported.
- Existing local Wi-Fi mode if included.
- Permission granted, denied, revoked, and restricted cases.
- Screen off, rotation, process recreation, app backgrounding, and notification behavior.
- Network loss, peer disappearance, reconnect, cancellation, and repeated retry.
- Low storage, read-only destination, SAF provider failure, and file-name collision.
- Large files, thousands of small files, Unicode names, long names, and unusual file types.
- Malformed protocol frames and hostile peer behavior.
- APK, split APK, ABI mismatch, version mismatch, signature mismatch, and installation cancellation.

### Required correctness invariants

1. No transfer is marked successful before destination integrity verification.
2. A failed or cancelled transfer never silently replaces a completed user file.
3. Resume never skips bytes or appends duplicate bytes.
4. An untrusted peer cannot access arbitrary local files.
5. Incoming files are never executed automatically.
6. A package is never installed without explicit user intent and the platform installation flow.
7. Failure and cancellation release sockets, descriptors, locks, jobs, and temporary resources.
8. Resource use stays bounded as file size and item count increase.

### Performance reporting

Measure on physical devices and record:

- device model and Android build
- Wi-Fi chipset/band and connection mode
- distance/interference conditions
- source and destination storage characteristics
- file size and file count
- observed throughput distribution, not just peak
- CPU, memory, battery, and thermal behavior
- reconnect and resume outcomes

Do not promise a fixed MB/s rate across all devices. Establish product thresholds only after reproducible baseline measurements.

## 14. Definition of Done and release gates

A feature is not done because its UI renders or its happy path works once.

For any transfer-related release, require evidence that:

- Peer discovery and pairing work on the supported device matrix.
- The core offline flow works without internet access.
- Files are streamed with bounded memory use.
- Final hashes match for successful transfers.
- Disconnect/reconnect and resume behavior are deterministic.
- No partial artifact is exposed as a completed file.
- Permission denial and storage failures are handled clearly.
- APK base/split handling and PackageInstaller behavior are tested on compatible targets.
- Security tests for malformed frames, spoofed peers, and unauthorized access pass.
- No unresolved critical/high security defect remains.
- Privacy and distribution-policy requirements have been reviewed.
- Release notes accurately describe supported and unsupported cases.

A test result must include command/device, environment, observed output, and pass/fail status. Never replace test evidence with an assertion.

## 15. Collaboration and change-control rules

When working in this repository:

1. Read this skill and the relevant project documents before proposing or changing architecture.
2. Inspect existing files before editing; do not overwrite work based on assumptions.
3. Separate confirmed decisions from proposals and open questions.
4. For consequential architecture changes, create or update an ADR before implementation.
5. Keep changes focused and reviewable. Avoid unrelated refactors.
6. Preserve working behavior and public contracts unless the user approved a breaking change.
7. Add tests with behavior changes; update documentation when behavior or constraints change.
8. Explain any platform limitation instead of hiding it behind a workaround.
9. Do not fabricate API names, permissions, SDK requirements, or policy allowances. Verify current official Android documentation at implementation time.
10. Report exactly what changed, what was tested, what was not tested, and any known risks.

### Source-of-truth order

For project decisions, use this order:

1. Explicit, most recent product-owner decision.
2. Approved ADRs and requirements documents in this repository.
3. Current official Android developer documentation and distribution policies.
4. Existing tested implementation and reproducible test evidence.
5. Engineering proposals, clearly labeled as proposals.

If sources conflict, do not silently choose one. Explain the conflict and request or record a decision.

## 16. Initial open decisions

Do not resolve these by guessing. Capture them in the requirements/ADR process before implementation:

- Minimum supported Android version.
- Google Play only, other app stores, direct distribution, or multiple channels.
- Whether app-sharing requires broad installed-package visibility and how that will be distributed compliantly.
- Whether group/multi-recipient transfer is required for the first release or a later version.
- Whether transfer history is retained indefinitely, bounded by time, or user-managed.
- Exact connection priority and fallback behavior across supported devices.
- Whether the first release includes existing-Wi-Fi transfer in addition to Wi-Fi Direct and local-only hotspot.
- Product name, visual identity, localization, and app package/application ID.
- Quantified performance and reliability targets after device-lab baselines exist.

**Default rule:** preserve the confirmed Android-only, offline-first, security-and-reliability-first scope. Do not expand the product with unrelated media, social, cloud, advertising, or account features unless the product owner explicitly approves the expansion.


## 17. Confirmed product-owner decisions (2026-11-01)

The following decisions are confirmed and must be treated as requirements unless the product owner explicitly changes them:

- **Offline/serverless core:** no central backend, cloud database, account, relay, or internet dependency is required for discovery, pairing, or local file transfer. Transfer payloads remain on the participating devices. Android local sockets may still require the manifest INTERNET permission; this does not imply internet connectivity.
- **Primary transport:** Wi-Fi Direct is the preferred primary connection technology. Evaluate device capability and OS behavior; do not claim universal support. Any fallback transport must be documented and approved.
- **Transfer modes:** support direct one-to-one transfer and one-sender-to-multiple-receivers group transfer. Each receiver has independent acceptance, progress, cancellation, integrity verification, and recovery state. Simultaneous multi-sender group exchange is not part of the confirmed first group-transfer requirement.
- **Performance target:** deliver competitive, SHAREit-class real-world performance where hardware and radio conditions permit. Never promise identical throughput on every device. Establish evidence-based benchmarks against SHAREit on the same phones, files, distance, radio conditions, and test procedure.
- **Frontend quality:** premium, bespoke visual design and polished, purposeful animations are core product requirements, not late-stage decoration. Use a maintained design system, reusable components, feature-isolated UI, explicit UI states, and accessibility/performance checks. Animations must not compromise responsiveness, battery, or transfer status accuracy.
- **Maintainability:** modular, cleanly separated architecture is mandatory. Avoid oversized files/classes and unrelated responsibilities. Gradle module boundaries should reflect stable architectural ownership; do not create a module for every trivial class.
- **Checkpoint discipline:** maintain repository documentation for project status, architecture decisions, test evidence, and milestone checkpoints. A checkpoint may be marked PASSED only with reproducible test evidence, relevant device/environment details, and the corresponding commit SHA. Use IN_PROGRESS, BLOCKED, and PASSED states. Preserve known-good commits/tags for major verified milestones.
- **Signing and build stage:** current work is Debug-only and uses the standard Android debug signing configuration. Do not create or require a production keystore or release secrets during this phase. Never commit any keystore or signing secret.
- **Distribution intent:** plan for both Google Play distribution and direct APK distribution. Treat their policy, package visibility, installer, signing, and release workflows as separate concerns requiring review.
- **Future release signing:** when release preparation is explicitly authorized, create a production signing key, keep protected backups offline, and expose secrets only to tightly scoped trusted release workflows via GitHub Secrets/Environment Secrets. Review whether Play App Signing is appropriate.

These decisions define product scope and planning constraints; they do not authorize implementation by themselves. Do not create app source, Gradle scaffolding, UI screens, or executable features until the product owner explicitly starts implementation.
