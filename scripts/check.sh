#!/usr/bin/env bash
# Full pre-commit gate. Never commit unless this exits 0.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
uv run ruff check src tests scripts android/scripts
uv run ruff format --check src tests scripts android/scripts
uv run --extra radio --group packs pytest -q
uv run python -I android/scripts/test_check_network_boundary_hub.py
cd android
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
"$JAVA_HOME/bin/java" -version
./gradlew --no-daemon --max-workers=1 --console plain \
  :core:test :hub:test :radio-ble:testDebugUnitTest :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleMinifiedDebug :app:assembleMinifiedDebugAndroidTest :app:checkApkSize :app:checkNetworkBoundary \
  :app:testDemoUnitTest :app:assembleDemo :hub:distZip
echo CHECK_OK
