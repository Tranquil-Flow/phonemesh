#!/usr/bin/env bash
# Push pack ZIPs into the app's external files dir on an emulator (never a phone).
set -euo pipefail
serial="$1"; shift
case "$serial" in emulator-*) ;; *) echo "emulator only" >&2; exit 2 ;; esac
dest=/sdcard/Android/data/org.phonemesh.app/files
adb -s "$serial" shell mkdir -p "$dest"
for zip in "$@"; do adb -s "$serial" push "$zip" "$dest/"; done
