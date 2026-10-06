#!/usr/bin/env bash
# Regenerates the built-in language-name clips from the project's Piper voices (emulator only).
# Needs the app and test APKs installed and the packs pushed (push_test_packs.sh).
# Output: AAC in .m4a, which every supported Android version (minSdk 26) plays.
set -euo pipefail
serial="$1"
case "$serial" in emulator-*) ;; *) echo "emulator only" >&2; exit 2 ;; esac
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/android/app/src/main/res/raw"
tmp="$(mktemp -d)"
mkdir -p "$out" "$root/.local/receipts"
for pair in fr:fr en-US:en; do
  tag="${pair%%:*}"
  name="${pair##*:}"
  python3 "$root/android/scripts/run_instrumentation.py" --serial "$serial" \
    --classes org.phonemesh.app.NameClipExport --expected 1 --args "clip=$tag" \
    --receipt "$root/.local/receipts/name-clip-$tag.txt"
  adb -s "$serial" pull "/sdcard/Android/data/org.phonemesh.app/files/name_$tag.wav" "$tmp/name_$tag.wav"
  ffmpeg -nostdin -loglevel error -y -i "$tmp/name_$tag.wav" -ac 1 -ar 22050 -c:a aac -b:a 32k \
    -movflags +faststart "$out/name_$name.m4a"
  ls -l "$out/name_$name.m4a"
done
rm -rf "$tmp"
