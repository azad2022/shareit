# CP-CHAT-UI-001 — Messaging UI Preview and Interaction Surface

- **Status:** `IN_PROGRESS`
- **Opened:** 2026-11-19
- **Owner authorization:** Explicitly granted on 2026-11-19 to complete messaging UI first and defer backend work.
- **Repository:** `azad2022/shareit`
- **Branch:** `feature/pey-vand-messaging-ui-preview`
- **Base implementation commit:** `813016d422cd33c54433e897b72fa79cb976690b`
- **Scope:** Kotlin/Jetpack Compose UI-only; Debug preview surface; no backend expansion.

## Objective

Create a reviewable UI surface for the owner-approved messaging product direction while preserving the already implemented one-to-one chat and transfer routes. The new surface is intentionally isolated behind the Android debug flag so product visuals and interaction affordances can be reviewed before wiring real domain state and backend services.

## Included UI areas

- Group conversation list and create-group dialog.
- Group message timeline with sender labels, read-receipt count and typing-indicator presentation.
- Message action affordances for reply, edit, delete-for-self, delete-for-everyone and forward.
- Attachment cards for photo, video, music, audio, documents, PDF, archive, APK and other files.
- Voice-message presentation with recording/review state illustrations and waveform treatment.
- Group members/roles, admin management affordances, group rename and member management states.
- Explanation of temporary sequencing-leader behavior and the distinction between group admin and Wi-Fi Direct Group Owner.
- Entry point visible only in debuggable builds.

## Explicit exclusions

This milestone does **not** implement or alter:
- Group membership storage, invitations, direct peer synchronization, sequencing protocol, election/leader failover, or Wi-Fi Direct group migration.
- Message networking, Room/schema changes, offline queues, edit/delete tombstones, read receipts or typing events.
- Real native photo/document pickers, MIME inspection, file transfer, destination storage or duplicate-name handling.
- Microphone permission, recording, voice encoding, audio playback, waveform extraction or voice-message transfer.
- Notifications, foreground services, background discovery or connection lifecycle.
- Current one-to-one chat transport and the existing file transfer pipeline.

All values in the review surface are **sample data**. They must never be saved, transmitted, or represented as verified current state. Controls may alter local preview state or show a clear “preview only” message, but must not claim a real operation succeeded.

## Owner-approved messaging requirements to preserve in the UI design

- A device may belong to multiple groups; only one group session per device is active at a time.
- Each invitee independently accepts or rejects joining. A rejection does not block other invitees.
- Group members receive only messages sent after they join.
- Only members to whom a message was delivered participate in that message's read-receipt denominator; display reader count, not reader identities.
- Typing indicators may identify the member who is typing.
- Private one-to-one conversations remain independent when either participant leaves a group.
- A member's temporary disconnection does not end membership. Only explicit Leave Group does.
- The app automatically reforms the Wi-Fi Direct network and recovers known members using stored device identity after Group Owner loss, subject to platform capability.
- Group admin and Wi-Fi Direct Group Owner are separate roles.
- The primary admin determines final message order. During temporary absence, the oldest available member acts as temporary sequencing leader; the primary admin regains authority on return. On actual admin departure, the oldest remaining member becomes the new admin.
- Capacity warnings must be grounded in a runtime estimate; no fabricated member limit is allowed.
- Offline-recipient edits/deletions remain queued and must be applied before the stale message is displayed on that recipient.
- Delete-for-everyone has no time limit while the message remains in local history.
- When the last member leaves, only that device deletes its own local group/history; no remote deletion command is propagated.
- Attachments support multi-select and native Android pickers; received files are to be saved into PEYVAND's approved public category folders with unique names on collisions. Only the sender may cancel the receive.
- Voice messages are limited to 10 minutes, compressed by default, stored under `PEYVAND/Voice Messages`, and automatically saved when received.

## Acceptance criteria

1. Preview entry point is present only when `ApplicationInfo.FLAG_DEBUGGABLE` is set; release/non-debug builds cannot navigate to the preview.
2. Existing chat-first home, one-to-one conversation flow and transfer screens are unchanged except for the debug preview entry point.
3. Preview content has an explicit sample-data banner and never claims real connection, delivery, membership, read, typing, recording, playback or transfer state.
4. No preview action writes to Room, invokes network/connectivity APIs, requests microphone/storage permissions, launches a file picker, or creates a file.
5. All visible copy is resource-backed; no new external dependency is introduced.
6. Group and attachment lists remain usable on compact screens and with larger font scaling.
7. RTL Persian behavior, TalkBack labels/order, touch targets and system back navigation are reviewed.
8. Exact-head CI build, unit tests and lint complete successfully before the checkpoint can advance beyond IN_PROGRESS.
9. Physical-device visual review is recorded on representative Android phones; CI/emulator evidence is not substituted for physical visual review.

## Evidence log

- Base branch HEAD verified as `813016d422cd33c54433e897b72fa79cb976690b`.
- UI source and resource blobs are being added on this branch.
- Local Gradle execution is not available in the current environment; exact-head CI and physical-device review remain outstanding.
- No UI/build/test/device result is marked PASSED at this stage.

## Known risks

- This is a visual/interaction review surface, not the final production wiring. Some actions deliberately show a preview-only notice.
- Final production UI must consume typed immutable UI state from ViewModels/repositories; it must not preserve mock/sample records as a substitute for real backend functionality.
- UI details for language switching and Kurdish script remain subject to the existing localization requirement; Kurdish must not be enabled before Sorani/Kurmanji variant is selected and reviewed.


## CI failure analysis and corrective change

- **Initial exact-head run:** Android CI run [44482801826](https://github.com/azad2022/shareit/actions/runs/44482801826), commit `2903f8e3e960070e9d429c78b592046099eb7f54`.
- API 26 instrumentation job: `success`.
- API 36 instrumentation job: `success`.
- Build/unit-test/lint job: `failure` during Kotlin compilation in `ChatHomeScreen.kt:101–106`.
- Root cause from compiler output: the new Debug-preview entry used Material 3 `TextButton` without importing it. The later Compose-context diagnostics were cascading from that unresolved symbol.
- Corrective change: add the missing `androidx.compose.material3.TextButton` import. No behavior, navigation, backend, transport or data-layer changes are made.
- Re-run exact-head CI after the corrective commit. Keep status `IN_PROGRESS` until it completes and the physical visual review is recorded.


## Exact-head CI evidence before responsive UI polish

- Commit: `94c3a6c8b274f555bc9439637fafb26ca0245d75`
- Workflow: [Android CI run 44499918249, attempt 2](https://github.com/azad2022/shareit/actions/runs/44499918249)
- **Build, unit tests, lint and Debug APK:** `success`.
- **Android storage publication and mTLS, API 26:** `success`.
- **Android storage publication and mTLS, API 36:** `success` on retry. The first attempt failed during dependency resolution; the retry completed successfully without source or dependency changes.
- Debug APK SHA-256: `c4eb7f3e66089f568e76a42ce4feaaad5e37ba5616972aca4053b164defd579e`.
- Artifacts: Debug APK `peyvand-debug-apk`; API 26 and API 36 instrumentation APKs and reports.
- This evidence validates commit `94c3a6c8...` only. The pending responsive layout, dialog scrolling and TalkBack improvements in the next commit are not covered by this run.
- Physical-device visual/accessibility review remains outstanding; status remains `IN_PROGRESS`.
