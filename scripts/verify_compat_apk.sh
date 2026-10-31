#!/usr/bin/env bash
# Verifies that a built APK really is the Geely/OneOS compatibility package.
#
# This vehicle's launcher lists media-card packages by exact name and its OnlineMusicService
# addresses two fixed component names inside io.dushu.car. A single wrong character produces an
# APK that installs cleanly and then silently does nothing, so every claim is checked here rather
# than trusted from the build log.
#
# Usage: scripts/verify_compat_apk.sh <path-to-apk>
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "usage: $0 <path-to-apk>" >&2
  exit 2
fi

EXPECTED_PACKAGE="io.dushu.car"
RECEIVER="io.dushu.car.receiver.MediaControlReceiver"
ACTIVITY="io.dushu.car.mvp.view.TransparentActivity"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "ANDROID_HOME is not set" >&2
  exit 2
fi
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
AAPT2="$BUILD_TOOLS/aapt2"
if [[ ! -x "$AAPT2" ]]; then
  echo "aapt2 not found under $BUILD_TOOLS" >&2
  exit 2
fi

failures=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; failures=$((failures + 1)); }

manifest="$(mktemp)"
entries="$(mktemp)"
trap 'rm -f "$manifest" "$entries"' EXIT
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$manifest" 2>/dev/null
# Read the listing once: piping unzip into `grep -q` closes the pipe early, and under pipefail
# that SIGPIPE would be reported as a missing asset.
unzip -l "$APK" > "$entries" 2>/dev/null || true

# Prints the attribute block of the first node whose android:name matches $1.
node_attributes() {
  grep -A8 "android:name(0x01010003)=\"$1\"" "$manifest" | sed -n '2,8p'
}
has_attr() {
  node_attributes "$1" | grep -q "android:$2(0x[0-9a-f]*)=$3"
}

echo "== $(basename "$APK") =="

actual_package="$("$AAPT2" dump badging "$APK" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
if [[ "$actual_package" == "$EXPECTED_PACKAGE" ]]; then
  pass "applicationId is exactly $EXPECTED_PACKAGE"
else
  fail "applicationId is '$actual_package', expected '$EXPECTED_PACKAGE' (a .hudtest suffix makes the package inert)"
fi

has_attr "$RECEIVER" enabled true && pass "MediaControlReceiver enabled" || fail "MediaControlReceiver is not enabled"
has_attr "$RECEIVER" exported true && pass "MediaControlReceiver exported" || fail "MediaControlReceiver is not exported"
has_attr "$ACTIVITY" enabled true && pass "TransparentActivity enabled" || fail "TransparentActivity is not enabled"
has_attr "$ACTIVITY" exported true && pass "TransparentActivity exported" || fail "TransparentActivity is not exported"

# Without the accessory identity the package installs and runs but cannot complete CarPlay setup,
# which looks like a broken build during a vehicle test.
for asset in identity.pk8 certificate.p7b; do
  if grep -q "assets/offline-mfi/$asset" "$entries"; then
    pass "assets/offline-mfi/$asset present"
  else
    fail "assets/offline-mfi/$asset missing (build without DIPLAY_AUTH_ASSETS_DIR cannot connect to an iPhone)"
  fi
done

echo
if (( failures == 0 )); then
  echo "RESULT: PASS"
  exit 0
fi
echo "RESULT: FAIL ($failures check(s))"
exit 1
