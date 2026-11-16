# DiPlay Geely 0.8.7 — reduce diagnostic overhead

Baseline ad576e70 (0.8.6). Continue codex/geely-return-picture.
Package com.shihab.diplay, versionCode 129.

## Source-confirmed overhead and changes

Each recorded event used to compile seven fixed regular expressions and, after
writing, list and stat all diagnostic history files. The same redaction rules
are now compiled once per process and reused; match state remains per call.
Their text, order, replacements and known-secret redaction are unchanged.

Retention now scans on the first write, on each file rotation, and at most once
per minute of subsequent writes. The eight-file size cap is enforced on every
rotation; seven-day expiration is enforced on the next eligible write. No
maintenance thread, timer or background wakeup is introduced. Each operation
is still recorded asynchronously and each line is still written immediately;
crash writing and export flushing retain their existing behavior.

This reduces repeated regex construction/allocation and directory scanning. It
does not make measured claims about head-unit CPU percentage, RSS, launch time,
frame rate or battery use. No physical profiler is connected in this change.
It does not resolve unexplained multi-second audio arrival gaps by itself.

Keep the existing audio/video quality, rate, buffers, connection paths, full
operation logs and privacy redaction. No new dependency or permission.

## Verification

Existing stress coverage writes thousands of records through rotation and
checks the file cap and newest operation. Existing snapshot/redaction tests
check secrets, correlation and crash records. Add an age-retention regression
ensuring expired history is removed while recent history and new operations
remain. Run the repository-required checks and authenticated standalone APK
build; final counts and integrity checks accompany the local artifact.

Physical launch responsiveness and resource savings require a comparable
vehicle profiling run. Compare the same frame rate/quality and operation
sequence, including music plus navigation, returning home, reconnecting and
uploading the resulting logs. Do not treat a larger FPS setting as a resource
optimization.

## Completed result

Full required command passed: 413 tasks, 47 executed, 366 up-to-date.
Core 104 tests, Geely app 62, shared 1654 (one existing skip), common 1502,
home 6; zero failures/errors. Build inputs match the final source.
APK package/name, selected auth assets, R8 exclusions and signature verified.
Certificate matches 0.8.6; physical performance remains unmeasured.
APK SHA256: 8571ced4c056edd267250f8ef34a97da0f6819e3b9b5e8d7d5a66f805039dd62.
