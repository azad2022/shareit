---
name: peyvand-android-engineering
description: Engineering policy and product-specific guidance for the PEYVAND Android project (repository azad2022/shareit): offline peer-to-peer connectivity, secure file transfer, APK and split-APK sharing, Android storage/package APIs, reliability, privacy, testing, and release quality. Load whenever planning, designing, implementing, reviewing, testing, or documenting this repository.
---

# PEYVAND (پیوند) Android — Engineering Skill

## 1. Mission and repository boundary

Act as the senior Android systems engineer for this repository. The product is PEYVAND (پیوند), an independently engineered, commercially reliable Android application for direct wireless transfer of user-selected files and eligible Android application packages between nearby devices.

PEYVAND competes in the same product category as SHAREit and may use blue-and-white as its broad color direction, but it must have its own brand expression. Do not copy proprietary source code, artwork, logos, trademarks, exact screen layouts, signature animations, text, or other protected assets from SHAREit or any other product. SHAREit may be used as a benchmark for measured transfer performance and common user expectations, not as a source of copied design assets.

### Current repository state and implementation authorization

- The master product specifications are maintained on `main`. CP-004 remains isolated on `feature/cp-004-multi-item-transfer`; PR #3 remains open as Draft and must not be modified or merged by this chat checkpoint.
- CP-CHAT-001 is separately authorized by the product owner on 2026-11-13 and is implemented on `feature/cp-chat-001-local-persistence`, based on code SHA `cf80930bd557a3a6feae0d6d8ada0e200f2fb6c8`.
- Continue this task only on `feature/cp-chat-001-local-persistence`. Do not switch branches or fold CP-CHAT changes into CP-004/PR #3. Read the latest skill and product requirements from `main`, then inspect the exact active branch files before each change.
- CP-CHAT-001 scope is encrypted local text-message persistence, Room storage, an atomic durable outbox, bounded pending-message count, Android backup/device-transfer exclusion, and automated tests. It does not include chat Compose screens, network sync, persistent peer identity, group chat, read receipts, voice/video, message deletion UI, app export, cloud backup, or remote recovery.
- The product owner's no-server boundary is absolute: no central server, backend, hosted database, account service, push service, cloud message store, relay, or operator-held chat data. Chat data remains only in the participating phones' private app data.
- The current mTLS pairing uses a session-scoped ephemeral certificate and exposes an exclusively claimed TLS channel to the transfer coordinator. Never use that ephemeral certificate, discovery name, IP/MAC address, or socket endpoint as a persistent chat identity. A separately reviewed stable-identity and channel-multiplexing milestone is required before synchronization.
- The 2026-11-01 CP-001/CP-002 authorization and 2026-11-04 CP-003 authorization remain valid for their existing scopes; they do not replace the separate CP-CHAT-001 scope above.
- Never claim a feature works, a test passed, a build succeeded, or a device is supported without direct evidence from repository state or test output.

The product owner's explicit, most recent decisions take precedence over stale planning-only wording in older documentation. Update the relevant checkpoint or ADR when a decision or verified implementation state changes.
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
10. Maintain local one-to-one text conversations with durable on-device history and an outgoing message outbox.
11. Synchronize messages only after a later direct connection to the same authenticated, previously paired peer; no server or relay delivery is allowed.

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

Use native Android development. Kotlin is a confirmed, mandatory implementation language for new application logic. Jetpack Compose is the strongly recommended UI toolkit because it supports state-driven interfaces and the required motion design; confirm the toolkit and its version strategy in an ADR before scaffolding. A proposal to replace Kotlin requires explicit product-owner approval. Avoid adding a cross-platform framework merely for abstraction.

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
- ChatRepository
- LocalChatStore
- ChatOutbox
- ChatSyncCoordinator
- PeerIdentityRepository

For CP-CHAT-001, ChatRepository/LocalChatStore/ChatOutbox are implemented as a persistence slice. ChatSyncCoordinator and stable PeerIdentityRepository remain future responsibilities. These are logical boundaries, not a mandate to create one Gradle module per class.

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

### DNS-SD request and callback semantics

