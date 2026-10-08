#!/usr/bin/env bash
# Offline welcome clips from the project's Piper voices. Never records or plays audio.
# Requires both minifiedDebug APKs and push_test_packs.sh; ONLY the approved emulator.
# BITRATE defaults to 24k. All twelve clips must fit 90,000 B before replacing assets.
set -euo pipefail
serial="${1:-}"
case "$serial" in emulator-5584) ;; *) printf '%s\n' 'Only emulator-5584 is allowed' >&2; exit 2 ;; esac
bitrate="${BITRATE:-24k}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/android/app/src/main/res/raw"
receipts="$root/.local/receipts"
waves="$receipts/vg-task6-caption-wav"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out" "$waves"
trim="silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse"
for pair in fr:fr en-US:en; do
  tag="${pair%%:*}"
  name="${pair##*:}"
  python3 "$root/android/scripts/run_instrumentation.py" --serial "$serial" \
    --classes org.phonemesh.app.CaptionClipExport --expected 1 --args "captions=$tag" \
    --receipt "$receipts/caption-clips-$tag.txt"
  for i in 0 1 2 3 4 5; do
    wav="$waves/caption_${tag}_$i.wav"
    adb -s "$serial" pull "/sdcard/Android/data/org.phonemesh.app/files/caption_${tag}_$i.wav" "$wav"
    ffmpeg -nostdin -loglevel error -y -i "$wav" -af "$trim" -ac 1 -ar 22050 -c:a aac -b:a "$bitrate" \
      -movflags +faststart "$tmp/caption_${name}_$i.m4a"
  done
done
python3 -c 'import pathlib, sys; clips = list(pathlib.Path(sys.argv[1]).glob("caption_*.m4a")); total = sum(p.stat().st_size for p in clips); print(f"CAPTION_CLIPS {len(clips)} TOTAL_BYTES {total}", flush=True); assert len(clips) == 12 and all(p.stat().st_size > 1000 for p in clips) and total <= 90000, "caption clip budget/size failed; assets not replaced"' "$tmp"
for clip in "$tmp"/caption_*.m4a; do
  mv "$clip" "$out/"
done
printf 'CAPTION_CLIPS_OK bitrate=%s\n' "$bitrate"
