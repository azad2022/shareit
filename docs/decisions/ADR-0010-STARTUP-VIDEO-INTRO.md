# ADR-0010 — Branded startup video playback

- **Status:** Accepted for the startup-loading surface; bundled media asset and device verification remain pending.
- **Date:** 2026-11-08
- **Scope:** One-shot, muted PEYVAND startup video shown only while the existing application bootstrap is genuinely in progress.

## Context

The product owner supplied a 10-second vertical H.264 video (720×1280, 24 fps). A silent derivative was prepared without re-encoding the video stream:

- Expected resource path: `app/src/main/res/raw/peyvand_splash.mp4`
- Duration: 10 seconds
- Video stream: H.264, 720×1280, 24 fps
- Audio stream: absent
- Prepared file size: 2,385,438 bytes
- Prepared file SHA-256: `fbfb9d37220cfcf57b5138c0e213a73cb9f696bfeba453e6a7ccc816904eae22`

Android's system splash is a platform-owned launch surface, not a general-purpose video player. Keeping it visible for the full clip would make launch time depend on a 10-second media duration and conflict with the platform's fast-startup model.

## Decision

1. Preserve the Android-managed system splash. Do not place a video player in the system splash window and do not hold the system splash until the clip ends.
2. Render the video in the existing in-app `StartupLoadingScreen` only while `MainActivity` is performing real bootstrap work.
3. Do not add an artificial minimum startup duration. When bootstrap reaches `Ready`, the existing state transition replaces the startup surface immediately, even if the clip is unfinished.
4. Play once, muted, without looping. If the clip ends before bootstrap completes, switch to the existing static loading presentation.
5. Pause playback when the Activity is backgrounded. Stop playback and release the `VideoView` when the startup composable leaves composition or is disposed.
6. If the resource is absent, cannot be decoded, or system animator motion is disabled, use the static loading presentation. Startup must never fail because of an optional brand animation.
7. Use Android's built-in `VideoView`; no external media dependency is justified for this short local resource.
8. Keep the raw video bundled in the APK. No network fetch, account, remote media host, or runtime Internet access is introduced.

## Consequences

- The intro is subordinate to actual app readiness; it does not delay entry to the home screen.
- Device decode support and visual fit still require tests on the minimum supported API and representative physical devices.
- The application code supports the named raw resource, but the binary file is not yet present in this Git branch. Until it is added at the expected path, the existing static loading UI remains active. A build without the optional media file must remain valid.

## Validation gates

- Confirm the committed binary matches the prepared SHA-256 and has no audio stream.
- Build Debug on API 26 minimum and current API.
- Verify cold start, warm start, bootstrap faster than the clip, bootstrap slower than the clip, immediate background/foreground, Activity recreation, decode failure, missing resource and reduced-motion fallback.
- Confirm that playback stops as soon as `StartupState.Ready` replaces `StartupLoadingScreen`.
- Confirm the app remains offline; no runtime network request is involved.
