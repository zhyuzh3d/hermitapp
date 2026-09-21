#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SIGNING_DIR=${HERMIT_SIGNING_DIR:-/Users/zhyuzh/.hermit-signing}
export HERMIT_KEYSTORE_PATH=${HERMIT_KEYSTORE_PATH:-$SIGNING_DIR/hermit-v1.keystore}
PASSWORD_FILE=${HERMIT_PASSWORD_FILE:-$SIGNING_DIR/hermit-v1.password}
test -f "$HERMIT_KEYSTORE_PATH" || { echo "Missing release keystore: $HERMIT_KEYSTORE_PATH" >&2; exit 1; }
test -f "$PASSWORD_FILE" || { echo "Missing release password file: $PASSWORD_FILE" >&2; exit 1; }
HERMIT_STORE_PASSWORD=$(tr -d '\r\n' < "$PASSWORD_FILE")
export HERMIT_STORE_PASSWORD
export HERMIT_KEY_PASSWORD=$HERMIT_STORE_PASSWORD
export HERMIT_KEY_ALIAS=${HERMIT_KEY_ALIAS:-hermit-v1}
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
VERSION_NAME=${HERMIT_VERSION_NAME:-$(sed -n 's/.*versionName.*?: "\([^"]*\)"/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)}
VERSION_CODE=${HERMIT_VERSION_CODE:-$(sed -n 's/.*versionCode.*?: \([0-9][0-9]*\)/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)}
test -n "$VERSION_NAME" || { echo "Cannot determine versionName" >&2; exit 1; }
test -n "$VERSION_CODE" || { echo "Cannot determine versionCode" >&2; exit 1; }
cd "$ROOT"
"$ROOT/gradlew" :app:assembleRelease -PhermitVersionName="$VERSION_NAME" -PhermitVersionCode="$VERSION_CODE"
