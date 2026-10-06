#!/usr/bin/env bash
# Full pre-commit gate. Never commit unless this exits 0.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
uv run ruff check src tests scripts android/scripts
uv run ruff format --check src tests scripts android/scripts
uv run pytest -q
cd android
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew --no-daemon --console plain \
  :core:test :radio-ble:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest
echo CHECK_OK
