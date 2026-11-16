# DiPlay Geely 0.8.6

Baseline e14a6452 (0.8.5); continue codex/geely-return-picture.
Package com.shihab.diplay, versionCode 128.

## Changes

- Add Vehicle home / 车机主页 to the connection header with a minimum 48dp
  touch target. Launch the system HOME intent without finishing the receiver
  or disconnecting its session. If HOME is absent or restricted, move this
  task to the background. This uses the existing background-retention lifecycle.
- Stop stale-media recovery once backlog falls below the overload threshold.
  A subsequent catch-up burst must independently exceed the threshold for the
  full grace period before another eviction. The old recovery flag could
  remain active at a 500ms target while waiting to reach a 200ms exit level.
  A regression checks catch-up preservation and later sustained overload.
- Distinguish failure to connect from an interruption of an established session.
  Explain hotspot-address changes and iAP2 control-link termination directly.

## Evidence and limits

Latest uploaded report 0366cd9f-206e-4fec-9dab-d28e82f23fa6, KX11_A2, 0.8.5,
contains historical sessions plus the 2026-11-16 07:30–07:46 session. Its 630
media dropped counter includes deliberate stale-backlog eviction and queue-full
loss; it is not a count of proven radio packet loss. Long arrival gaps of up to
6.4 seconds remain unexplained by these changes. Do not promise elimination
of dropouts or improve quality/frame rate to mask them.

No hotspot automation, protocol rewrite, package-identity change or system-app
modification. Authentication assets recovered locally from the previously
verified 0.8.5 APK; no contents printed or sent to source control.

Physical car/home-button layout and connected background behavior still need
vehicle confirmation. No new device screenshots were captured for this change.
Offline build/test results and APK checks accompany the local deliverable.

## Completed verification

All AGENTS.md checks plus Geely tests/lint/standalone build passed, 413 tasks
executed, build successful. Core 104 tests, app 61, shared 1654 (one existing
skip), common 1502, home 6; zero failures/errors. Runtime and test inputs match
the final working source. Package/name, API30, R8 exclusions, authentication
asset equality and APK signature checks passed. Certificate matches 0.8.5.
APK SHA256: 66a62eacecc42665da97d599ce88af61067b71afc62eb00c58e8ab1e88ea3dd1.

This is an offline-verified build, not an actual head-unit playback result.
