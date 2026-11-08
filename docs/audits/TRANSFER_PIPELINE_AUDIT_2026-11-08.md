# PEYVAND transfer pipeline audit — 2026-11-08

- **Status:** `IN_PROGRESS`
- **Audited branch:** `feature/cp-004-multi-item-transfer`
- **Audited code SHA:** `0815395cc1ff3b9259131b9eaf96363692c18078`
- **Product:** PEYVAND (پیوند)
- **Scope:** one-file v1 transfer, multi-item v3 transfer, Android content URI access, SAF publication, MIME metadata, installed-app/APK scope.
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
- `SingleFileTransferCoordinator`: one selected item uses the v1 path; multiple selected items use the v3 batch path.
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


## Owner retest report and implementation follow-up — 2026-11-08

### Observed device scenario

- Owner reports that Galaxy A12 → ASUS multi-file transfer succeeds.
- In the reverse direction, ASUS → Galaxy A12, music and other file types transfer, while a JPG photo fails with the UI message: «ذخیره‌سازی یا بررسی نهایی فایل در پوشه مقصد ناموفق بود».
- This directional observation narrows the fault to an interaction among the ASUS photo source metadata/URI and the Galaxy A12 receiving/publishing path. It does not yet prove which operation failed.

### Newly confirmed code defects and corrections

1. **Failure-stage conflation:** the prior receiver wrapped final partial-stage sync, integrity verification, stage sealing, SAF publication, and the receipt write in one exception handler. Several unrelated failures were therefore reported as `DESTINATION_FAILED`. The receiver now classifies stage writes/syncs, SHA-256/size verification, stage finalization and SAF publication independently. A receipt-write failure after successful publication is fatal to the session and is no longer represented as a destination failure.
2. **Nullable size metadata:** both MediaStore-backed media and generic SAF document catalog reads used `Cursor.getLong()` without checking `Cursor.isNull()`. Android's cursor contract maps a SQL NULL numeric field to zero when read through `getLong()`; this can turn an unknown source size into a false zero-byte declaration. The adapter now preserves NULL/missing/negative sizes as unknown and retains a real zero-byte file as zero. A pure unit-tested reader covers missing column, NULL, zero, negative and positive values.
3. **Legacy JPEG MIME aliases:** the MIME resolver previously accepted syntactically valid `image/jpg`, `image/pjpeg` or `image/x-jpeg` values as authoritative provider MIME. They are now canonicalized to `image/jpeg` before the manifest and SAF destination creation. JVM tests cover the alias cases.
4. **Wire-safe diagnostics:** the batch protocol was advanced to version 3 for explicit `INTEGRITY_VERIFICATION_FAILED` and `STAGING_FINALIZATION_FAILED` wire codes. Version-2 batch peers fail closed; version-1 single-file transfer remains unchanged. This is an intentional batch-protocol compatibility break, not a silent fallback.

### Root-cause status

- The code defects above are confirmed and corrected in source.
- The exact cause of the owner's ASUS JPG failure remains **unconfirmed** until the latest exact-head APK is tested on the same two physical devices. In particular, we have not yet observed whether the ASUS provider reported a legacy MIME alias, NULL size, or whether the Samsung destination DocumentProvider rejected/failed a specific create/write/read-back operation.
- No Android instrumentation test yet exercises the real ASUS MediaStore source URI and Galaxy A12 SAF destination provider as one end-to-end path. JVM tests validate the corrected rules and protocol behavior but cannot substitute for that OEM-specific test.
- Do not mark AUD-01 or CP-004 as `PASSED` solely from CI. Use the new per-item failure category to decide whether the next device result is an integrity/stage failure or a SAF publication failure; capture only sanitized metadata (failure code/stage, API, provider authority class), never a full URI or personal path.


## Storage destination architecture follow-up — 2026-11-08

### Decision

- ADR-0012 replaces the receiver-selected SAF destination tree in the default receive path with one app-managed PEYVAND destination policy.
- Media files use their canonical shared-storage roots and matching MediaStore collections: Pictures/PEYVAND/Images, Movies/PEYVAND/Videos and Music/PEYVAND/Audio/{Music,Voice,Other}. General files use Download/PEYVAND/{Documents,Apps,Other} through MediaStore.Downloads.
- This deliberately implements one logical PEYVAND destination system, not one physical top-level directory. Android MediaStore requires RELATIVE_PATH to be compatible with the media collection. Placing all files in Download would not be an equivalent gallery-integrated media implementation.
- Android 10+ items remain IS_PENDING until destination write and independent read-back length/SHA-256 verification succeed. API 26–28 use a legacy public shared-storage path with WRITE_EXTERNAL_STORAGE capped at API 28 and requested only after receiver consent. No MANAGE_EXTERNAL_STORAGE permission is introduced.
- Single-file and batch coordinators now call the same storage abstraction. Destination URI/tree picker dependencies are removed from the normal receive path; SAF remains appropriate for user-selected source URIs.

### Classification and integrity constraints

- A concrete MIME type wins over a conflicting file extension. Extensions are used only for generic/absent MIME metadata. Legacy JPEG aliases are already canonicalized to image/jpeg.
- Voice/music separation cannot be inferred reliably for all codecs. Only recording-specific types go to Voice; obvious music formats go to Music; ambiguous audio goes to Audio/Other.
- Files remain in bounded private staging until authenticated size/SHA-256 validation and stage sealing complete. Publication streams with a fixed buffer, verifies copied and read-back bytes, and never overwrites a same-name user file.
- Public publication temporarily duplicates a file while private staging still exists. The advertised receive ceiling is conservatively limited by available private and public storage with a safety factor/reserve.
- APK file storage does not imply installed-app extraction, split APK collection or installation. Those remain CP-005.

### Validation status

- JVM category tests and Android instrumentation tests are added. The instrumentation tests cover a JPEG MediaStore publication and a PDF Downloads publication, including relative path, IS_PENDING release, read-back bytes and cleanup on API 26 and API 36 CI emulators.
- The instrumentation tests are not yet executed on this new exact source tree. CI must prove compilation, tests, lint, Debug APK build and both emulator API jobs.
- A green emulator is not a substitute for Galaxy A12 (SM-A125F/API 31) and ASUS X017DA physical tests. The previously reported ASUS → A12 JPG failure remains an open interoperability issue until the new exact APK is installed and tested.
- No root cause is claimed for the earlier JPG failure. The storage architecture reduces dependence on the receiver's SAF DocumentProvider but does not prove that the ASUS source URI/MIME/metadata path is correct.
