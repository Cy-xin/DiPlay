# DiPlay Geely 0.8.4 — restore the picture after returning

Baseline: 3ad672a7 (0.8.3). Branch: codex/geely-return-picture.
Package com.shihab.diplay; versionCode 126.

## Observed problem

The owner-supplied 17.2-second vehicle video shows a return from the factory home through the launcher to DiPlay. After selecting Return to CarPlay at about 8 seconds, its picture stays black until about 17 seconds. Frame sampling bounds the visible gap at approximately eight seconds; it is not an exact presentation-latency measurement.

The matching uploaded session shows the Surface destroyed on backgrounding and recreated on return. After the owner selects CarPlay, the receiver continues to receive empty screen keepalives before pictures resume. It does not show a new connection attempt, a sustained decoder queue, or a decoder recovery during this interval. Connection retention and steady-state decoder statistics did not verify first-picture restoration.

The upstream CarPlayHostActivity explicitly calls refreshPicture after Surface creation and retries at one and two seconds while waiting for a picture. The Geely activity had preserved codec parking and setOutputSurface handoff but omitted the refresh calls.

## Change

Reuse the existing sink refreshPicture -> decoder job -> video-recovery-handler mechanism on Surface creation, return to the foreground and selecting Return to CarPlay. Queueing this control does not perform network I/O on the UI thread.

A small lifecycle helper coalesces repeated callbacks for the same visible Surface and requests at most an initial picture plus retries at one and two seconds. A new rendered frame cancels remaining retries; a replacement decoder starting its counter at zero is not mistaken for a visible frame. Returning to DiPlay, backgrounding, Surface destruction, disconnect and destruction stop pending retries. Add short picture_refresh_requested and picture_restored diagnostics; the latter reports the check time, not the exact display time.

This uses the retained session and upstream picture-refresh path, with the existing bounded detach handshake. It introduces no new background service or permanent polling.

## Validation

Required repository checks, Geely tests/lint and authenticated standalone build passed. Geely app: 61 tests; geely-core: 93; shared: 1654 (one existing skip); common: 1502; home: 6. Zero failures/errors.

Five deterministic recovery tests cover bounded retries, rendered-frame completion, counter reset, hidden-state cancellation and Surface replacement/coalescing. An activity regression exercises returning, keeping the session/generation and cancelling retries at the receiver home. Existing connection-retention and audio regressions also pass.

APK package, version, components, permissions, R8 trimming, local authentication-asset equality and signature verified. Certificate matches 0.8.3. The five changed runtime/test/build files matched the isolated build source byte-for-byte. Final APK SHA-256:

b11c986044047aab3e87452f93bd18a78be3dacaea6acfdc29ba0296824d46cb

## Remaining vehicle verification

The head unit is offline, so there is no measured post-fix vehicle result. Repeat the supplied video's steps: connect, leave to the factory home for at least ten seconds, reopen DiPlay and return to CarPlay, including while its home screen is otherwise still. Repeat several times and verify first picture, touch, continuous music and retained session. Upload a report if black-screen waiting remains; the new events distinguish requested refreshes and bounded checks.

The private video, extracted frames, raw diagnostic reports, authentication assets and APK are not part of the source commit.