- Treat DnsSdServiceResponseListener and DnsSdTxtRecordListener as separate callbacks. Android explicitly documents TXT records as optional; a missing TXT callback must not suppress a service-discovered candidate.
- Understand the request factory semantics before selecting an overload: WifiP2pDnsSdServiceRequest.newInstance() searches all Bonjour services; newInstance(serviceType) searches services of that type; newInstance(instanceName, serviceType) requests TXT data for that specific Bonjour service. Do not assume the service-type-only request also requests each instance's TXT record.
- When TXT metadata is needed, explicitly design and test the request strategy (broad Bonjour discovery followed by callback filtering, or bounded per-instance TXT requests). Account for request registration, removal, duplicate callbacks, limits, timeout, and asynchronous ordering. Do not create unbounded per-peer service requests.
- A valid service callback may create a discovery candidate after strict instance-name and registration-type validation. It is not proof that the remote app is genuine or compatible. A missing TXT record means metadata is unavailable, not that the candidate does not exist.
- If TXT is received, validate its full domain and schema independently, and associate it with the service candidate using the transient source-device address plus normalized instance name. TXT may enrich or classify the candidate; it must never be treated as authentication.
- Keep the UI and domain model distinctions explicit: candidate discovered, metadata unavailable, metadata compatible/incompatible, connecting, and authenticated are different states. Never label a candidate as authenticated or verified solely because a public DNS-SD name/TXT marker matches.
- Service and TXT callbacks can be delivered independently and may be delayed or duplicated. Correlation logic must be order-independent, bounded, generation-scoped, and safe when either callback never arrives.
- Diagnostics must distinguish request setup/action success and failure from callback counts. Capture enough transient callback context to distinguish events and sources, but redact/pseudonymize P2P addresses and avoid logging user-chosen device names, secrets, file metadata, or persistent identifiers.
- Make advertiser and browser roles explicit where the product flow requires them. Test sender-searching/receiver-advertising and the reverse as separate cases. Do not impose reciprocal list visibility as an acceptance condition unless the approved UX requires both devices to show each other.

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

- Use Kotlin with explicit nullability and typed domain models. Kotlin is mandatory for new Android product code; do not introduce Java for new features without an approved interoperability reason.
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
- Keep chat history and outgoing messages only in the private local application storage of participating phones. Never add server-side chat retention, remote backup, or relay.
- Encrypt message bodies at rest using a reviewed AEAD scheme and a non-exportable Android Keystore key. Bind ciphertext to the message/conversation/peer metadata with authenticated associated data.
- Persist each outgoing message and its outbox row in the same Room transaction before reporting local save. A socket write is never delivery confirmation.
- If chat crypto metadata exists but its Keystore key is missing or invalidated, fail closed; never silently create a replacement and expose an empty history.
- Exclude app data from cloud backup and Android device-transfer paths for the initial chat release; validate effective behavior on the supported Android/OEM matrix.
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
- Chat message persistence and outgoing outbox survival across process/database reopen.
- AES-GCM ciphertext integrity, wrong-peer isolation, Android Keystore key-loss fail-closed behavior, and queue quota.
- Chat synchronization after direct reconnect, peer identity mismatch, replay/duplicate frames, cursor safety, and dual-device ordering once that milestone is implemented.
- Android cloud backup and D2D migration exclusion on the supported matrix.
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
- Exact Kurdish locale/script (Sorani/Arabic script, Kurmanji/Latin script, or another explicitly approved variant), and app package/application ID. Product name and blue-white brand direction are confirmed.
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


## 18. Confirmed PEYVAND brand, language, and motion requirements (2026-11-01)

These requirements are confirmed by the product owner. They supersede earlier open-decision wording about the product name, broad visual direction, primary implementation language, and target languages. They do not authorize implementation by themselves.

### Product identity and repository identity

- Product/brand name: **PEYVAND**; Persian display form: **پیوند**.
- The existing GitHub repository remains `azad2022/shareit`. Repository naming does not redefine the customer-facing brand. Do not rename the repository unless separately approved.
- Use PEYVAND in application-facing copy, package metadata proposals, design documents, and product references. SHAREit is a category competitor and performance/expectation benchmark, not the product name.
- Build an original visual identity. The product owner selected a blue-and-white visual direction similar to SHAREit's broad palette. Use blue and white as the dominant brand colors, but define PEYVAND-specific color tokens, typography, shapes, iconography, illustrations, screen compositions, and motion. Never copy SHAREit's logo, artwork, exact screens, signature assets, text, or proprietary implementation.
- Do not invent exact color HEX values before a design-system proposal. Select accessible primary/secondary blues, white and neutral surfaces, readable foreground colors, and semantic status colors; validate contrast and states. Blue must not be used for every status if that makes success, warning, or error ambiguous.

### Mandatory language and UI stack

