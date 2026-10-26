#!/usr/bin/env bash
# Build the debug APK, install it on the connected device (phone or emulator),
# and launch it. Starts the emulator first if nothing is connected.
#   scripts/run.sh            build, install, launch
#   scripts/run.sh -s SERIAL  pick a device when several are connected
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
AVD="${AVD:-Medium_Phone_API_37.0}"
# Studio's bundled JDK (25) is too new for Gradle 8.14; use the JDK 21 Studio set up for this project.
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"

if [ "${1:-}" = "-s" ]; then export ANDROID_SERIAL="$2"; fi

if ! "$ADB" get-state > /dev/null 2>&1; then
    echo "No device connected; starting emulator $AVD"
    "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot-save > /dev/null 2>&1 &
    "$ADB" wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'
fi

./gradlew -q :starfront:installDebug
"$ADB" shell am start -S -n com.androidex.starfront/.MainActivity > /dev/null
echo "Launched Starfront TD on $("$ADB" shell getprop ro.product.model | tr -d '\r')"
