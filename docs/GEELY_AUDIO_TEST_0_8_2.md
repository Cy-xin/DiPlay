# DiPlay Geely 0.8.2 — five-second audio tests

Branch: codex/geely-audio-tests. Baseline: a3b801f7.

Settings -> Audio & display -> Audio output mapping now includes an explicit five-second test for media, navigation, calls, assistant/Siri and ringtone. Tests immediately use the saved routing choice and usage classification. CarPlay sessions still apply mapping changes at their next connection.

Each signal has 240,000 mono PCM frames at 48kHz, five faded pulses, peak about -22dBFS. System volume is never written. Physical loudness still depends on the car's volume, so start low. Only one finite test worker may run. Stop, leaving the mapping page, application background/destruction, starting a CarPlay connection or losing audio focus cancels playback and releases AudioTrack/focus. Tests are disabled while CarPlay is connecting or connected. No calls, Siri commands, microphone recording, system audio policy writes, new service or permanent polling are introduced.

The status shows requested and actually reported output; route mismatch/fallback is logged as WARN and failure as ERROR. A detected bus or accepted preferred-device request is not proof of independent physical volume control. Calls/ringtone still share the car's captured volume group.

Required repository checks, 55 app tests, 62 core tests, app lint/build, signature, package/component/R8/auth-asset verification passed. Native AudioTrack completion after five seconds, immediate stop and leaving-settings cancellation were exercised on the emulator; a manually selected navigation route was reported as the actual navigation output. Wide/compact/RTL large-font layouts checked. Physical head-unit loudness/volume-slider behavior and real CarPlay calls/Siri are not verified by these tones. No physical device install performed in this task.

APK SHA-256: 8647fc402f99c37167b5dbafebf8122ccedf19b59a086e6f5404b8639a6ed463.
