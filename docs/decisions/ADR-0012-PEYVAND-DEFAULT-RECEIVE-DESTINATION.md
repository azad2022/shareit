# ADR-0012 — PEYVAND managed public receive destinations

- Status: Accepted for implementation; CI and physical-device validation are mandatory before CP-004 acceptance.
- Date: 2026-11-08
- Scope: Public destination policy for received files in CP-003/CP-004. Does not change the authenticated transfer protocol, one-file v1 or batch v3 consent and integrity contracts.

## Context

The previous receive flow asked the user to choose an output document for a single file and one SAF tree for a batch. That created different destination providers and behaviors across otherwise identical transfers. It also added an avoidable user step for every session and coupled successful receive publication to provider-specific SAF tree behavior.

Android scoped-storage rules must be respected: a MediaStore collection's relative path must use a directory appropriate to the media type, and Android 11+ restricts selecting some public roots through ACTION_OPEN_DOCUMENT_TREE. A single physical Download/PEYVAND root is therefore not the correct universal publication target when files should remain integrated with Android's image, video and audio collections.

## Decision

### 1. Receiver experience

1. The receiver still explicitly approves metadata disclosure and content reception. This ADR does not weaken or bypass consent.
2. Once the user accepts the incoming file or selected batch items, PEYVAND chooses the destination category automatically. The receiver is not shown a Create Document or Open Document Tree picker for the default path.
3. The receive UI tells the user where files are stored. On API 26–28, the app requests the limited legacy shared-storage write permission only after an explicit receive acceptance. A denial prevents publication and is surfaced as an actionable error; the app never claims that a file was saved when it was not.
4. User-configurable destinations, a SAF export/copy action and destination preferences are separate future product decisions. They are not implicit in this ADR.

### 2. Directory layout

The product uses a PEYVAND subdirectory under the Android standard public root for each media collection:

| Category | Public directory | Android publication collection |
|---|---|---|
| Images | Pictures/PEYVAND/Images/ | MediaStore.Images |
| Videos | Movies/PEYVAND/Videos/ | MediaStore.Video |
| Music | Music/PEYVAND/Audio/Music/ | MediaStore.Audio |
| Voice recordings | Music/PEYVAND/Audio/Voice/ | MediaStore.Audio |
| Other/ambiguous audio | Music/PEYVAND/Audio/Other/ | MediaStore.Audio |
| Documents | Download/PEYVAND/Documents/ | MediaStore.Downloads |
| APK and app-package artifacts | Download/PEYVAND/Apps/ | MediaStore.Downloads |
| Other files | Download/PEYVAND/Other/ | MediaStore.Downloads |

This is one logical PEYVAND destination system, but intentionally not one physical parent directory. Images, videos and audio remain in the Android public roots associated with their MediaStore collection so that gallery, media indexing and other Android applications can discover them consistently. Documents, APK artifacts and generic files use Download. The folder is public and user-manageable; it is not placed inside the app's private or cache directories.

### 3. Classification rules

1. A syntactically valid, concrete MIME type has priority over the filename extension.
2. If the source provider supplies a generic or absent MIME value, a known extension-derived MIME/category may be used as a fallback. Unknown values go to Other.
3. Legacy JPEG aliases are normalized to image/jpeg before they reach the publication adapter.
4. Voice-versus-music classification is not always determinable from an audio file. Only recording-specific MIME types/extensions are placed in Audio/Voice; well-known music extensions may be placed in Audio/Music; ambiguous audio is placed in Audio/Other. Perfect semantic voice/music identification requires an explicit sender-side category signal and is not claimed by this ADR.
5. An APK received as a file is stored under Apps. This is not installed-app extraction, private application data transfer, or a promise that the package can be installed. Installed package extraction and PackageInstaller handling remain in the separate APK/application checkpoint.

### 4. Publication and integrity

