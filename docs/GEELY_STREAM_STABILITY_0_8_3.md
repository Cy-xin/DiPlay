# DiPlay Geely 0.8.3 — stream stability

Baseline: e1190a95 (0.8.2). Local branch: codex/geely-stream-stability.
Package remains com.shihab.diplay; versionCode 125.

## Evidence and limits

The latest KX11_A2 report contains today's active session on 0.8.2. During the 13:22 stall, video socket-read wall time reached 1,181ms while processing was approximately 3.4ms. Music read time reached 1,586ms while processing was approximately 1.7ms. Across the current session, UDP RTP sequence-forward gaps sum to 29 missing sequence positions. These are distinct from the renderer's zero local queue-drop count; they do not alone identify the phone, radio, driver, socket or scheduler as the cause. Read timing includes time descheduled and is not a kernel-only measurement. No raw report, credentials or device identifiers are committed here.

The old receiver observer stopped at AirPlay session activation, leaving only baseline/cached kernel counters. Consequently that report cannot establish whether socket receive-buffer drops grew during ongoing playback.

## Changes

- Reuse the original clock PI controller, with a PCM safety floor: below half the configured startup target, compressed backlog or a stale smoothed surplus must not speed up playback. Safety braking may bypass the normal one-second update delay. Negative correction remains available for genuinely fast hardware clocks; healthy PCM can still catch up on compressed backlog.
- Check the draining PCM queue from the existing audio worker's maintenance path, not only immediately after writes. Between writes, only apply safety braking: preserve the original PI estimator and do not learn a network gap as hardware clock drift. No additional audio worker, altered system volume, changed output bus, new audio-focus policy or larger default buffer.
- Report PCM/compressed/target durations, requested rate and safety-protection count on a separate short five-second diagnostic line. Track rejection still disables clock adjustment and leaves playback running.
- Add receive-loop gap timing, separating time between iterations (including diagnostic callback overhead) from socket-read and payload-processing wall time. No payload or network endpoint is logged.
- Retain the existing wireless observer during a live session. It samples only bounded interface/UDP counters every 30 seconds; startup uses the existing detailed queries and ten-second cadence. Unavailable sources are not repeatedly probed. Disconnect/controller teardown closes the observer; no boot receiver, offline polling or new persistent service.
- When exactly one device is bonded, select it immediately after honoring any explicit user selection. This is the same device the previous fallback selected, including renamed iPhones; avoid up to two three-second profile queries. Ambiguous multi-device selection and a missing explicitly selected device retain the original behavior. The six-second benefit depends on that actual pairing state, not guaranteed for all cars.

## Regression coverage

Retain the original fast/slow/accurate-clock simulations, trim bounds, pause handling, compressed backlog and rate-rejection cases. Add depleted-PCM with compressed bursts, stale surplus and inherited positive drift, immediate safety braking, recovery with healthy PCM, and an AudioTrack-level test that drains PCM without receiving another packet. Receive diagnostics retain sequence-wrap/gap coverage and add receive-loop timing/reset checks. Observer tests cover live-only sampling and stopping on teardown.

## Validation

The complete AGENTS.md check command plus Geely app/core tests, app lint and authenticated standalone build passed (413 tasks, clean isolated build). Tests: geely-core 93, geely 55, shared 1654 (one existing skip), common 1502, home 6; zero failures/errors. APK package/label/components/permissions/R8 trimming and external authentication-asset equality verified. APK signature is valid and matches 0.8.2. The build source was compared byte-for-byte with all 2,057 current tracked/new files before this validation-document update.

The original working directory repeatedly regained old duplicate generated class files even after cleanup. Final checks and APK were built from an identical source snapshot under /private/tmp/diplay-geely-stability-source, with fresh generated files, rather than relying on that directory's outputs. No source or authentication asset was replaced as part of cleanup.

No car or Redmi is currently attached to ADB. This release must not be described as a demonstrated fix for real-world stalls until physical A/B testing.

APK SHA-256: 4bd480cf75d50bf204d2d0c41c005cdc22d01e493c7dcbd7dcdb0335794c7af2.

## Vehicle verification

1. Install over 0.8.2 without clearing data; keep the same resolution, FPS and music-buffer setting for comparison.
2. Connect through the car hotspot and play the same music for several minutes, then switch and drag map/apps. Note the time of any stall or interruption.
3. Exercise navigation over music, return to DiPlay and the car launcher, resume CarPlay, then deliberately disconnect/reconnect.
4. Upload logs with the symptom and approximate time. Compare read/process/loop gaps, RTP sequence gaps, live receive-counter deltas, clock protection and rebuffer counts. UDP kernel counters are device-wide and cannot alone attribute drops to DiPlay.
5. Test the same-LAN mode separately. Real calls, Siri and independent system-volume sliders remain physical verification items.
