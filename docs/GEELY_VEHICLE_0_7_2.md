# KX11_A2 direct diagnosis and 0.7.2 candidate

Baseline: `b2f8c07d`; branch `codex/geely-vehicle-audio-fix`. Physical head unit KX11_A2, Android API 30, 1920x720 / 160 dpi; current app bounds exclude the 120px OEM rail (1800x720). No original system app or account was modified.

## Evidence

- Read-only system audio focus history does not show a repeated DiPlay focus-loss cycle. Most collected music windows show no packet drops, no underruns and no AudioTrack write errors. This rules out claiming a simple underrun cause; it does not prove the perceived pauses are fixed.
- The paired iPhone still has a Connected native A2DP sink. The minimal controller had replaced original `configureBluetoothAudioHandoff` with a no-op. Recovered the existing original function, using KX11 model detection instead of restoring the removed OEM factory bridge. Existing peer-only BluetoothAudioHandoff implementation is unchanged; onPlaying is null, so its playback polling is not started. The original bounded reconnect/cleanup remains.
- Video records show actual decode canvas 1800x720; dynamic activity can approach 47fps, while idle windows drop naturally. The app supplied `videoFps`, which is unused in this sink, but omitted `mainVideoFrameRate`, the original decoder operating-rate input. Now passes both to preserve existing constructor compatibility. No speculative decoder tuning or new codec is enabled.
- OEM dpi160 made 14sp labels physically small on the wide head unit. KX11-only Compose density is multiplied by 1.35, preserving system font scaling; SurfaceView geometry and CarPlay touch coordinates remain unchanged. Phone layouts are unaffected.
- The earlier upload failed with SocketException/connection timeout. A later upload receipt became visible. Added an explicit local sanitized diagnostic export in app-specific external storage, retained at most one file, so direct ADB diagnosis does not depend on public network access. Authentication assets and upload credentials are not exported.

## Verification

The diagnostic helper build 0.7.1 passed app tests/lint, required repository checks and signature verification; installed with data preserved. Its local export was used to read 10,227 correlated head-unit events. Actual export size approximately 288KB gzip. Private logs/screenshots remain under /private/tmp/geely-car-debug; never commit these or user content.

The 0.7.2 candidate must be installed and tested with the same iPhone: repeatedly play music without tapping pause, observe at least 30 seconds, then test navigation with music, and return/home continuity. If platform rejects peer A2DP control, the helper reports fallback rather than requesting root/system privileges. Do not label the conflict hypothesis confirmed until this controlled test succeeds.

CarPlay dock auto-hide is not implemented: dock pixels and layout are drawn by iOS; do not crop them or remap touch coordinates to imitate native hiding. Existing dock position setting is preserved.

R8 verification now permits only BluetoothAudioHandoff (and its nested classes) under the otherwise excluded vehicle namespace, because this peer handoff is required session infrastructure, not an OEM integration feature.

## Physical follow-up

0.7.2 (versionCode 121) installed with data preserved. The same KX11_A2 reconnected and showed CarPlay music playing. Bluetooth dump changed from one connected A2DP sink peer before the fix to Devices Tracked = 0 while CarPlay remained active. The owner explicitly confirmed continuous music playback without the previous repeated pauses. Navigation-plus-music and longer driving sessions remain separate checks; the effect on map interaction is pending user feedback. Actual head-unit screenshots confirmed the 35% larger DiPlay controls; iOS-drawn CarPlay UI was not scaled or cropped.

All required repository checks, 41 app tests and 53 core tests passed; APK signature/authentication assets/retained component checks passed. APK SHA-256: ebf05ea8aea61faef0f6fbf84821df9673d0d4bc8e007d09915bb6261313e2cf.
