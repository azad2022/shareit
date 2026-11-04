# ADR-0007 — Secure one-to-one file transfer over Wi-Fi Direct

- **Status:** Accepted for CP-003 implementation; security and physical-device validation remain mandatory.
- **Date:** 2026-11-04
- **Scope:** First end-to-end, one-to-one transfer of one user-selected file of any format. No fallback transport or full visual redesign.

## Context

CP-002 has a product-owner report that peers now discover one another in several manually tested operating scenarios. The current application still has no peer connection, authenticated session, receiver-consent protocol, socket transport, or file data plane.

The existing sender catalog resolves user-authorized media and document URIs. That is selection/catalog functionality, not transfer. A discovered peer is public, spoofable metadata; its transient P2P address is neither identity nor a trusted endpoint.

CP-003 must establish a secure, complete one-to-one transfer before adding group scheduling, resume, APK package sets, or extensive presentation.

## Decisions

### 1. Scope and transport

1. Wi-Fi Direct is the only transport in CP-003. No QR/local-only-hotspot fallback, infrastructure Wi-Fi fallback, cloud, relay, or public-internet endpoint is added.
2. The sender requests a connection to the selected discovered peer through WifiP2pManager.connect(). The app must observe the P2P connection-state broadcast and then query requestConnectionInfo(); an accepted ActionListener callback is not proof that a group has formed.
3. The group owner hosts the TCP listener; the group client connects to the group-owner address returned by the framework. Either product role may become group owner. The first implementation supports one authenticated peer session at a time.
4. Socket connect, read, bootstrap, and TLS handshake operations have finite timeouts, bounded retries, cancellation, and deterministic cleanup. No alternate transport is silently selected.

### 2. Session authentication and TLS

1. Each connection attempt creates a fresh P-256 ephemeral key pair and a short-lived self-signed X.509 certificate using Android Keystore. The private key is non-exportable. The key alias is session-scoped and is deleted when the session closes; stale aliases are cleaned by a bounded startup policy.
2. Before a file name, MIME type, size, file bytes, or other user content is sent, the peers exchange only bounded bootstrap records containing protocol version, ephemeral public certificate, fresh cryptographically random nonce, and role/session binding fields.
3. Both peers compute a ten-decimal-digit Short Authentication String from a domain-separated SHA-256 hash of the canonical handshake transcript. Canonicalization must be deterministic and independent of which device is the group owner. The transcript includes both certificate fingerprints, both nonces, protocol version, session identifier, and endpoint roles.
4. Both users must compare the same code on the two physical screens and explicitly approve it. A mismatch, rejection, timeout, duplicate/unknown bootstrap field, or failed confirmation closes the session. Limit attempts and do not reveal file metadata before approval.
5. After both local approvals, the same socket is upgraded to mutually authenticated TLS. The trust manager accepts only the exact peer certificate fingerprint exchanged in this session, verifies certificate validity and the certificate's self-signature, and rejects every other certificate. TLS must prove possession of the corresponding private key. Trust-all managers, disabled certificate checks, plaintext file transfer, and downgrade fallback are prohibited.
6. TLS 1.2 is the minimum compatibility protocol; TLS 1.3 is enabled when supported by the selected Android runtime/provider. The implementation must inspect socket-supported protocols rather than assume support from an API level alone.
7. Pairing is limited to this session. Persistent peer trust, accounts, reusable credentials, and permanent device identities are outside CP-003.

### 3. Control and data protocol

1. The protocol is versioned and transport-independent at its domain boundary. Control messages and binary file data are separate frame types.
2. Use bounded length-prefixed frames with explicit version, type, and session/item identifiers. Validate lengths before allocation, reject oversized or truncated frames, reject unsupported versions/types, and validate legal state transitions. Do not deserialize untrusted content into unrestricted polymorphic object graphs.
3. The first manifest carries one random item ID, display name, MIME metadata, exact byte length, and a 32-byte SHA-256 digest. It never contains a sender filesystem path.
4. The authenticated receiver explicitly accepts or rejects the incoming item before the sender transmits file bytes.
5. The data plane streams bounded chunks with an explicit item ID and offset. One item and a bounded in-flight window are used initially. A 64 KiB chunk may be used as an initial measurement point, not as a universal optimum; tune only from reproducible device measurements.
6. Progress is based on bytes actually written and acknowledged. Sender completion requires an authenticated receiver acknowledgement after verification and successful publication; a successful socket write is not completion.
7. Cancellation closes the active stream/socket, releases file descriptors and session keys, removes uncommitted temporary data, and never publishes a partial artifact.

### 4. Arbitrary file formats and Android storage

