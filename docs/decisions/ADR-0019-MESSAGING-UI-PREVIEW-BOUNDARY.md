# ADR-0019 — Messaging UI Preview Boundary

- **Status:** Accepted for CP-CHAT-UI-001
- **Date:** 2026-11-19
- **Owner authorization:** UI-first implementation; backend work explicitly deferred.
- **Decision scope:** Presentation architecture and Debug review boundary only.

## Context

The existing `feature/cp-chat-002-chat-experience` branch contains a working-scope one-to-one chat UI and transport, but the newly approved product UX expands to group conversations, voice messages, richer message actions and multiple attachment types. Implementing those backend capabilities first would make UI review dependent on unsettled protocol/data implementation and would risk changing the existing working one-to-one path.

## Decision

1. Add the new messaging/group UI review surface as Kotlin/Jetpack Compose code in a separate feature branch, based on the inspected CP-CHAT-002 implementation head.
2. Expose the review surface only when `ApplicationInfo.FLAG_DEBUGGABLE` is true. Non-debug/release builds do not show the entry point.
3. Keep preview state local to Compose and use fixed illustrative sample records. Display a persistent warning that sample data are not connected to any peer, network, storage, microphone or transfer engine.
4. Preview controls may navigate between panels or demonstrate local interaction affordances. They must not call current networking, Room, file transfer, microphone, Android picker, storage or service APIs.
5. Keep all user-visible text in resources and reuse PEYVAND's existing semantic blue/white design tokens and original icon family.
6. Do not introduce a UI framework or third-party dependency for this preview.
7. Preserve the existing production one-to-one chat and file-transfer routes. The preview is not a replacement for, or a claim of completion of, the production messaging feature.
8. After visual acceptance, replace sample sources with typed immutable UI state from presentation ViewModels and approved domain contracts in separate reviewed milestones. Backend/protocol implementation remains a separate scope.

## Consequences

### Positive
- Product owner can review navigation, hierarchy, copy, group governance affordances, file-card patterns and voice-note presentation before committing to backend shape.
- Existing validated-by-owner flows are less likely to regress due to UI exploration.
- Sample UI cannot accidentally send data, request permissions or store placeholder content.

### Trade-offs
- Data and actions shown in the preview are illustrative only; none of the actions are operational.
- A debug-only preview is not release-ready UI and does not satisfy physical-device acceptance by itself.
- Additional integration work is required to bind the screens to real UI state and production use cases later.

## Review triggers

Revisit this ADR when:
- The product owner accepts the visual/interaction design and authorizes production UI integration.
- The UI must consume real group/message/voice/attachment state.
- Backend and group protocol work are authorized.
- A language bundle is enabled beyond Persian or a new UI dependency is proposed.
