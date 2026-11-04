# PEYVAND (پیوند) — Product Identity, Localization, and Motion Specification

- **Status:** Confirmed product direction; CP-001/CP-002 and the narrowly scoped CP-003 one-to-one transfer implementation are owner-authorized. Product-wide visual redesign and later group-transfer scope remain milestone-gated.
- **Product:** PEYVAND (پیوند)
- **Repository:** `azad2022/shareit` — keep repository name unless separately approved.
- **Last updated:** 2026-11-01.

## 1. Brand identity

PEYVAND is a native Android product for direct, offline transfer of files and eligible Android application packages. Its category reference is SHAREit, but the product must be independently designed and implemented. The name shown to users is **PEYVAND**; the Persian form is **پیوند**.

### Color direction

Blue and white are the dominant brand colors. The goal is a clean, energetic, premium interface with high clarity and obvious primary actions. This is a color-direction decision, not permission to reproduce SHAREit's exact screens, artwork, iconography, logo, or motion. Design a distinct PEYVAND visual language.

Create semantic tokens rather than per-screen color literals:

- Primary and secondary brand blues, chosen for hierarchy and contrast.
- White and layered neutral surfaces for cards, sheets, backgrounds, and elevation.
- High-contrast foreground tokens for body text, captions, disabled states, and controls.
- Independent semantic tokens for success, warning, error, and informational states.
- Focus, pressed, selected, disabled, and scrim/overlay states.

Do not decide final HEX values by guesswork. A design-system checkpoint should include palette swatches, contrast evidence, light-surface hierarchy, button/state variants, and actual-device screenshots. Blue must not be the only indicator of state; pair color with text, iconography, and accessibility semantics.

### Design quality

The design must be original, consistent, fast, legible, accessible, and cohesive across all major flows. Use a stable type scale, spacing scale, shape rules, elevation model, icon family, and component variants. Prefer a small set of refined reusable components over a large collection of one-off screen widgets. Do not allow a screen to invent its own spacing, colors, typography, or button behavior.

## 2. Required implementation language and UI approach

Kotlin is mandatory for new Android product code. Jetpack Compose is strongly recommended as the UI toolkit because the product requires state-driven rendering and deliberate animation. Confirm Compose and version/dependency policy in an ADR before scaffolding. Do not silently substitute Java or a cross-platform toolkit.

The UI layer renders observable state. It must not own transport sockets, peer authentication, file reads/writes, cryptography, APK extraction, or transfer orchestration. A ViewModel/use-case boundary should make it possible to test state transitions without rendering a screen.

## 3. Supported languages and rollout

Target languages:

1. Persian (fa)
2. English (en)
3. Arabic (ar)
4. Turkish (tr)
5. Kurdish (exact locale/script to be selected)
6. German (de)
7. French (fr)

Persian is the initial complete product language for development and debugging. Localization architecture is built from the beginning, not added as a final retrofit. It is acceptable to complete the non-Persian translations in a later localization milestone, but no language may be marked available to users until its required content is complete and reviewed.

### First-run language selection

- On first launch, show a dedicated, clear language-selection screen before entering the main product.
- The user makes the initial selection once. Persist it in app preferences and apply it consistently after process death and app restart.
- Do not show the selector on every launch, and do not silently override the saved language when the system language changes.
- Provide a deliberate language-change control in Settings as a recommended usability feature. Changing language must not delete transfer history, pending files, or user settings unrelated to language.
- Display language names in their native forms (for example, فارسی, English, العربية, Türkçe, Deutsch, Français) and an approved Kurdish label/script.
- The saved app language, not the device's current locale, is the source of truth after the first selection.

### Localization architecture

- All user-visible strings must be externalized in Android resources or an approved localization layer. Never hard-code UI copy in Kotlin, Compose, tests that validate copy, or notification builders.
- Use stable resource keys. Avoid using translated text as identifiers, enum values, database keys, analytics dimensions, protocol values, or business logic.
- Include plural resources and locale-aware formatting for dates, times, numbers, sizes, and counts. Avoid manual string concatenation to build sentences.
- Keep protocol messages, package identifiers, MIME types, file names, filesystem/content URIs, URLs, IP addresses, and cryptographic identifiers unchanged. They are data, not translatable UI copy.
- Define fallback behavior for missing resources during development. Never display raw keys such as `transfer_completed` or empty labels. Do not enable an incomplete language bundle as production-ready.
- Use font fallback that covers Persian/Arabic/Kurdish glyphs. Check numeral policy and punctuation with native reviewers; do not force one numeral style without a product decision.
- Long German and French translations must not clip. Persian/Arabic layouts must handle text expansion, wrapping, and mixed-direction strings.

## 4. RTL, LTR, and bidirectional content

Persian and Arabic are RTL. English, Turkish, German, and French are LTR. Kurdish layout direction depends on the chosen locale and script.

RTL is not equivalent to setting all text alignment to the right. Use layout-direction-aware placement, navigation behavior, start/end padding, and mirroring only for directional icons. Do not mirror logos, media controls, or universally understood symbols without reason. Test back navigation, progress indicators, directional arrows, swipe actions, sheets, dialogs, and item ordering in both directions.