1. Data first lands in the existing bounded private staging sink. This directory is private to PEYVAND and is not a public receive destination.
2. The existing transfer protocol verifies the authenticated actual byte count and SHA-256 receipt and seals the stage before publication begins.
3. Android 10+ uses the matching MediaStore collection. A new row is inserted with its canonical RELATIVE_PATH and IS_PENDING=1; PEYVAND streams from the sealed stage using a bounded buffer, flushes/syncs, verifies byte count and SHA-256 while copying, then reopens the destination and verifies the persisted bytes again. Only after both checks pass is IS_PENDING changed to zero. The receiver sends the authenticated verified receipt only after publication returns success.
4. Android 8–9 use the corresponding public shared-storage directory. A random hidden .part file is created exclusively, streamed and synced, verified by byte count/SHA-256, moved to a collision-free final name without replacing an existing file, read back and verified, and then indexed through MediaScannerConnection.
5. On failure or cancellation, PEYVAND attempts to remove the uncommitted MediaStore row or legacy temporary/final file and deletes private staging. Failure to confirm cleanup is surfaced; no incomplete file is reported as successful.
6. Filenames are treated only as display names. Path separators, control characters and unsafe characters are sanitized. Unicode is normalized and length-limited without splitting UTF-8 code points. Name collisions never overwrite user data; a deterministic numbered suffix is used on legacy file paths and the MediaStore provider's non-replacement allocation behavior is retained.
7. Files are streamed with a fixed bounded buffer. No received file is loaded into RAM in full.
8. The maximum accepted item size is conservatively bounded against both private staging and public destination free space because publication temporarily requires both a stage and a second copy. A safety reserve remains in place.

### 5. Permissions and platform policy

1. API 29+ must not request broad shared-storage write access for these app-created MediaStore items. No MANAGE_EXTERNAL_STORAGE or QUERY_ALL_PACKAGES is introduced by this ADR.
2. API 26–28 declare WRITE_EXTERNAL_STORAGE with maxSdkVersion=28 and request it at runtime only when a user accepts a receive that will publish into public shared storage.
3. MediaStore is used for media and public Downloads records on API 29+; direct filesystem paths are not used for public shared-storage writes on API 29+.
4. The app-private no-backup directory remains the staging area only. It is not the final destination, and final public files are not expected to be deleted when PEYVAND is uninstalled.
5. SAF remains appropriate for user-authorized source selection and may remain available for explicit future export/import operations; it is no longer the default receive publication provider.

## Alternatives considered

- One Download/PEYVAND root for every type: rejected for standard media publication because the MediaStore collection's relative path must be compatible with its media type. It also risks poor gallery/media integration.
- One receiver-selected SAF tree: rejected as the default because Android restricts which roots can be selected, document providers vary in create/write/read/delete behavior, and it creates a user step for each receive flow. SAF remains useful for source selection and explicit user-directed operations.
- App-private final destination (filesDir/noBackupFilesDir): rejected because files are hidden from normal file managers and other applications, and app uninstall/private-data lifecycle is inappropriate for user-owned received files.
- Broad all-files permission: rejected because it is unnecessary for the app-created MediaStore items and does not meet least-privilege expectations.

## Consequences and validation

- CP-004 single-file and batch receive paths share one default-publication contract; they no longer ask for a destination URI/tree.
- Unit tests cover MIME/category routing, canonical Android roots, MIME precedence, generic-extension fallback, and conservative audio categorization.
- Android instrumentation tests exercise a verified JPEG through the image collection and a document through Downloads, checking destination path, IS_PENDING=0, byte equality and cleanup on API 26 and API 36 CI emulators.
- Instrumented emulator tests do not prove compatibility with OEM-specific MediaStore implementations. Galaxy A12 (Android 12/API 31) and ASUS X017DA physical tests remain required in both directions.
- CP-004 remains IN_PROGRESS until exact-SHA CI, APK installation and the owner's physical test matrix succeed. No build, emulator or physical result is inferred from source changes alone.

## References

- Android shared media storage: https://developer.android.com/training/data-storage/shared/media
- MediaStore MediaColumns and RELATIVE_PATH: https://developer.android.com/reference/android/provider/MediaStore.MediaColumns
- Storage Access Framework: https://developer.android.com/training/data-storage/shared/documents-files
