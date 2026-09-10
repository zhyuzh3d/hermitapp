#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/java}
if [ -z "${JAVA_BIN:-}" ] || [ ! -x "$JAVA_BIN" ]; then JAVA_BIN=/opt/homebrew/opt/openjdk@17/bin/java; fi
SDK_DIR=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
echo "workspace=$ROOT"
"$JAVA_BIN" -version
"$ROOT/gradlew" --version
node --version
npm --version
"$SDK_DIR/platform-tools/adb" version
test -f "$SDK_DIR/platforms/android-37.0/android.jar"
test -x "$SDK_DIR/build-tools/37.0.0/apksigner"
echo "Hermit build environment is ready."
