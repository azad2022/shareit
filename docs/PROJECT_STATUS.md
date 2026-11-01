# PEYVAND project status

- **Product:** PEYVAND (پیوند)
- **Repository:** `azad2022/shareit`
- **Current status:** `IN_PROGRESS`
- **Current checkpoint:** CP-001 — Initial Android foundation
- **Current branch:** `feature/foundation-bootstrap`
- **Last updated:** 2026-11-01
- **Latest source fix commit:** `71538591fcc2a3473920b26ba3388781dc2cfac1`

## Current milestone

Establish the first native Android foundation: Kotlin/Compose build, four focused modules, localization preference infrastructure and first-run Persian language selection, PEYVAND blue/white theme, automated CI, and reproducible tests.

## Confirmed implementation limits

- Only CP-001 foundation scope is authorized by the product owner at this point.
- No device discovery, Wi-Fi Direct transport, pairing, socket protocol, encryption, file transfer, resume, APK extraction, PackageInstaller, or background transfer is implemented yet.
- Debug build only; no release keystore or signing secrets.
- Persian is the only enabled language. The other six target languages remain disabled pending complete translation and validation.

## Open decisions

- Final `minSdk` and supported physical-device matrix (current `26` is provisional).
- Final application ID (current `com.peyvand.app` is provisional).
- Kurdish variant/script and locale tag.
- First transport/security architecture ADRs for CP-002.

See `docs/checkpoints/CP-001-FOUNDATION.md` for acceptance criteria, test evidence, and current risks.
