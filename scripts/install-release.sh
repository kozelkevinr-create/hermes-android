#!/usr/bin/env bash
# Builds the signed release APK and updates the app on a connected phone in place (data kept).
# Needs keystore.properties (gitignored) so the signature matches the installed app, and a
# JDK on PATH or JAVA_HOME (Gradle's own downloaded JDK under ~/.gradle/jdks also works).
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null; then
    JAVA_HOME=$(ls -d "$HOME"/.gradle/jdks/*/ 2>/dev/null | head -1 || true)
    [ -n "$JAVA_HOME" ] && export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
fi

[ -f keystore.properties ] || { echo "keystore.properties missing: the update would be unsigned" >&2; exit 1; }

ADB=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}

# Wireless debugging can list the same phone twice; pick the first physical device's transport.
SERIAL_ARGS=()
if [ -n "${ANDROID_SERIAL:-}" ]; then
    SERIAL_ARGS=(-s "$ANDROID_SERIAL")
else
    # Match on the whole line: a duplicate mDNS entry is named "...(2)._adb-tls-connect._tcp", with a space.
    TRANSPORT=$("$ADB" devices -l | awk '!/^emulator/ && / device / && match($0, /transport_id:[0-9]+/) { print substr($0, RSTART + 13, RLENGTH - 13); exit }')
    [ -n "$TRANSPORT" ] || { echo "no phone connected over adb" >&2; exit 1; }
    SERIAL_ARGS=(-t "$TRANSPORT")
fi

./gradlew :app:assembleRelease
"$ADB" "${SERIAL_ARGS[@]}" install -r app/build/outputs/apk/release/app-release.apk
