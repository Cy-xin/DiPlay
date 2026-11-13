# DiPlay Geely 0.8.0: independent audio output mapping

Baseline `0e162a2d`, branch `codex/geely-audio-route-settings`. No new dependencies, system permissions, native libraries or background tasks. Previous KX11 peer-scoped Bluetooth music handoff is preserved.

## Settings

Settings -> Audio & display -> Audio output mapping has separate rows for media music, navigation, calls, assistant/Siri and ringtone. Each offers automatic Geely matching, system routing, or any of these five captured buses. Reset clears only mapping preferences. Saving never disconnects the current session; changes apply at the next connection.

| CarPlay sound | Android usage | KX11 default bus | Captured CarAudio group |
|---|---|---|---|
| media | MEDIA (1) | bus0_media_out | 0 |
| default (navigation guidance) | ASSISTANCE_NAVIGATION_GUIDANCE (12) | bus1_navigation_out | 1 |
| telephony/facetime | VOICE_COMMUNICATION (2) | bus4_call_out | 3 |
| speechrecognition | ASSISTANT (16) | bus2_voice_command_out | 2 |
| ringtone/ring | NOTIFICATION_RINGTONE (6) | bus3_call_ring_out | 3 |

Defaults come from the actual KX11_A2 vendor XML and runtime Audio Policy Mix captured on 2026-11-13. Automatic matching is model-gated to KX11; other devices keep the existing system policy. Other head-unit firmware must be checked independently.

## Implementation

- Store only validated stable bus addresses/auto/system per role, not volatile runtime IDs. Resolve output IDs at each connection and reuse original AudioOutputDevice resolution and AudioTrack preferred-output handling.
- Manual cross-category selection also maps the standard AudioAttributes usage so the car's dynamic policy can match the chosen volume category. Only the five ordinary usages are allowed; no arbitrary vendor/emergency values are accepted.
- Logical CarPlay role classification, navigation mix/ducking and stream lifetime remain intact. Mapping to another category may change vehicle focus priority; the dialog says so.
- Missing or ambiguous devices retain the intended address but resolve to no forced device, falling back to system routing. The choice is not silently rewritten. The UI distinguishes output detection from actual route acceptance; original audio diagnostics report acceptance/fallback and playback route IDs.
- No direct writes to CarAudio volume groups, no system audio policy edits, no account/OEM app integration. Use original car volume controls after outputs are verified. Calls and ringtone are separate buses but share a captured CarAudio group, so fully separate volume sliders cannot be promised.

## Offline checks / physical boundary

Tests cover KX11 defaults vs non-car system routing, independent manual assignment and usage classification, changed IDs, missing/ambiguous outputs, invalid/emergency values, reset and no disconnect while saving. Required repository checks and app/core tests are run. Layout must be checked on compact and wide windows with enlarged font.

This version is prepared offline. Actual route acceptance and separate media/navigation/Siri/call/ringtone volume control require a later physical KX11 test. No automatic call or assistant command is executed during offline checks. A successful build/detection is not evidence of successful physical volume separation.

## Completed offline validation

Required CI tasks passed; app 45 tests, core 55 tests, shared 1654 tests (one existing skip), common 1502 tests; zero failures/errors. App lint, APK package/components/R8/auth-asset checks and signature verification passed. Physical KX11 is not connected for this change; no device installation or volume changes were performed on the car.

Emulator: manually mapped media to navigation and verified the saved label, then restarted and confirmed it remained saved. Captured 1280x720 and 720x1280 at font scale 1.3, plus Arabic-locale RTL layout (existing Chinese text fallback; no new Arabic translations). Selection options scroll and cancel remains visible.

APK SHA-256: a2c328a0d31141df6821e4de676c064994f892c6bfe3f684096b0e1ec2f7a09a. No new dependencies. Local APK and R8 mapping retained; no source, mapping, APK or authentication material uploaded to the server.
