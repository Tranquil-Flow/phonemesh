#!/usr/bin/env bash
# Full pre-commit gate. Never commit unless this exits 0.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
uv run ruff check src tests scripts android/scripts hub-ble-bridge-linux
uv run ruff format --check src tests scripts android/scripts hub-ble-bridge-linux
uv run --extra radio --group packs pytest -q
uv run python -I android/scripts/test_check_network_boundary_hub.py
cd android
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
"$JAVA_HOME/bin/java" -version
./gradlew --no-daemon --max-workers=1 --console plain \
  :core:test :hub:test :radio-ble:testDebugUnitTest :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleMinifiedDebug :app:assembleMinifiedDebugAndroidTest :app:checkApkSize :app:checkNetworkBoundary \
  :app:testDemoUnitTest :app:assembleDemo :hub:distZip
# The Linux Bluetooth bridge, its service names checked against the vectors core:test just wrote.
PM_GATT_VECTORS="$root/android/core/build/gatt-vectors/service-uuids.txt" \
  uv run --no-project --directory "$root/hub-ble-bridge-linux" python -I -m unittest discover -s tests -t .
echo CHECK_OK
