# PEYVAND transfer pipeline audit — 2026-11-08

- **Status:** `IN_PROGRESS`
- **Audited branch:** `feature/cp-004-multi-item-transfer`
- **Audited code SHA:** `0815395cc1ff3b9259131b9eaf96363692c18078`
- **Product:** PEYVAND (پیوند)
- **Scope:** one-file v1 transfer, multi-item v2 transfer, Android content URI access, SAF publication, MIME metadata, installed-app/APK scope.
- **Main branch:** not modified.
- **PR #3:** remains open, Draft and unmerged.

## Executive findings

1. The owner installed the earlier CI #333 APK on two physical phones and reported that a two-item batch containing one audio file and one image completed the audio item but failed the image item. This is a real device-level failure report. It is not overridden by a green JVM/CI run.
2. The exact device-side exception and failing stage were not captured in the report. Therefore the root cause of that particular image failure is **not proven**.
3. The code line after CI #333 contains targeted fixes for provider MIME normalization, incomplete document-provider metadata, concrete MIME validation, batch receipt compatibility, and per-item failure-code propagation. These are relevant fixes, but they do not prove the previously failing photo now transfers on the owner's devices.
4. PDF and arbitrary user-selected documents use the Android Storage Access Framework through `OpenMultipleDocuments()` and `content://` URIs. This is the correct general-file path; it does not guarantee that every cloud/document provider is online, readable, writable, or consistent.
5. Installed applications are **not yet transferable**. The app catalog can enumerate launcher-visible apps, but the transfer storage adapter currently opens only `ShareSource.ContentUri`; installed-package sources are rejected by the coordinator. The UI-side guard added in commit `545b852` prevents selecting an unsupported package source. This is an intentional safety guard, not completed app sharing.
6. APK file transfer and installed-app extraction are different capabilities. A user-selected `.apk` document can travel as an ordinary file; extracting an installed app requires a package-aware source, including its base APK and required split APKs. Installation is a separate Android PackageInstaller flow and requires user/system approval.
7. No Android instrumentation test currently exercises the real ContentResolver/MediaStore/DocumentProvider source and SAF destination adapter end-to-end. Existing transfer tests primarily exercise protocol logic using loopback sockets and in-memory staging implementations. This is a material coverage gap for the reported failure.

## Evidence reviewed

### Code and architecture

- `AndroidShareCatalogRepository`: MediaStore-backed audio/photo/video catalogs; generic files are resolved from user-selected document URIs; application catalog is based on launcher activities.
- `AndroidTransferFileStorageRepository.openSource`: accepts `ShareSource.ContentUri` only. It reads source data through `ContentResolver.openInputStream(uri)`.
- `AndroidTransferFileStorageRepository.readMetadata`: combines fallback item metadata, `OpenableColumns.DISPLAY_NAME/SIZE`, provider MIME, fallback MIME, and extension MIME.
- `TransferMimeTypeResolver`: rejects wildcard/generic metadata as a concrete media type and falls back to a specific type or `application/octet-stream`.
- `SingleFileTransferCoordinator`: one selected item uses the v1 path; multiple selected items use the v2 batch path.
- `BatchTransferSenderProtocol`: source-open failures and source-read failures are item-scoped; source read failures are surfaced as `SOURCE_READ_FAILED`. Terminal batch receipt is verified after all item outcomes.
- `AndroidTransferFileStorageRepository.publishVerifiedToTree`: writes to a newly created SAF document, verifies byte count and SHA-256 by read-back, and attempts cleanup on failure. Document providers may differ in create/write/read/delete semantics.
- `AndroidManifest.xml`: declares media permissions and a launcher-intent package visibility query; it does not request `QUERY_ALL_PACKAGES`.

### Relevant commits after the earlier CI #333 APK

- `b745b222` — normalize provider MIME metadata for transfers.
- `8f0caa83` — avoid duplicate provider metadata preflight.
- `545b8524` — prevent selecting unsupported installed packages.
- `3adb173f`, `714aa098`, `1b9370d4`, `de7ec464` — propagate and display per-item failure reasons.
- `092a47be` — tolerate incomplete document-provider metadata.
- `4c473bd6` — correct concrete MIME validation pattern.
- `0815395c` — repair malformed test declaration and assert a concrete item failure code.