Mixed-direction data is critical in a file-transfer app. Test RTL screens that show Latin file names, APK package names, versions, URLs, IP addresses, sizes, timestamps, and hashes. Apply Unicode bidirectional isolation or equivalent safe formatting so periods, parentheses, colons, digits, and extensions remain attached to the correct content. Do not alter the underlying filename to make it visually nicer.

### Kurdish decision

“Kurdish” is not a sufficiently precise locale decision. Sorani is commonly written in an Arabic-based script and uses RTL; Kurmanji is commonly written in Latin script and uses LTR. The product owner must select the intended variant, locale tag, and writing system before final translation and launch. Do not assume that one Kurdish translation automatically covers both variants.

## 5. Motion design principles

Animation is required where it makes a real product behavior easier to understand or confirms a meaningful state change. It is not required on every screen. Motion should be professional, restrained, related to the feature, and consistent with the PEYVAND design system.

Create motion tokens for duration, easing, distance, scale, opacity, interruption, and sequencing. Each proposed animation must document:

- What user problem it solves.
- Which real UI/domain state triggers it.
- Which values drive its progress or final state.
- How it behaves if interrupted, cancelled, backgrounded, or recreated.
- Its reduced-motion/static alternative.
- Its frame-time, CPU, memory, and battery budget.

Compose-native animation APIs are the default. Add an external animation library only after evaluating binary size, maintenance, licenses, accessibility, and lifecycle behavior.

### Feature-specific motion expectations

- **Device discovery:** a subtle scan/radar/ripple or peer-reveal treatment may communicate active discovery. It must stop or change when scanning stops and must never imply a device was found when none exists.
- **Connection and pairing:** animate real stages such as discovery, connection, authentication, consent wait, connected, timeout, and failure. Never show a connected/success state before authentication succeeds.
- **File selection and queue:** use clear, lightweight item insertion/removal/reordering feedback. Avoid distracting motion in large lists.
- **Transfer:** a transfer path or item-motion metaphor may help, but progress, speed, and completion must be based on actual measured transfer state. Never fake progress or hide a stall with fabricated smoothing.
- **Group transfer:** every receiver must have a separate visual identity and independent state. A receiver's animation cannot be driven by another receiver's progress. Do not show a group-wide completed animation while any expected receiver is incomplete.
- **Verification:** distinguish receiving bytes from verifying a completed artifact. Show a success state only after integrity verification passes.
- **Pause, resume, cancel, recover:** animate the state change clearly without delaying the user's control or implying that a cancelled transfer continues.
- **Application package flow:** distinguish package transfer, validation, compatibility checks, and the OS installation handoff. Do not imply silent or completed installation before Android confirms it.
- **Error and retry:** use motion to guide attention, not to shame or startle the user. Error text and the next action must remain explicit.

### Motion prohibitions

No decorative infinite loops without a user purpose; no fake counters; no false speed estimates; no false completion; no animation that delays cancel/pause; no movement that makes status harder to read; no excessive particles, blur, or expensive compositing; no reliance on color or motion alone. Stop off-screen motion where practical. Respect system accessibility and reduced-motion settings, and offer a static/low-motion alternative for essential feedback.

## 6. Accessibility and performance

- Preserve TalkBack semantics and logical reading order in both RTL and LTR.
- Support large font scaling without clipped buttons, truncated critical status, or inaccessible dialogs.
- Use adequate touch targets and visible focus/pressed states.
- Meet WCAG AA contrast targets as the design baseline for text and essential controls; validate actual colors instead of assuming.
- Motion cannot be the only carrier of information; always expose readable status text and semantics.
- Benchmark animations on the lowest supported device tier and on 60 Hz and high-refresh devices. Watch frame drops, main-thread work, battery, thermal load, and unnecessary recomposition.
- If a visual effect competes with transfer stability or accessibility, simplify the effect.

## 7. Acceptance checklist

### Brand/design

- PEYVAND/پیوند is used consistently in customer-facing surfaces.
- Dominant palette is blue and white with documented semantic tokens.
- Visual assets and screen composition are original, not copied from SHAREit.
- Contrast, text scale, states, and component consistency are evidenced.

### Localization

- Persian, English, Arabic, Turkish, Kurdish, German, and French are the defined target set.
- First-run selection is persisted and not repeated on every launch.
- Language switching, if implemented, preserves transfer state and data.
- All user-facing strings are externalized; no raw key or untranslated placeholder is visible.
- RTL and LTR are tested, including mixed-direction filenames and package identifiers.
- Kurdish variant/script is explicitly decided and verified before it is enabled.
- Plurals, dates, sizes, numeral presentation, font fallback, large text, and long translations are reviewed.

### Animation

- Every animation has an explicit UX purpose and real source state.
- No false connection, progress, speed, or completion is shown.
- Independent group receiver state is accurately represented.
- Reduced-motion, interruption, lifecycle, cancellation, and screen-reader behavior are tested.
- Device performance and battery impact are measured on representative hardware.

## 8. Governance

This document adds product specifications; it does not authorize application code, Gradle files, or UI implementation. Keep decisions and evidence in Git. Update the engineering skill when these requirements change. Any implementation milestone must include its scope, acceptance criteria, tests, device/API details, commit SHA, evidence, and honest status. A checkpoint is `PASSED` only with reproducible evidence.
