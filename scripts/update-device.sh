#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK_DIR=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
ADB=$SDK_DIR/platform-tools/adb
AAPT=$SDK_DIR/build-tools/37.0.0/aapt
APKSIGNER=$SDK_DIR/build-tools/37.0.0/apksigner
APK=$ROOT/app/build/outputs/apk/release/app-release.apk
VERSION_NAME=${HERMIT_VERSION_NAME:-$(sed -n 's/.*versionName.*?: "\([^"]*\)"/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)}
VERSION_CODE=${HERMIT_VERSION_CODE:-$(sed -n 's/.*versionCode.*?: \([0-9][0-9]*\)/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)}

test -x "$ADB" || { echo "Missing adb: $ADB" >&2; exit 1; }
test -x "$AAPT" || { echo "Missing aapt: $AAPT" >&2; exit 1; }
test -x "$APKSIGNER" || { echo "Missing apksigner: $APKSIGNER" >&2; exit 1; }
test -n "$VERSION_NAME" && test -n "$VERSION_CODE" || { echo "Cannot determine APK version" >&2; exit 1; }

DEVICE_COUNT=$($ADB devices | awk 'NR>1 && $2=="device" {n++} END {print n+0}')
test "$DEVICE_COUNT" -eq 1 || { echo "Exactly one ready Android device is required" >&2; exit 1; }

NEEDS_BUILD=1
if test -f "$APK"; then
  BADGING=$($AAPT dump badging "$APK")
  if printf '%s\n' "$BADGING" | grep -q "versionName='$VERSION_NAME'" &&
     printf '%s\n' "$BADGING" | grep -q "versionCode='$VERSION_CODE'" &&
     ! find "$ROOT/app/src" "$ROOT/app/build.gradle.kts" "$ROOT/build.gradle.kts" "$ROOT/settings.gradle.kts" "$ROOT/gradle.properties" -newer "$APK" -print -quit | grep -q .; then
    NEEDS_BUILD=0
  fi
fi

if test "$NEEDS_BUILD" -eq 1; then
  HERMIT_VERSION_NAME=$VERSION_NAME HERMIT_VERSION_CODE=$VERSION_CODE "$ROOT/scripts/build-release.sh"
else
  echo "Reusing unchanged release APK $VERSION_NAME ($VERSION_CODE)."
fi

$APKSIGNER verify "$APK"
BADGING=$($AAPT dump badging "$APK")
printf '%s\n' "$BADGING" | grep -q "package: name='io.github.zhyuzh3d.hermit'"
printf '%s\n' "$BADGING" | grep -q "versionName='$VERSION_NAME'"
printf '%s\n' "$BADGING" | grep -q "versionCode='$VERSION_CODE'"
$ADB install -r "$APK"
$ADB shell am force-stop io.github.zhyuzh3d.hermit
START_RESULT=$($ADB shell am start -W -n io.github.zhyuzh3d.hermit/.MainActivity)
printf '%s\n' "$START_RESULT"
printf '%s\n' "$START_RESULT" | grep -q 'Status: ok' || { echo "HermitApp did not start successfully" >&2; exit 1; }
test -n "$($ADB shell pidof io.github.zhyuzh3d.hermit | tr -d '\r')" || { echo "HermitApp process is not running" >&2; exit 1; }
$ADB shell dumpsys package io.github.zhyuzh3d.hermit | grep -m1 "versionCode=$VERSION_CODE"
echo "Updated device with Hermit $VERSION_NAME ($VERSION_CODE)."
