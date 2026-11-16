# DiPlay Geely 0.8.8 — restrained app motion

Baseline 4f1ce57a (0.8.7). Continue codex/geely-return-picture.
Package com.shihab.diplay, versionCode 130.

## Motion design and implementation

Keep the existing cool-blue glass palette, typography and layout. Introduce
finite, small-amplitude transitions inspired by system-app motion:

- Connection, settings and diagnostic pages enter over 260ms with a 12dp upward
  settle and opacity from 0.85 to 1. The cubic curve is (0.22, 0.8, 0.2, 1).
  Replace the old page immediately; animate only the incoming page so two live
  forms, click targets and their effects are not retained during a crossfade.
- Tab foreground and selected fill interpolate over 200ms.
- The primary action compresses to 0.985 while pressed, with a spring return
  (damping ratio 0.85, stiffness 550). The action still fires immediately on
  normal button release; connection/cancel requests are not delayed by animation.
  The original 56dp minimum remains above 48dp even at the compressed scale.
- Advanced connection options expand/collapse over 240ms, with short fades.
  Fields, permissions and input persistence retain their existing behavior.

Use the project's existing Compose animation APIs and system duration scaling;
no third-party animation dependency, per-frame timer or infinite decoration.
Page/press progress is read in graphicsLayer to avoid measuring the whole page
on every animation frame. Page effects are cancelled when their composition
leaves. CarPlay's transparent host branch bypasses these app-page animations;
its Surface is never faded, resized or blurred by this implementation.

## Validation boundary

Run repository-required checks plus Geely tests/lint and authenticated standalone
build. Physical Apple-like feel and sustained 60/120fps are not established by
compilation or unit tests. No device is connected and no configured emulator AVD
is available, so compact/landscape/large-font screenshots were not captured.
On-device visual QA should check rapid tab changes, advanced field reveal,
button release/cancel, system animations disabled, portrait/landscape and large
font size, plus CarPlay picture/touch/audio after leaving and returning.

## Completed offline result

All required checks and build tasks passed: 413 tasks, 49 executed and 364
up-to-date. Core 104 tests, Geely app 62, shared 1654 (one existing skip),
common 1502, home 6; zero failures/errors. Build input matches final source.
Package/name, local authentication assets, R8 exclusions and signature verified.
The signer matches 0.8.7. No physical frame-rate or visual QA result is claimed.
APK SHA256: 920d6ae0ef9a4209dd68ac01faa8f30c38f0a64452e97f5200e90015710e4412.
