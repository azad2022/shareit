# ADR-0011: Installed application and APK package sharing

- **Status:** Proposed
- **Date:** 2026-11-08
- **Scope:** CP-005 package-sharing milestone
- **Related:** PRODUCT_REQUIREMENTS.md §9, ADR-0009, CP-004, transfer pipeline audit 2026-11-08

## Context

PEYVAND's product requirements include eligible Android application package sharing. The current CP-004 transfer path is intentionally restricted to user-authorized `content://` sources. The Android catalog can display launcher-visible installed applications, but the storage adapter cannot open `ShareSource.InstalledPackage`; current UI guards reject selecting those unsupported items.

A standalone APK chosen through SAF is a generic file. It is not equivalent to extracting an installed application. Modern Android packages may contain a base APK and multiple required split APKs. Sending only the base APK may produce an incomplete or incompatible installation.

## Proposed decision

Implement installed-application sharing as a distinct package-aware domain workflow in CP-005, while reusing the existing authenticated transport, bounded streaming, per-item SHA-256 verification, staging, and batch item outcome machinery where the package model permits.

1. Keep generic file selection and generic APK document transfer on the SAF `ContentUri` path.
2. Introduce an immutable package transfer descriptor containing package name, version code/name, min/target SDK where available, signing certificate digest(s), and a deterministically ordered artifact list.
3. Extract only accessible package artifacts through supported PackageManager/ApplicationInfo metadata. Include the base APK and all required split APKs available for the selected installed package. Never read another app's private sandbox data.
4. Treat the package as an atomic logical selection: the base and all required splits are accepted/rejected together. Transfer artifacts sequentially; verify each artifact's size/hash and validate set-level package/version/signature consistency before marking the package received.
5. On the receiver, inspect the received APK artifacts before offering installation. Use PackageInstaller sessions and let Android own the user-confirmation/installation result. Installation is separate from receipt.
6. Never silently install, bypass Android confirmation, or claim that a valid signature makes an APK safe.
7. Preserve app-private data, accounts, runtime permissions, Play licensing, expansion assets, and downloaded content as explicitly out of scope.
8. Package visibility must use the narrowest supported query surface. The current launcher-intent query lists launchable apps, not every installed package. `QUERY_ALL_PACKAGES` is not approved by this ADR; it requires a separate distribution-policy assessment and explicit decision for Google Play versus direct APK.
9. If a package is not visible, not launchable, system-restricted, inaccessible, or missing required split artifacts, report it as unavailable with a reason rather than presenting a sendable item.

## Compatibility and safety constraints

- A package set must have one package name, one version code, compatible split names, and consistent signing certificates across every APK.
- The receiver must reject duplicate/missing base APKs, duplicate split names, mixed package identities, mixed version codes, and signature mismatches before installation.
- Installation must use Android PackageInstaller. A committed session may require user intervention; no silent install is promised.
- A successful file transfer does not imply successful installation.
- Package extraction must be bounded and cancellable. Do not read APKs wholly into memory.
- Package metadata and APKs are untrusted transfer inputs. Validate before handing to PackageInstaller.

## Open decisions before implementation

- Exact minimum API and compatibility matrix for PackageInstaller session handling.
- Whether the app catalog remains limited to launchable apps or adds narrowly justified package queries.
- Google Play policy position for package visibility and `QUERY_ALL_PACKAGES`; evaluate separately from direct APK distribution.
- Whether CP-005 first delivers installed-app export only, or export plus user-initiated install handoff.
- How unsupported/archived/system/updated-system packages are classified across OEMs.

## Consequences

- CP-004 remains limited to generic content URI files and does not become PASSED merely because CP-005 is planned.
- The current unsupported-app selection guard must remain until the package-aware source and receiving workflow are implemented and tested.
- Product copy must distinguish APK file transfer, installed package transfer, installation, and app-data migration.
