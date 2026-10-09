#!/usr/bin/env bash
# Offline step 1 how-to clips from the project's Piper voices. Never records or plays audio.
# Requires both minifiedDebug APKs and push_test_packs.sh; ONLY the approved emulator.
# BITRATE defaults to 24k. All four clips must fit 30,000 B before replacing assets.
set -euo pipefail
serial="${1:-}"
case "$serial" in emulator-5584) ;; *) printf '%s\n' 'Only emulator-5584 is allowed' >&2; exit 2 ;; esac
bitrate="${BITRATE:-24k}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/android/app/src/main/res/raw"
receipts="$root/.local/receipts"
waves="$receipts/vg-task10-howto-wav"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out" "$waves"
trim="silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse"
for pair in fr:fr en-US:en; do
  tag="${pair%%:*}"
  name="${pair##*:}"
  python3 "$root/android/scripts/run_instrumentation.py" --serial "$serial" \
    --classes org.phonemesh.app.HowToClipExport --expected 1 --args "howto=$tag" \
    --receipt "$receipts/howto-clips-$tag.txt"
  for scene in download friend; do
    wav="$waves/howto_${tag}_$scene.wav"
    adb -s "$serial" pull "/sdcard/Android/data/org.phonemesh.app/files/howto_${tag}_$scene.wav" "$wav"
    ffmpeg -nostdin -loglevel error -y -i "$wav" -af "$trim" -ac 1 -ar 22050 -c:a aac -b:a "$bitrate" \
      -movflags +faststart "$tmp/howto_${name}_$scene.m4a"
  done
done
python3 -c 'import pathlib, sys; clips = list(pathlib.Path(sys.argv[1]).glob("howto_*.m4a")); total = sum(p.stat().st_size for p in clips); print(f"HOWTO_CLIPS {len(clips)} TOTAL_BYTES {total}", flush=True); assert len(clips) == 4 and all(p.stat().st_size > 1000 for p in clips) and total <= 30000, "how-to clip budget/size failed; assets not replaced"' "$tmp"
for clip in "$tmp"/howto_*.m4a; do
  mv "$clip" "$out/"
done
printf 'HOWTO_CLIPS_OK bitrate=%s\n' "$bitrate"
