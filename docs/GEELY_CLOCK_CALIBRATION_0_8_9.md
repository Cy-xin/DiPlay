# DiPlay Geely 0.8.9 — correct burst-biased media clock control

Baseline ba349ff7 (0.8.8), continue codex/geely-return-picture.
Package com.shihab.diplay, versionCode 131.
Official audio reference: shihabal3amri/DiPlay commit
615b32ce0b40cd0b99ce94ba23a512001553684f (0.2.22 public preview).

## Evidence and scope

Report 58fd59a6, current 0.8.8 KX11_A2 session on 2026-11-17, had zero local
media packet drops/write errors but 19 rebuffer counter increments across two
renderers. 114 of 175 clock windows requested 48720Hz, the +1.5% bound.
Long receive gaps and RTP sequence discontinuities also remain. These facts do
not establish hardware clock drift or prove that the app caused every silence.

Before changing production code, add the official 400ms Wi-Fi burst regression
to the existing Geely controller. The default 300ms-buffer test fails against
0.8.8; the longer-buffer scenario passes. Keep this failed-run log privately as
reproduction evidence. The production fix must pass it without weakening its
no-starvation and near-48kHz assertions.

## Backport and vehicle safeguards

- Reuse upstream time-weighted draining-buffer estimation, instead of smoothing
  only the post-write peak. Its stale window is longer than ordinary 400ms bursts.
- Measure the steady target from two agreeing windows after the playback head
  starts moving. Startup threshold and user buffer configuration are unchanged;
  the measured steady level need not equal the startup threshold.
- Record packet arrival timestamps and account for frames that arrived while a
  blocking AudioTrack write held the renderer. Retain RTP wrap/reset handling.
  Ignore packets newer than the consumer's time snapshot, because a concurrent
  producer may append while the weakly consistent queue iterator is running.
- Re-measure after playback restarts and after local compressed/input drops.
  Cumulative drop counters are separate from resettable diagnostic windows.
- Keep the Geely PCM starvation floor tied to the original configured startup
  threshold. Never accelerate low PCM during calibration or steady control.
  Between writes, maintenance still only brakes and never learns silence as drift.
  An AudioTrack rejecting playback-rate changes still disables trim and continues
  playing. Clamp a measured zero target to one frame to avoid division by zero.
- Add measuredTargetMs and calibrating to existing bounded five-second clock
  diagnostics. No new polling/service, permission or dependency.
- Explain an explicit Turn on Bluetooth startup error as Bluetooth disabled/not
  ready, rather than a generic pairing failure. No automatic Bluetooth toggling.

## Regression coverage

Two official Wi-Fi burst simulations plus three calibration/PCM regressions and
two renderer integration regressions are added. Keep existing fast/slow-clock,
no-drift, stale-write, correction-bound, compressed-backlog and between-write
starvation tests. Renderer tests cover arrival-time accounting (including a
concurrent future arrival and RTP reset), restart and packet-loss recalibration.

## Remaining verification

This cannot fill seconds of audio that never reached the receiver. Do not claim
the six-second receive gap is fixed. Next parked vehicle run should compare
continuous music plus navigation at the same settings, checking audible gaps,
clock rate, calibration completion, measured target, rebuffer increments and
receive/sequence events. User pauses, track changes and renderer recreation
must be distinguished from transport failures. Physical sound, latency and
source-network causes still need validation; no head unit is connected here.

## Completed offline result

Before fix, the official default-buffer burst regression failed. After fix,
all 111 core tests and 62 Geely app tests pass, including seven added cases.
Shared 1654 tests (one existing skip), common 1502, home 6; zero failures/errors.
All required lint/assemble tasks and authenticated standalone build passed.
Final run: 413 tasks, 46 executed and 367 up-to-date. Build source matches
canonical runtime/test files. Package/name, assets, R8 and signer verified;
signature matches 0.8.8. The APK remains a local artifact, excluded from Git.
APK SHA256: 765986b0eb8ddcf1aa0708827d32deead0a2a68914651416e04a350f0249e4d0.
