# DiPlay Geely 0.8.5 — targeted upstream stability backports

Local baseline: b1df279ed1d18547180b99c979b77869f77729bc (0.8.4).
Development branch: codex/geely-return-picture (continued in place). Package: com.shihab.diplay; versionCode 127.
Official reference: shihabal3amri/DiPlay main, pinned to
615b32ce0b40cd0b99ce94ba23a512001553684f (README: 0.2.22 public preview).
Historical tagged baseline: v0.2.21, 49bf3bc2b011cb5ee7c7f4da9b99b18cbd3a23a2.

## Audit method

Compare the pinned official source, the retained shared/common fork, and geely-core.
The local shared/common directories contain historical modifications and are not
an untouched official checkout. Equality with them alone is not evidence of
being current with upstream. No wholesale merge or untested replacement of the
working vehicle adaptations is performed.

## Adopted

- ScreenStream accepts a TCP video sender only when its address matches the
  current control-session iPhone. An unrelated connection is closed and the
  listener keeps accepting. IPv4-mapped and IPv6 cases use Java address equality,
  as in upstream. CarPlayMediaEngine passes the existing remoteAddress; a
  session without an address cannot start a video listener.
- Drop video bodies shorter than the authentication tag before decoding or
  incrementing the nonce counter. The next valid sealed frame still decrypts
  with the correct counter. ScreenCodec no longer treats a short body as clear
  video. Valid codec configuration messages retain their existing path.
- A first-rendered-frame callback marks connection progress only for the current
  main-screen stream, preventing a superseded stream from reporting success.
- NtpClock publishes the selected peer across threads and accepts timing
  responses only from that peer address, while retaining the original timing
  exchange and port flexibility.

These are upstream recovery/isolation safeguards, not measured throughput or
frame-rate gains. They cannot by themselves explain or eliminate radio loss.

## Retained and deferred

- Keep the verified Geely original-Bluetooth music handoff, navigation ducking,
  configurable audio usages/buses, channel tests, PCM starvation guard, bounded
  video queues and the 0.8.4 foreground picture-refresh recovery.
- Upstream MediaClockTrim now averages the draining buffer between Wi-Fi bursts
  and measures its steady playback target. It needs coordinated renderer arrival
  accounting and compatibility tests with our PCM protection; copying only its
  controller or STALE_NS constant is insufficient. It is intentionally not
  included in this release. This release does not claim new audio-delay tuning.
- Upstream TcpLiveness changes mainly affect API 26–28; our minimum is API 30.
  No backport is needed for that path. Do not add unrelated USB, Wi-Fi Direct,
  BYD SDK, HUD, ambient lighting or vehicle-data startup dependencies.
- No new dependencies, UI, background polling, system-app modification or
  permission changes.

## Regression checks

Port upstream ScreenStreamPeerTest and NtpClockPeerTest, adjusting Robolectric
SDK to the project's API 30 minimum. Add a regression that sends a short frame
followed by a valid encrypted frame on the same connection, checking that only
one decoded frame arrives and the nonce remains correct. Network tests require
IPv6 loopback and explicitly skip if unavailable; report skips in verification.

Run all AGENTS.md checks plus geely-core/geely tests, Geely lint and the
standalone authenticated build in an isolated build directory. Keep private
assets and APK out of source control. Verification results accompany the APK.

## Vehicle verification still required

No head unit is online for this change. Offline tests do not establish actual
first pairing, continuous sound, touch responsiveness or improved performance.
At the next parked test: verify both connection modes, play music with navigation,
leave to the factory home and return repeatedly, and verify picture/touch/sound
and connection retention. Upload the session report if any problem remains.

## Completed offline result

Full required command succeeded: 413 tasks, 64 executed and 349 up-to-date.
Geely core 103 tests, Geely app 61, shared 1654 (one existing skip), common 1502,
home 6; zero failures/errors. All ten added peer/frame regressions ran without
skips. Required lint and all requested assemble tasks passed. Final changed
runtime, test and version files match the isolated build input byte for byte.

APK verification passed for package/name, minimum API 30, component and
permission limits, R8 exclusions and equality with the selected local auth
assets. Signature matches 0.8.4. Final APK SHA-256:
1b836ba977a6134266cfe165d76d2e425adf11ba3d4bf16fde04bc1549d1c414.

The APK and private assets are local deliverables only and are not committed.
