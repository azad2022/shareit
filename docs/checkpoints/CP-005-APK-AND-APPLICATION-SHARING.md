# CP-005 — APK and installed-application sharing

- **Status:** `IN_PROGRESS` (planning/architecture only; implementation not started)
- **Branch baseline:** `0815395cc1ff3b9259131b9eaf96363692c18078` on `feature/cp-004-multi-item-transfer`
- **Decision:** Proposed ADR-0011
- **Dependency:** CP-004 generic file transfer must pass its physical-device gates before package sharing is declared complete.

## Objective

Allow users to send a standalone APK as a normal file and, as a separate first-class capability, export eligible installed Android applications as complete package sets (base APK plus required splits), transfer them securely, validate them, and optionally hand installation to Android PackageInstaller with explicit user approval.

## Scope

### A. Standalone APK file

- Select an existing `.apk` using SAF `OpenMultipleDocuments()`.
- Transfer bytes through the same generic file v1/v2 path.
- Verify exact byte count and SHA-256 before publication.
- Keep APK as an ordinary file until the user explicitly chooses an install action.
- Validate APK archive metadata before offering installation.

### B. Installed application export

- Enumerate only packages visible under Android package-visibility rules.
- Resolve package name, version, SDK requirements, signing certificate digest(s), base APK, and all accessible required split APKs.
- Treat one application and its required split set as one logical package item; do not allow individual split selection to produce a broken install set.
- Stream each APK artifact sequentially with bounded memory and independent artifact integrity receipts.
- Reject inaccessible packages, missing base/splits, inconsistent metadata, and packages blocked by platform policy with explicit reasons.

### C. Receive and install

- Stage all package artifacts privately.
- Verify per-artifact hashes and set-level package/version/signing consistency.
- Check SDK, ABI, available storage, and split completeness where determinable.
- Use PackageInstaller sessions. Android controls any required user confirmation and final installation status.
- Never silently install or imply that private app data, accounts, permissions, or cloud assets are transferred.

## Acceptance criteria

1. A user-selected APK file transfers as a generic file with exact SHA-256 and byte count.
2. A PDF and arbitrary document selected through SAF transfer in both one-file and multi-file flows.
3. Installed app export includes exactly one base APK and every required accessible split for the chosen package.
4. Package metadata and signing-certificate digests are consistent across the set.
5. Package artifacts transfer with bounded streaming, cancellation, item failure isolation, and no partial publication.
6. Receiver rejects malformed, missing, duplicate, mixed-version, mixed-package, or signature-inconsistent package sets.
7. Installation is initiated only through PackageInstaller and the OS/user controls confirmation.
8. Tests cover API 26, API 31, API 33+, current target API, ABI differences, split packages, low storage, inaccessible source APK, and OEM package visibility.
9. Direct APK and Google Play distribution policy are assessed separately. No unapproved broad package-visibility permission is added.

## Out of scope

- App-private data, accounts, saved credentials, runtime permission state, licensed/DRM content, cloud downloads, and expansion assets.
- Silent installation, install-policy bypass, privileged/system package extraction, or root-only access.
- Claiming that every installed package is exportable on every OEM/API combination.

## Required test matrix

- Monolithic APK; base + language split; base + ABI split; base + density split; multi-split package.
- Same package/version/signature; inconsistent signatures; missing base; missing split; duplicate split; mixed version.
- API 26, API 31 (owner Galaxy A12), API 33+ permission/package visibility, and current target API.
- Package hidden by visibility, non-launchable package, updated system app, inaccessible system app.
- User rejects installation; install blocked by OS; insufficient space; installation success callback.
- No installation attempt before explicit user action.

## Exit gate

CP-005 may be marked `PASSED` only after exact-SHA CI, repeatable physical-device export/receive/install tests, package-set integrity evidence, and distribution-policy review. Until then it remains `IN_PROGRESS`.
