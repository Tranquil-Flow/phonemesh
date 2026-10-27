#!/usr/bin/env bash
# Phonemesh Demo on a laptop (macOS or Linux): an Android emulator in a folder you own, with the demo app installed.
#
#   ./start-demo.sh                 set up if needed (asks before downloading), start, install, open the demo
#   ./start-demo.sh --apk FILE      use a demo APK you already have instead of downloading the latest release
#                                   (a demo APK in this script's folder is used without asking, checked
#                                   against a SHA256SUMS file next to it when there is one)
#   ./start-demo.sh stop            stop the emulator
#   ./start-demo.sh remove          delete everything this script made
#
# Options: --yes (no questions), --no-mic (no microphone or sound), --headless (no window), --version vX.Y.Z
# Folder: $PHONEMESH_DEMO_HOME, else ~/.cache/phonemesh-demo-laptop. Nothing is installed system-wide and nothing
# runs as root. Downloads come from Google (emulator, adb, Android 15) and GitHub (the demo app), and are checked
# against the checksums pinned below (Google's own manifest) and the release's SHA256SUMS.
set -euo pipefail

HOME_DIR="${PHONEMESH_DEMO_HOME:-$HOME/.cache/phonemesh-demo-laptop}"
SDK="$HOME_DIR/sdk"
AVD_NAME="PhonemeshDemo"
REPO="Tranquil-Flow/phonemesh"
PACKAGE="org.phonemesh.app.demo"
GOOGLE="https://dl.google.com/android/repository"

# Pinned from https://dl.google.com/android/repository/repository2-3.xml and sys-img/google_apis/sys-img2-3.xml
# (2026-10-26). Format: file size sha1.
PT_MAC="platform-tools_r37.0.1-darwin.zip 16110554 6ae73f4de6452dc57e62ec02b68eed92a4c21661"
PT_LINUX="platform-tools_r37.0.1-linux.zip 9054187 477254aa5f903c15cf51001717bdf347fb6b53e0"
EMU_MAC_ARM="emulator-darwin_aarch64-16428233.zip 416112708 3af4fe44ce82b3d88ae5678a53735f27ad729c15"
EMU_MAC_X64="emulator-darwin_x64-16428233.zip 488731099 0c08fc22f41f2e63976f5503d593f821ff11e30b"
EMU_LINUX_X64="emulator-linux_x64-16428233.zip 349654171 cd7362ea55dfb86a418958138dc396e74165dd01"
IMG_ARM="arm64-v8a-35_r09.zip 1778933980 16f5bceca236b2737008977c4aaf826e46a8de7d"
IMG_X64="x86_64-35_r09.zip 1738815903 0103e6dab21290c4b9d16550a3ce99476f884eef"

say() { printf '%s\n' "$*"; }
die() { printf 'Stopped: %s\n' "$*" >&2; exit 1; }

COMMAND=start; APK=""; YES=0; MIC=1; WINDOW=1; VERSION=latest
while [ $# -gt 0 ]; do
  case "$1" in
    start|stop|remove) COMMAND="$1" ;;
    --apk) APK="${2:?--apk needs a file}"; shift ;;
    --yes) YES=1 ;;
    --no-mic) MIC=0 ;;
    --headless) WINDOW=0 ;;
    --version) VERSION="${2:?--version needs a tag}"; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) die "unknown option $1 (try --help)" ;;
  esac
  shift
done

[ "$(id -u)" != 0 ] || die "do not run this as root."

