# ADR-0017: Chat-First Product Home and Conversation UX

- **Status:** Accepted
- **Decision owner:** Product owner
- **Decision date:** 2026-11-13
- **Scope:** Primary navigation, chats list, one-to-one conversation UI, and attachment entry point

## Context

The previous home screen presented three large actions (Send, Receive, Files), which made PEYVAND feel like a transfer utility with no coherent messaging experience. The product owner explicitly rejected that information architecture and approved a chat-first experience. The owner supplied a Telegram conversation screenshot as a quality and interaction reference.

The reference is used only to understand familiar messaging conventions: a compact conversation header, chronological message timeline, clear empty state, persistent bottom composer, attachment entry point, and future voice/video call affordances. PEYVAND must not reproduce Telegram's exact layout, wallpaper artwork, illustrations, icons, typography, text, or signature visual treatment.

## Decision

1. The primary app destination becomes **Chats**. The old three-button Send / Receive / Files home screen is removed from the primary experience.
2. The Chats screen presents locally known trusted conversations, a search affordance, and a truthful nearby/trust-entry point. Discovered but unauthenticated devices must not be shown as trusted conversations.
3. Selecting a trusted peer opens a dedicated one-to-one conversation screen.
4. The conversation screen includes:
   - A compact peer header with user alias, verified-peer indicator, current direct-connection/synchronization state, and overflow actions.
   - Voice-call and video-call affordance icons may be present for future discoverability, but must be visibly unavailable/disabled until those features are separately designed, authorized, and implemented. They must not initiate fake calls.
   - A chronological message timeline with incoming/outgoing bubbles, localized timestamps/date separators, and accessible textual delivery states.
   - A clear empty-conversation state with PEYVAND-original illustration/visual language.
   - A persistent bottom composer with multiline text input, emoji affordance, attachment action, and send action. A voice-message control is not included unless voice-message capture/transfer is separately designed and implemented.
   - An attachment sheet for photo, video, audio, documents, generic files, and eligible APK artifacts, delegating file bytes to the existing transfer subsystem.
5. File transfer remains an independent domain workflow. A message attachment reference never implies successful transfer. Transfer progress, receiver consent, verification, and publication are represented using actual transfer-job state.
6. The primary visual direction is PEYVAND's own premium blue-and-white design system. A subtle original chat wallpaper/pattern is allowed only if contrast, accessibility, battery, and rendering performance remain sound.
7. The UX must support Persian-first RTL and correct LTR locales, TalkBack, font scaling, 48dp minimum touch targets, reduced motion, keyboard insets, and edge-to-edge safe drawing.
8. The app must distinguish local save, waiting for direct connection, synchronization, durable remote delivery, and error. It must never show a queued message as delivered or show read receipts/online presence without an approved real source.
9. The app remains offline/local-first. No server, account, cloud, push service, or relay is introduced.

## Scope boundaries

- Initial chat is one-to-one text only.
- Attachments delegate to existing one-to-one transfer capability; file transfer state is not a chat delivery state.
- Group chat, read receipts, voice messages, voice calls, video calls, remote notifications, and cloud backup are not included.
- Call icons are disabled affordances only; call implementation requires a future ADR and explicit scope authorization.
- Existing session TLS certificate and file-transfer channel remain exclusively owned by file transfer. Chat uses a separately reviewed persistent peer identity and message-sync protocol; no ad-hoc channel multiplexing.

## Acceptance criteria

- The old three-action home screen is no longer the primary screen.
- Chats list and conversation screen use real local repository and trusted-peer state; no fabricated contacts, messages, online states, transfer progress, or delivery receipts.
- The composer clears only after durable local persistence succeeds.
- Attachment selection delegates to existing file selection/transfer and preserves receiver consent and integrity verification.
- All displayed states are backed by repository/session state and have localized accessible text.
- Automated Compose/ViewModel tests and physical-device RTL/LTR, large-font, TalkBack, lifecycle, and two-device tests are recorded in the checkpoint.

## Reference

Product-owner-provided Telegram conversation screenshot (2026-11-13). It is an interaction-quality reference only; PEYVAND artwork and composition must remain original.