- Kotlin is mandatory for new Android application code.
- Jetpack Compose is the default recommended UI toolkit for the custom interface and motion system. Record the chosen Compose/AndroidX versions and architecture in an ADR. Do not treat Kotlin as optional or replace it with Java or a cross-platform framework without explicit approval.
- Keep UI declarative and state-driven. Compose UI must render state; it must not own sockets, file streaming, cryptographic rules, package extraction, or transfer orchestration.

### Local chat persistence requirements

- Use Room in `:core:data` for local conversations, messages, outbox, and crypto metadata; keep domain contracts Android-free.
- Encrypt chat message bodies with AES-GCM and a non-exportable Android Keystore key. Hardware-backed or StrongBox protection must not be assumed without querying actual device capability.
- Include the peer fingerprint, conversation/message IDs, direction, sender sequence, and creation timestamp in authenticated associated data.
- Commit message row, outbox row, and next local sequence atomically. Return success to the caller only after the Room transaction commits.
- Bound each message to 4 KiB UTF-8 and each pending outbox to the current engineering ceiling of 1,024 messages per peer. Do not silently prune history or unsent messages.
- The current CP-CHAT-001 implementation does not send or receive network messages. Future sync must reuse direct Wi-Fi Direct, authenticate stable peer identity, use an idempotent protocol, and acknowledge only after durable receiver-side commit.
- The current secure-session certificate is ephemeral and the current TLS channel is exclusively claimed. Do not attach chat to it until persistent identity and a reviewed multiplexing/ownership design exist.

### Localization scope and rollout

The target app languages are exactly these seven language groups unless the product owner changes the list: Persian, English, Arabic, Turkish, Kurdish, German, and French.

- Persian is the initial development, debugging, and first-complete-content language. The project must be localization-ready from the first implementation checkpoint; do not postpone the architecture until the end.
- Establish stable string resource keys, plural resources, locale-aware formatting, fallback behavior, and a translation review process before feature UI expands. No user-facing text may be hard-coded in Kotlin or Compose source.
- Keep UI copy separate from domain values and protocol messages. Never translate package names, file names, MIME types, raw paths, IP addresses, cryptographic identifiers, or protocol fields. Display these through correct bidirectional text isolation where necessary.
- The first app launch must show a clear language-selection step before the main app experience. The user selects a language once; persist the selection and do not show the language chooser on every launch. A deliberate language-change action in Settings is recommended and must not erase transfer data. Do not override a user's saved choice silently when the device language changes.
- Show language names in their accepted native/autonym forms as well as a clear accessible label where useful. Selection must be keyboard/screen-reader accessible and visually unambiguous.
- Prepare the localization architecture and resource structure from the start. Persian content is the initial source of truth; other language bundles may be translated and enabled in a staged localization milestone. Never expose raw resource keys, machine-looking placeholders, or visibly half-translated screens. A language must be marked available only when its required UI coverage has passed review.
- Persian and Arabic require full RTL support. Kurdish direction depends on the approved script/locale. English, Turkish, German, and French are LTR. Do not implement RTL by merely right-aligning text: use direction-aware layout, navigation, icon mirroring rules, back/forward semantics, and tested Compose layout direction.
- Test mixed-direction content: Persian/Arabic UI containing Latin file names, APK package identifiers, URLs, IP addresses, version strings, numbers, and dates. Use Unicode bidi isolation and safe formatting so punctuation and numbers do not reorder incorrectly.
- Use locale-aware pluralization, date/time/number formatting, Unicode normalization where appropriate, font fallback, and correct line breaking. Do not concatenate translated fragments to form sentences. Avoid clipping in longer German/French strings and font glyph gaps in Persian/Arabic/Kurdish.
- Kurdish is a confirmed target language, but the exact variant and writing system are not yet confirmed. Before final translation and release, ask the product owner to choose the intended variant, such as Sorani (Arabic-based script, RTL) or Kurmanji (commonly Latin script, LTR). Do not silently treat Kurdish as one unambiguous locale.
- The full translation completion phase may occur after core functionality, but localization architecture, string extraction, RTL-safe component design, and language persistence must exist from the beginning. Do not create a costly retrofit.

### Blue-and-white design system