OS="$(uname -s)"; ARCH="$(uname -m)"
case "$OS/$ARCH" in
  Darwin/arm64) PT="$PT_MAC"; EMU="$EMU_MAC_ARM"; IMG="$IMG_ARM"; ABI=arm64-v8a; CPU=arm64 ;;
  Darwin/x86_64) PT="$PT_MAC"; EMU="$EMU_MAC_X64"; IMG="$IMG_X64"; ABI=x86_64; CPU=x86_64 ;;
  Linux/x86_64) PT="$PT_LINUX"; EMU="$EMU_LINUX_X64"; IMG="$IMG_X64"; ABI=x86_64; CPU=x86_64 ;;
  Linux/*) die "Google makes the Android emulator for Linux on x86_64 only; this computer is $ARCH." ;;
  *) die "this script is for macOS and Linux; on Windows use start-demo.ps1." ;;
esac

ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK" ANDROID_AVD_HOME="$HOME_DIR/avd" ANDROID_EMULATOR_HOME="$HOME_DIR/home"

sha() { if command -v shasum >/dev/null; then shasum -a "$1" "$2" | cut -d' ' -f1; else "sha${1}sum" "$2" | cut -d' ' -f1; fi; }
ask() { [ "$YES" = 1 ] && return 0; printf '%s [y/N] ' "$1"; read -r answer; [ "$answer" = y ] || [ "$answer" = Y ]; }
mb() { echo $(( $1 / 1000000 )); }

serial() { "$ADB" devices 2>/dev/null | awk '/^emulator-/{print $1}' | while read -r s; do
  [ "$("$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD_NAME" ] && echo "$s" && break; done; }

if [ "$COMMAND" = stop ]; then
  s="$(serial || true)"; [ -n "$s" ] || { say "The demo emulator is not running."; exit 0; }
  if [ -f "$HOME_DIR/mic-keeper.pid" ]; then kill "$(cat "$HOME_DIR/mic-keeper.pid")" 2>/dev/null || true; rm -f "$HOME_DIR/mic-keeper.pid"; fi
  "$ADB" -s "$s" emu kill >/dev/null && say "Stopped."; exit 0
fi
if [ "$COMMAND" = remove ]; then
  [ -d "$HOME_DIR" ] || { say "Nothing to remove."; exit 0; }
  ask "Delete $HOME_DIR ($(du -sh "$HOME_DIR" | cut -f1)) and everything in it?" || exit 0
  s="$(serial 2>/dev/null || true)"; [ -z "$s" ] || "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
  rm -rf "$HOME_DIR"; say "Removed."; exit 0
fi

# ---- 1. Android pieces ----
fetch_zip() { # "file size sha1" base-url target-dir
  local file size sum base="$2" into="$3"
  read -r file size sum <<< "$1"
  local zip="$HOME_DIR/downloads/$file"
  mkdir -p "$HOME_DIR/downloads" "$into"
  if [ ! -f "$zip" ] || [ "$(sha 1 "$zip")" != "$sum" ]; then
    say "Downloading $file ($(mb "$size") MB)..."
    curl -fL --retry 3 -C - -o "$zip" "$base/$file" || curl -fL --retry 3 -o "$zip" "$base/$file"
    [ "$(sha 1 "$zip")" = "$sum" ] || { rm -f "$zip"; die "$file does not match its checksum; try again."; }
  fi
  unzip -q -o "$zip" -d "$into"
  rm -f "$zip"
}

IMG_DIR="$SDK/system-images/android-35/google_apis/$ABI"
need=0
[ -x "$ADB" ] || need=$((need + $(echo "$PT" | cut -d' ' -f2)))
[ -x "$EMULATOR" ] || need=$((need + $(echo "$EMU" | cut -d' ' -f2)))
[ -f "$IMG_DIR/system.img" ] || need=$((need + $(echo "$IMG" | cut -d' ' -f2)))
if [ "$need" -gt 0 ]; then
  mkdir -p "$HOME_DIR"
  free_kb=$(df -Pk "$HOME_DIR" 2>/dev/null | awk 'NR==2{print $4}' || echo 0)
  say "First start: about $(mb "$need") MB to download from Google, about 6 GB of disk once set up."
  say "Folder: $HOME_DIR (free: $((free_kb / 1000000)) GB). This takes 5 to 30 minutes, depending on your connection."
  ask "Download now?" || die "nothing downloaded."
  [ -x "$ADB" ] || fetch_zip "$PT" "$GOOGLE" "$SDK"
  [ -x "$EMULATOR" ] || fetch_zip "$EMU" "$GOOGLE" "$SDK"
  [ -f "$IMG_DIR/system.img" ] || fetch_zip "$IMG" "$GOOGLE/sys-img/google_apis" "$(dirname "$IMG_DIR")"
fi
if [ "$OS" = Linux ] && [ ! -w /dev/kvm ]; then
  die "the emulator needs /dev/kvm. Turn on virtualisation in the BIOS and add yourself to the kvm group: sudo usermod -aG kvm \$USER (then log in again)."
fi

# ---- 2. The virtual phone ----
AVD_DIR="$ANDROID_AVD_HOME/$AVD_NAME.avd"
if [ ! -f "$AVD_DIR/config.ini" ]; then
  mkdir -p "$AVD_DIR" "$ANDROID_EMULATOR_HOME"
  printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-35\n' "$AVD_DIR" > "$ANDROID_AVD_HOME/$AVD_NAME.ini"
  cat > "$AVD_DIR/config.ini" <<EOF
AvdId=$AVD_NAME
avd.ini.displayname=Phonemesh Demo
avd.ini.encoding=UTF-8
abi.type=$ABI
hw.cpu.arch=$CPU
image.sysdir.1=system-images/android-35/google_apis/$ABI/
tag.id=google_apis
tag.display=Google APIs
target=android-35
hw.ramSize=3072
disk.dataPartition.size=6G
hw.lcd.width=1080
hw.lcd.height=2340
hw.lcd.density=420
hw.keyboard=yes
hw.audioInput=yes
hw.audioOutput=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
hw.gpu.enabled=yes
hw.gpu.mode=auto
showDeviceFrame=no
PlayStore.enabled=false
EOF
fi

# ---- 3. The demo app ----
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$APK" ] && [ "$VERSION" = latest ]; then
  # The demo app for this computer's Android (x86_64 on Intel and AMD), else the ARM one through Android's translation.
  local_apk="$(find "$HERE" -maxdepth 1 -name "*-demo-$ABI.apk" 2>/dev/null | sort | tail -1)"
  [ -n "$local_apk" ] || local_apk="$(find "$HERE" -maxdepth 1 -name "*-demo-arm64-v8a.apk" 2>/dev/null | sort | tail -1)"
  if [ -n "$local_apk" ]; then
    APK="$local_apk"
    if [ -f "$HERE/SHA256SUMS" ]; then
      want="$(awk -v f="$(basename "$APK")" '$2==f || $2=="*"f {print $1}' "$HERE/SHA256SUMS")"
      [ -n "$want" ] && [ "$(sha 256 "$APK")" = "$want" ] || die "$(basename "$APK") does not match the SHA256SUMS next to it."
      say "Using $(basename "$APK") from this folder (matches its SHA256SUMS)."
    else
      say "Using $(basename "$APK") from this folder."
    fi
  fi
fi
if [ -z "$APK" ]; then
  if [ "$VERSION" = latest ]; then api="https://api.github.com/repos/$REPO/releases/latest"; else api="https://api.github.com/repos/$REPO/releases/tags/$VERSION"; fi
  json="$(curl -fsSL "$api")" || die "could not reach GitHub for the release."
  apk_url="$(printf '%s' "$json" | grep -oE "\"browser_download_url\": *\"[^\"]*-demo-$ABI\\.apk\"" | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')"
  # Older releases have only ARM demo apps; Android's translation runs them on x86_64.
  [ -n "$apk_url" ] || apk_url="$(printf '%s' "$json" | grep -oE '"browser_download_url": *"[^"]*-demo-arm64-v8a\.apk"' | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')"
  sums_url="$(printf '%s' "$json" | grep -oE '"browser_download_url": *"[^"]*/SHA256SUMS"' | head -1 | sed -E 's/.*"(https[^"]+)"/\1/')"
  [ -n "$apk_url" ] || die "this release has no demo app yet. Use --apk FILE, or --version with a release that has one."
  [ -n "$sums_url" ] || die "this release has no SHA256SUMS to check the demo app against."
  APK="$HOME_DIR/downloads/$(basename "$apk_url")"; mkdir -p "$HOME_DIR/downloads"
  say "Downloading the demo app ($(basename "$apk_url"))..."
  curl -fL --retry 3 -o "$APK" "$apk_url"
  want="$(curl -fsSL "$sums_url" | awk -v f="$(basename "$apk_url")" '$2==f || $2=="*"f {print $1}')"
  if [ -z "$want" ] || [ "$(sha 256 "$APK")" != "$want" ]; then rm -f "$APK"; die "the demo app does not match the release's SHA256SUMS."; fi
  say "Checked: the demo app matches the release's SHA256SUMS."
fi
[ -f "$APK" ] || die "no such file: $APK"

# ---- 4. Start, install, open ----
s="$(serial || true)"
if [ -z "$s" ]; then
  opts=(-avd "$AVD_NAME" -no-boot-anim -no-snapshot-save -no-metrics) # no usage statistics to Google, and no question about them
  [ "$WINDOW" = 1 ] || opts+=(-no-window)
  # Without -allow-host-audio the emulator hears silence: Talk and "Say your name" need the computer's microphone.
  if [ "$MIC" = 1 ]; then opts+=(-allow-host-audio); else opts+=(-no-audio); fi
  say "Starting the virtual phone (the first start takes a few minutes)..."
  nohup "$EMULATOR" "${opts[@]}" > "$HOME_DIR/emulator.log" 2>&1 &
  "$ADB" start-server >/dev/null 2>&1
  for _ in $(seq 1 120); do s="$(serial || true)"; [ -n "$s" ] && break; sleep 3; done
  [ -n "$s" ] || die "the emulator did not start; see $HOME_DIR/emulator.log"
fi
for _ in $(seq 1 120); do [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 3; done
[ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || die "the virtual phone did not finish starting."
# -allow-host-audio alone still sends zeros: the emulator's "Enable Host Microphone Access" switch starts off, and its
# link to the computer's microphone goes stale after a while (silence, though the switch still says on); only switching
# it off and on again revives it. A small keeper does that every 10 seconds while nothing is recording, never during a
# recording, until the emulator stops (one keeper at a time; "stop" ends it).
KEEPER="$HOME_DIR/mic-keeper.pid"
if [ -f "$KEEPER" ]; then kill "$(cat "$KEEPER")" 2>/dev/null || true; rm -f "$KEEPER"; fi # it may have ended already
if [ "$MIC" = 1 ]; then
  "$ADB" -s "$s" emu avd hostmicon >/dev/null 2>&1 || say "Could not turn on the microphone; Talk will hear nothing."
  # shellcheck disable=SC2016 # expanded by the inner shell
  nohup bash -c 'while [ "$("$0" -s "$1" get-state 2>/dev/null)" = device ]; do
      case "$("$0" -s "$1" shell dumpsys audio 2>/dev/null | grep -E " rec (start|stop) " | tail -1)" in
        *" rec start "*) ;;
        *) "$0" -s "$1" emu avd hostmicoff >/dev/null 2>&1; "$0" -s "$1" emu avd hostmicon >/dev/null 2>&1 ;;
      esac
      sleep 10
    done' "$ADB" "$s" >/dev/null 2>&1 &
  echo $! > "$KEEPER"
fi
say "Installing the demo app..."
"$ADB" -s "$s" install -r "$APK" >/dev/null || {
  say "The installed demo was signed on another computer: removing it first (its data goes too)."
  "$ADB" -s "$s" uninstall "$PACKAGE" >/dev/null; "$ADB" -s "$s" install "$APK" >/dev/null; }
"$ADB" -s "$s" shell am start -n "$PACKAGE/org.phonemesh.app.ChatsActivity" >/dev/null
say "Phonemesh Demo is open. Demo controls: pull down from the top of the virtual phone, or its \"Demo controls\" icon."
say "Stop: $0 stop    Remove everything: $0 remove"
