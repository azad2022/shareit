# SHAREit Android — Product Requirements

- **Status:** Product definition / planning. No application implementation is authorized yet.
- **Repository:** `azad2022/shareit` (private), default branch `main`.
- **Last decision update:** 2026-11-01.

## 1. Product vision

Build a premium-quality Android application for fast, secure, reliable, direct wireless transfer of files and eligible Android application packages between nearby devices. The product is inspired by the user needs served by SHAREit but must be independently designed and implemented.

## 2. Confirmed scope and constraints

| Area | Confirmed requirement |
|---|---|
| Platform | Android only |
| Core connectivity | Offline, local peer-to-peer; no central server required |
| Primary wireless method | Wi-Fi Direct |
| Transfer modes | One-to-one and one sender to multiple receivers |
| Content | User-selected files/media and eligible app packages (APK/split APK where supported) |
| Performance | Competitive, SHAREit-class performance where hardware and conditions allow; benchmark rather than promise universal parity |
| Product priorities | Security, integrity, reliability, compatibility, maintainability |
| Frontend | Bespoke premium UI, polished purposeful animations, coherent design system |
| Architecture | Modular, layered, independently testable; avoid oversized files/classes |
| Progress governance | Repository-based checkpoints with test evidence and commit SHA |
| Current build mode | Debug only; standard Android debug signing |
| Future distribution | Google Play and direct APK distribution |
| Release signing | Deferred until release preparation; production keys protected outside Git |

## 3. Offline and networking requirements

- Device discovery, pairing, and core transfer must work without internet access.
- No cloud upload, remote relay, account registration, or central backend is required for local transfer.
- Transfer data travels directly between participating devices.
- Wi-Fi Direct is the preferred primary transport, subject to runtime capability checks and Android/vendor behavior.
- Local sockets are permitted; the Android `INTERNET` manifest permission may be required for socket APIs and does not mean the app must connect to the public internet.
- Do not claim every Android device supports identical Wi-Fi Direct behavior or throughput.

## 4. Transfer modes

### 4.1 One-to-one

Two devices discover, authenticate, explicitly approve the session, and exchange selected files. The product should support the user-approved bidirectional workflow.

### 4.2 One sender to multiple receivers

A sender selects multiple discovered receivers and a set of files. Each receiver must have independent consent, progress, cancellation, failure, resume, and integrity status. Group transfer must not corrupt or block unrelated receiver sessions. Radio capacity is shared; per-receiver throughput is not assumed to equal one-to-one throughput.

Simultaneous multi-sender exchange among all group members is not confirmed for the initial group-transfer scope and requires a separate product decision.

## 5. Performance and integrity

- Stream large files using bounded memory and backpressure.
- Support multi-file queues and large files without loading entire files into RAM.
- Provide safe interruption handling and resumability where the source and destination provider permit it.
- Verify completed files cryptographically (SHA-256 baseline candidate) before reporting success.
- Do not expose incomplete temporary files as completed output.
- Benchmark on physical devices and compare with SHAREit under matched conditions: device models, Android builds, Wi-Fi band, distance/interference, storage, file sizes, and test repetition.
- Report throughput distribution, connection time, stability, CPU/RAM, battery, thermal behavior, and resume success. Do not promise fixed speeds before measurements.

## 6. Security and privacy

- Treat local networks and discovered peers as untrusted until authenticated.
- Encrypt sessions and implement explicit peer authentication and user consent.
- Validate protocol frames, file metadata, lengths, and package artifacts.
- Never silently install APKs or bypass Android PackageInstaller/user approval.
- Do not claim APK transfer migrates private app data, credentials, accounts, or protected assets.
- Keep file contents on participating devices; do not upload them to a server.
- Avoid collecting or logging unnecessary personal metadata and secrets.

## 7. Frontend and UX quality

- Premium bespoke visual identity is a core requirement.
- Build and maintain a centralized design system: typography, color tokens, spacing, shapes, iconography, components, states, and motion principles.
- Use reusable feature-scoped components and explicit loading, empty, error, connecting, waiting, transferring, paused, recovering, completed, and failed states.
- Animations should be polished and purposeful, with no impact on transfer correctness, responsiveness, accessibility, or battery beyond accepted budgets.
- UI must remain separate from network, storage, package extraction, and cryptographic implementation.

## 8. Architecture and maintainability

- Preferred native stack: Kotlin and Jetpack Compose, subject to recorded ADRs.
- Use layered boundaries for presentation, domain, data, platform adapters, networking, security, and transfer infrastructure.
- Split features into focused modules where stable ownership justifies it.
- Avoid giant files/classes, circular dependencies, UI-owned business logic, and unnecessary abstraction.
- Record consequential decisions as ADRs before locking in implementation.

## 9. Checkpoint and quality governance

Maintain `docs/PROJECT_STATUS.md`, `docs/checkpoints/`, `docs/architecture/`, and `docs/decisions/` as the project grows. Each checkpoint records: ID, objective, acceptance criteria, status, commit SHA, test commands/results, device and OS details, evidence, known issues, and reviewer/approval.

Allowed statuses: `IN_PROGRESS`, `BLOCKED`, `PASSED`. Only mark `PASSED` when the acceptance criteria have reproducible supporting evidence. Major verified milestones should be recoverable through Git history and, where useful, tags.

## 10. Build, signing, and distribution

### Current development stage

- Work in Debug mode only.
- Use the standard Android debug signing configuration.
- Do not create a production keystore or configure release signing secrets yet.
- Never commit debug or release keystores, passwords, tokens, or signing material.

### Future release stage

- Plan for both Google Play and direct APK distribution.
- Review current Google Play policy for package visibility and all-files access; do not assume permissions are automatically allowed because a feature uses them.
- Use a dedicated production signing key, protected offline backups, and tightly scoped GitHub Environment/Actions secrets when release automation is approved.
- Review Play App Signing and the distinction between upload and app-signing keys if Google Play is used.
- Keep Play and direct-distribution release procedures explicit and separately testable.

## 11. Open decisions — do not guess

- Minimum supported Android API level and target SDK policy.
- Exact fallback transport order when Wi-Fi Direct is unavailable.
- Final protocol framing, serialization, chunk sizing, and concurrency limits.
- Pairing bootstrap and peer identity lifecycle.
- Exact background transfer mechanism across Android versions.
- Initial maximum supported group size based on device testing.
- Whether simultaneous multi-sender group exchange is required in a later release.
- Transfer history retention and cleanup policy.
- App identity, branding, localization, and package/application ID.
- Quantified throughput and reliability acceptance thresholds after benchmark baselines.

## 12. Implementation authorization

This document records product requirements only. It does not authorize coding. Begin Android project scaffolding or implementation only after explicit product-owner approval.