- Blue and white are the dominant brand colors. The finished interface must feel premium, modern, coherent, and recognizably PEYVAND rather than a clone of SHAREit.
- Build a centralized design system before multiplying screens: semantic color tokens, typography scale, spacing, elevation, shape language, icon rules, component variants, focus/pressed/disabled/error states, and motion tokens.
- Use white/light surfaces for clarity and controlled blue accents for primary actions, navigation, focus, and active states. Include neutral surfaces and semantic status colors. Check contrast in normal, disabled, pressed, dark/light if later supported, and high text-scale conditions.
- Do not hard-code colors in individual screens. Do not use color alone to communicate transfer state; combine text, iconography, and accessible semantics.
- Use original vector/icon/illustration assets and consistent geometry. No trademark confusion or reused SHAREit visual assets.
- Every screen must handle loading, empty, permission-denied, unavailable, disconnected, connecting, waiting-for-consent, active, paused, recovering, completed, and failed states where relevant.

### Purposeful feature animation and motion design

Animations are a first-class frontend requirement, but only when they communicate a real action, state, relationship, or result. Do not add motion merely to make a screen look busy.

- Define a motion system: durations, easing curves, spatial distances, interruption rules, reduced-motion alternatives, and rules for simultaneous animations. Use Compose-native animation APIs by default; add an animation dependency only after size, licensing, accessibility, maintenance, and performance review.
- Every animation specification must state: user purpose, triggering state/event, source of truth, start/end states, cancellation/interruption behavior, reduced-motion behavior, and performance budget.
- Discovery may use a restrained radar/ripple or nearby-device reveal to communicate scanning and found peers. The animation must stop or change state when discovery stops; it may not imply that a peer exists when none was found.
- Connection and pairing may visually communicate searching, handshaking, authentication, consent waiting, success, timeout, and failure. Never animate a successful connection before authentication actually succeeds.
- File transfer may animate item movement or a transfer path, but the progress value, speed, remaining time, and completion state must be based on actual measured transfer data. Never fabricate or artificially smooth progress in a way that conceals a stall.
- Group transfer must show each receiver as a distinct participant with independently sourced consent, progress, pause/cancel, retry, verification, and completion states. Do not show one global animation that suggests every receiver completed when only one did.
- Integrity verification may use a distinct checking/verified state. Do not display a success flourish until final destination integrity verification passes.
- Pause, resume, cancellation, failure, recovery, and package-transfer/install handoff must have clear, semantically correct transitions. Installation remains separate and user-approved.
- Use transitions for navigation and state changes only when they preserve orientation and do not hide controls or important status. Keep critical buttons stable and predictable.
- Respect Android accessibility settings and reduced-motion preferences. Provide a static or low-motion equivalent; no critical information may be conveyed solely by animation.
- Avoid infinite decorative loops, parallax for its own sake, large particle effects, CPU-heavy blur, and unnecessary recomposition. Stop animation when off-screen or irrelevant; test low-end supported hardware, 60/120 Hz devices, battery use, thermal effects, and accessibility.
- Motion must never block user input, delay cancellation, make progress less legible, break screen-reader semantics, or create misleading expectations. Transfer correctness and accurate state always outrank visual flourish.

### Localization and animation acceptance gates

Before a language/UI milestone is marked PASSED, verify: all visible strings are externalized; no missing-key markers appear; Persian and Arabic RTL layouts work; Kurdish direction matches the chosen script; mixed bidi filenames and identifiers render correctly; font scaling and TalkBack work; long translated strings do not clip; first-run language choice persists; and no launch repeats the chooser. Record the test device/API and screenshots or reproducible evidence.

Before a motion milestone is marked PASSED, verify: the animation is tied to a real state; it reflects actual transfer/connection data; interruptions and lifecycle changes are safe; reduced motion is supported; controls remain usable; frame stability and resource use are acceptable on the supported device matrix; and the animation does not misrepresent transfer completion.

Refer to `docs/PRODUCT_IDENTITY_AND_LOCALIZATION.md` for the expanded product identity, localization, RTL, motion, and acceptance specification. Refer to `docs/PRODUCT_REQUIREMENTS.md` for the current confirmed scope. CP-001/CP-002 implementation was authorized by the product owner on 2026-11-01. The owner separately authorized CP-003 on 2026-11-04; its active branch is `feature/secure-file-transfer`. The current CP-003B substage implements a P2P-interface-bound raw TCP channel under ADR-0008. The owner separately authorized CP-CHAT-001 on 2026-11-13 on `feature/cp-chat-001-local-persistence`, limited to encrypted local Room persistence, durable outbox, Android backup/device-transfer exclusions, and tests. Chat Compose UI, stable peer identity, sync protocol, channel multiplexing and all remote/online services remain outside this authorization. Future scope expansions still require explicit approval.