### CI

- CI #345, run `42506900809`, failed because a malformed Kotlin/JUnit test declaration combined two backtick-named test declarations into one invalid test method. The failure was in test discovery/initialization, not a production transfer test failure.
- CI #346, run `42507588353`, on exact SHA `0815395cc1ff3b9259131b9eaf96363692c18078`, completed successfully:
  - Build, unit tests, lint and Debug APK build: SUCCESS.
  - Android Keystore mTLS loopback API 26: SUCCESS.
  - Android Keystore mTLS loopback API 36: SUCCESS.
- A successful CI run does not test OEM-specific MediaStore/SAF behavior on the owner's physical devices.

## Risk and gap register

| ID | Finding | Severity | Current disposition |
|---|---|---:|---|
| AUD-01 | Image item failed on a real two-phone batch; exact exception/stage not captured | Critical | Open; repeat with per-item diagnostics and real MediaStore URI |
| AUD-02 | No end-to-end Android storage-provider instrumentation coverage | High | Open; add Android tests for MediaStore and SAF source/destination |
| AUD-03 | Installed app source is not supported by transfer storage/protocol | High | Explicitly excluded from CP-004; planned CP-005 |
| AUD-04 | Launcher-intent query is not a complete inventory of every installed package | High | Must define package visibility and distribution policy before CP-005 implementation |
| AUD-05 | Generic files depend on provider-specific access and semantics | Medium | Use SAF; test local and provider-backed URIs, handle denial/offline/unknown size |
| AUD-06 | A received APK is only a package artifact, not app-private data or a guaranteed install | High | UX and installation flow must state limitations and use PackageInstaller |

## Required validation before CP-004 can pass

1. Re-test the latest exact Debug APK on both owner devices with the same audio+image pair that failed on CI #333.
2. Repeat one-file transfer for image, audio, PDF, arbitrary binary, zero-byte, Unicode filename, and a large file.
3. Repeat multi-file batches with mixed MIME order in both directions; verify each file's exact SHA-256 and byte count at the destination.
4. Exercise source URI revoked/unavailable, metadata size null/stale, source read error, receiver stage failure, SAF create/write/read-back/delete failure, cancellation, and next-item continuation.
5. Capture only sanitized diagnostics: transfer/session correlation ID, item index, stage, failure enum, exception class, API level and provider authority class. Do not log file contents, full URIs, user paths, or secrets.
6. Do not mark any device test PASSED without device model, Android/API, exact APK SHA-256, sender/receiver roles, test file set, repetitions, and observed destination hashes.

## Package-sharing boundary

- A standalone APK selected through SAF is an ordinary file and can use the generic file-transfer path once verified.
- Sharing an installed app requires a package-aware extraction source that handles base and split APKs as a consistent set, validates package/version/signing-certificate consistency, and streams each artifact through the same bounded, authenticated, hash-verified transfer engine.
- The receiving side must validate the package set and hand installation to Android's PackageInstaller. Do not silently install, bypass user consent, or imply that app-private data is included.
- Android package visibility is policy-constrained. Do not assume that the app can enumerate every installed package. Prefer narrow visibility; assess `QUERY_ALL_PACKAGES` separately for Google Play eligibility and direct APK distribution.

## References

- Android Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
- Android package visibility: https://developer.android.com/training/package-visibility
- Declaring package visibility and QUERY_ALL_PACKAGES: https://developer.android.com/training/package-visibility/declaring
- PackageInstaller: https://developer.android.com/reference/android/content/pm/PackageInstaller
- PackageInstaller.Session and split APK requirements: https://developer.android.com/reference/android/content/pm/PackageInstaller.Session
- PackageManager archive inspection: https://developer.android.com/reference/android/content/pm/PackageManager#getPackageArchiveInfo(java.lang.String,int)