1. The data plane is format-agnostic: transfer the exact byte sequence without decoding, transcoding, recompressing, or interpreting the file format.
2. Initial source access is through user-authorized content:// URIs using ContentResolver. MIME type, file extension, and category are descriptive metadata, not an allow-list. Unknown MIME types use application/octet-stream.
3. The receiver stages data in app-private temporary storage, verifies exact byte count and SHA-256, and then publishes through a user-authorized Storage Access Framework destination.
4. The remote display name is untrusted metadata. Strip path separators and control characters, bound encoded length, detect destination collisions, and let the receiver choose/own the destination URI. Never concatenate a peer-supplied name into a filesystem path.
5. If a provider cannot safely reopen a source or its size is unknown, stream it and count actual bytes; do not load the complete file into RAM. If staging is needed, perform disk-space checks and keep staging in app-private storage.
6. SAF providers can differ in atomic rename and durability semantics. Do not claim universal atomic publication. Inability to verify the destination must produce a non-success state.

### 5. Permissions and target-SDK policy

1. Add the normal INTERNET manifest permission only for local TCP socket APIs. This does not create a public-internet dependency, backend, account, cloud endpoint, analytics upload, or relay.
2. The current target SDK is 36. Do not add ACCESS_LOCAL_NETWORK while targeting SDK 36. Test Android 16's documented local-network opt-in behavior with the existing permission path.
3. Before changing target SDK to 37, add and test runtime ACCESS_LOCAL_NETWORK permission handling, including denial and revocation. Treat that as a separate compatibility gate.
4. Do not add broad storage access or MANAGE_EXTERNAL_STORAGE for generic document transfer. Use SAF and scoped media access.

### 6. Explicit non-goals for CP-003

- Multiple files per batch, sender-to-many group transfer, and independent per-recipient state.
- Resume after disconnect/process death, durable transfer history, and guarantees for background continuation.
- Installed-application extraction, base/split APK-set handling, signature compatibility checks, and PackageInstaller.
- Local-only hotspot/QR, existing-Wi-Fi fallback, and multi-sender group transfer.
- Final visual redesign, new brand artwork, and signature motion design.

These remain separate follow-on checkpoints. The CP-003 contracts must not prevent those capabilities from being added later.

## Security and failure invariants

- Discovery is never authentication.
- No file metadata or content is sent before both SAS approvals and successful mutually authenticated TLS.
- No incoming bytes are written to a user-visible destination before consent and integrity verification.
- A file is complete only after exact byte-count and SHA-256 verification and successful destination publication/read-back verification where supported.
- Malformed frames, oversized lengths, invalid transitions, certificate/pin mismatch, wrong SAS, storage exhaustion, short reads/writes, timeouts, disconnect, source mutation, and cancellation fail closed.
- Untrusted peer data never becomes a trusted filesystem path, package command, or executable action.
- No received file or APK is opened, installed, or executed automatically.

## Validation gates

- Unit tests: frame boundaries; malformed/truncated frames; oversized lengths; unsupported versions/types; transcript/SAS determinism; canonical ordering; pin mismatch; stream short-read/write; zero-byte files; arbitrary binary payloads; hash mismatch; cancellation; filename sanitation; resource cleanup.
- Integration tests: local socket bootstrap, mutually authenticated TLS, receiver rejection, timeout, disconnect, cancellation, and corrupted payload.
- API coverage: API 26-28 TLS 1.2, TLS 1.3 where provided, Android 12/API 31 Wi-Fi Direct behavior, Android 16 local-network opt-in, and Android 17/target-SDK-37 permission behavior before target increase.
- Physical interoperability: Samsung Galaxy A12 (SM-A125F) and ASUS X017DA; record exact OS/build, APK SHA-256, both group-owner outcomes where controllable, VPN state, and repeated connect/cancel/retry.
- Data tests: zero-byte, small and large files; images, video, audio, PDF, archive, extensionless and unknown-MIME binaries; Unicode/long names; low storage; read denial; provider write failure; destination collision; and source mutation.
- Resource tests: bounded memory for files far larger than the app heap, cleanup of sockets/descriptors/temp artifacts, cancellation latency, and sustained thermal/battery behavior.

## Android references

- Wi-Fi Direct connection and group-owner socket flow: https://developer.android.com/develop/connectivity/wifi/wifi-direct
- WifiP2pManager connection lifecycle: https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager
- Local-network permission and target-SDK policy: https://developer.android.com/privacy-and-security/local-network-permission
- Android TLS protocol availability: https://developer.android.com/reference/javax/net/ssl/SSLSocket
- Android Keystore key generation: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder