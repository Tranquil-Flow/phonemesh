#!/usr/bin/env bash
# Builds the test-only screenshot variant (no FLAG_SECURE), captures every screen state on the
# emulator into docs/screenshots-phase3/, then puts the normal secure build back. Emulator only.
set -euo pipefail
serial="${1:?usage: capture_screenshots.sh emulator-SERIAL}"
case "$serial" in emulator-*) ;; *) echo "emulator only" >&2; exit 2 ;; esac
root="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
install_build() {
  (cd "$root/android" && ./gradlew --no-daemon --console plain "$@" :app:assembleMinifiedDebug :app:assembleMinifiedDebugAndroidTest)
  adb -s "$serial" install -r "$root/android/app/build/outputs/apk/minifiedDebug/app-arm64-v8a-minifiedDebug.apk"
  adb -s "$serial" install -r "$root/android/app/build/outputs/apk/androidTest/minifiedDebug/app-minifiedDebug-androidTest.apk"
}
# The secure build goes back on the emulator even when the capture fails.
restore() { install_build; }
trap restore EXIT
install_build -Pphonemesh.screenshots=true
python3 "$root/android/scripts/run_instrumentation.py" --serial "$serial" --classes org.phonemesh.app.ScreenshotTour \
  --expected 1 --receipt "$root/.local/receipts/ui-screenshots.txt"
mkdir -p "$root/docs/screenshots-phase3"
rm -f "$root/docs/screenshots-phase3/"*.png
adb -s "$serial" pull /sdcard/Android/data/org.phonemesh.app/files/screenshots-phase3/. "$root/docs/screenshots-phase3/"
ls -l "$root/docs/screenshots-phase3"
