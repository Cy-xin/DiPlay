# DiPlay Geely 0.8.1: controlled video-latency comparison

Baseline 680ffafe; branch codex/geely-video-latency. User reports whole-screen and touch-response slowdowns during application switching, not restricted to music. Physical car is unavailable for a new capture, so no current cause or repair success is claimed.

Historical KX11 captures identify c2.qti.avc.decoder. In activity windows (at least 20 received fps), 202 historical windows had median receive/display rates both 32.2fps, median per-window decode p50 37ms and p90 70ms. These logs predate the latest fixes and cannot establish the present bottleneck. Original docs/SMOOTH_WIRELESS.md describe the same codec retaining about two frames and the existing low-latency option. Their measured benefit on another vehicle is not evidence of benefit on this KX11.

## Changes

- Settings -> Audio & display -> Low-latency decoding (experimental), default OFF. Reuses original vendorLowLatencyDecoder switch: supported Qualcomm codec parameters and original dedicated output thread. No new codec, forced resolution/FPS change, invented protocol flag or extra display delay.
- Save applies next connection, never disconnects a running session. Disable to compare the original standard mode; reset audio mapping does not alter this setting.
- Existing configure fallback handles rejected tuned configurations. Runtime behavior on this firmware still needs testing; turning on the setting does not guarantee the parameters were accepted.
- Diagnostics now expose the decoder name, reported configured vendor parameters/output-thread mode, decode-input-to-output p50/p90, input-queue maximum wait and peak pending frames, and estimated time to a subsequent received frame after sending touch.
- Queue counters update in existing frame processing and report in existing five-second stats; no polling/background task added. They are per-window maxima, not accumulated backlog. Touch samples are not end-to-end UI latency and do not prove causal response to a specific touch.
- Audio routing from 0.8.0 and previous peer-specific Bluetooth audio handoff are preserved. The audio input/output workers were already independent of the video worker; music is not assumed to cause the slowdown.

## Next physical comparison

Use the same resolution, frame rate, audio mapping and network in both tests. First run standard mode, switch applications and drag maps for 30-60 seconds. Export diagnostics. Enable low-latency mode, reconnect, repeat the same actions and export again. Compare input-queue delay, decoder latency, receive/display cadence and user-perceived responsiveness. If only the optional mode gives abnormal pictures, disable it and reconnect.

No automatic calls, messages, system application changes or vehicle volume writes are needed. An offline build passing does not prove CarPlay smoothness or performance on the head unit.

## Completed checks

Required repository CI tasks, app lint/build and APK signature/package/R8/auth-asset verification passed. App 47 tests and core 62 tests passed, with zero failures; original shared/common checks passed. Added queue-window reset and invalid-sample tests, parsed diagnostic tests, and no-disconnect/default-off setting tests; reused original video operating-rate/vendor-key regression tests unchanged.

Emulator verified default OFF, toggle ON, retained selection after restart, 1280x720 and 720x1280 at font scale 1.3, and Arabic-locale RTL with existing Chinese fallback. Screenshots are local artifacts. No physical-car/Redmi installation or CarPlay performance claim is made.

APK SHA-256: bfc7010ef51c8c5e3652c5e74bdc817f3e286d03d3074d641184c5aff983264f. No new dependencies, privileged permissions or background polling.
