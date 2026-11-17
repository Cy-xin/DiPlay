# DiPlay Geely 0.9.0 — guidance mixing and native volume context

Baseline 7654164b (0.8.9). Continue codex/geely-return-picture.
Package com.shihab.diplay, versionCode 132.

## Reported behavior and evidence

Owner confirms music progress keeps moving while guidance makes it inaudible,
and wheel volume still changes media with CarPlay in the foreground. Prior
0.8.8 session logs show local guidance ducking at 0.2, no priority speech/focus
loss messages during that session, and usage 12 on the navigation bus. This
supports checking gain/context policy, not treating the symptom as paused music.
It does not establish whether the HAL further ducks/mutes, iPhone audio has
already changed gain, or what current OEM volume-group selection is doing.

## Change

- Keep bus1_navigation_out and navigation usage 12, including manual mapping.
  Reduce extra app guidance attenuation from 0.2 to 0.5 in this receiver.
  Combine local guidance gain with system focus ducking using the smaller gain,
  rather than multiplying two reductions. Calls/Siri still mute media when
  speech arbitration requires it; external focus losses remain respected.
- On KX11 only, retain music's focus and make a separate ordinary
  AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK request using the track's actual navigation
  attributes while navigation PCM is playing. Abandon it when its PCM deadline
  ends, the stream closes, speech/capture/call takes priority or the sink closes.
  No request is made merely because a navigation stream was negotiated.
- One request per prompt even after denial. A focus loss does not trigger a
  reacquisition loop; a new quiet-to-speaking period can request again.
  Generation checks reject delayed callbacks from retired prompts.
- Never request navigation focus when a manual mapping changes guidance usage
  to media or another category. Phones retain the existing focus path.
- Add gain-change and navigation-focus diagnostics on transitions only.

This uses public audio focus to report the active navigation context to the
vehicle's policy. It does not capture/reinject wheel keys, start accessibility
services, force legacy STREAM_MUSIC to mean navigation, request system volume
permissions, modify stock apps or write CarAudio groups. The intent is for the
vehicle to select navigation volume during guidance and media afterward; actual
KX11 behavior is not proven by an offline request test.

References: AOSP automotive focus and volume management:
https://source.android.com/docs/automotive/audio/audio-focus
https://source.android.com/docs/automotive/audio/volume-management
Firmware policy can differ, particularly on this Android 11 head unit.

## Checks and remaining vehicle verification

Add six coordinator regressions: navigation-PCM-only focus and release; two
simultaneous duck sources; Siri priority and restoration; denied-focus bounds;
external call/manual mapping; and lost-focus/retired callbacks. Existing media,
navigation and clock tests must remain passing. Build/check results accompany
the local APK.

Parked verification: continuous music -> navigation prompt -> music remains
quietly audible -> turn wheel and check the OEM slider is navigation -> prompt
ends -> wheel changes media. Repeat across prompts, with CarPlay foreground and
background. Also check calls/Siri keep priority and media resumes correctly.
If OEM volume selection still stays on media, collect contemporaneous car audio
focus, playback and volume-group state via wireless debugging. Do not claim a
fixed wheel mapping, or overwrite system policies, based on successful builds.

## Completed offline result

All repository-required checks plus Geely tests/lint/standalone build pass.
Core 117 tests (including six new navigation-focus cases), app 62, shared 1654
(one existing skip), common 1502, home 6; zero failures/errors. Final build
413 tasks: 48 executed, 365 up-to-date. Final runtime/test files match build
inputs. APK package/name, selected auth assets, R8 exclusions and signature
verified. Signer matches 0.8.9. No head-unit wheel/slider or audible mix result
is claimed. APK SHA256: 33f3b2e6e90af00aa1be8feb0fb33bec95f1016c0dc66623dc78b332d4e063d0.
