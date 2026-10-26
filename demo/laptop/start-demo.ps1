# Phonemesh Demo on a Windows laptop: an Android emulator in a folder you own, with the demo app installed.
#
#   .\start-demo.ps1                 set up if needed (asks before downloading), start, install, open the demo
#   .\start-demo.ps1 -Apk FILE       use a demo APK you already have instead of downloading the latest release
#   .\start-demo.ps1 stop            stop the emulator
#   .\start-demo.ps1 remove          delete everything this script made
#
# Options: -Yes (no questions), -NoMic (no microphone or sound), -Headless (no window), -Version vX.Y.Z
# Folder: $env:PHONEMESH_DEMO_HOME, else %LOCALAPPDATA%\phonemesh-demo-laptop. Nothing is installed system-wide and no
# administrator rights are needed. Downloads come from Google (emulator, adb, Android 15) and GitHub (the demo app),
# and are checked against the checksums pinned below (Google's own manifest) and the release's SHA256SUMS.
# If Windows blocks the script: powershell -ExecutionPolicy Bypass -File .\start-demo.ps1
param(
  [Parameter(Position = 0)][ValidateSet('start', 'stop', 'remove')][string]$Command = 'start',
  [string]$Apk = '',
  [switch]$Yes,
  [switch]$NoMic,
  [switch]$Headless,
  [string]$Version = 'latest'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$HomeDir = if ($env:PHONEMESH_DEMO_HOME) { $env:PHONEMESH_DEMO_HOME } else { Join-Path $env:LOCALAPPDATA 'phonemesh-demo-laptop' }
$Sdk = Join-Path $HomeDir 'sdk'
$AvdName = 'PhonemeshDemo'
$Repo = 'Tranquil-Flow/phonemesh'
$Package = 'org.phonemesh.app.demo'
$Google = 'https://dl.google.com/android/repository'

# Pinned from https://dl.google.com/android/repository/repository2-3.xml and sys-img/google_apis/sys-img2-3.xml
# (2026-10-26): file, size, sha1.
$PT = @('platform-tools_r37.0.1-win.zip', 8044989, 'e03e78b1d80b396f1c3358e31251cb31740e1110')
$EMU = @('emulator-windows_x64-16428233.zip', 455342868, '488ed747e82de7e9bb5247becd1ac043c7e5e85d')
$IMG = @('x86_64-35_r09.zip', 1738815903, '0103e6dab21290c4b9d16550a3ce99476f884eef')

function Say($text) { Write-Host $text }
function Stop-Demo($text) { Write-Host "Stopped: $text" -ForegroundColor Red; exit 1 }
function Ask($text) { if ($Yes) { return $true }; (Read-Host "$text [y/N]") -match '^[yY]$' }
function MB($bytes) { [math]::Floor($bytes / 1000000) }

if ([Environment]::Is64BitOperatingSystem -eq $false -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64') {
  Stop-Demo 'Google makes the Android emulator for Windows on x64 only.'
}

$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Emulator = Join-Path $Sdk 'emulator\emulator.exe'
$env:ANDROID_HOME = $Sdk; $env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_AVD_HOME = Join-Path $HomeDir 'avd'; $env:ANDROID_EMULATOR_HOME = Join-Path $HomeDir 'home'

function Serial {
  if (-not (Test-Path $Adb)) { return $null }
  foreach ($line in (& $Adb devices 2>$null)) {
    if ($line -match '^(emulator-\d+)\s') {
      $s = $Matches[1]
      $name = (& $Adb -s $s emu avd name 2>$null | Select-Object -First 1)
      if ("$name".Trim() -eq $AvdName) { return $s }
    }
  }
  return $null
}

if ($Command -eq 'stop') {
  $s = Serial; if (-not $s) { Say 'The demo emulator is not running.'; exit 0 }
  & $Adb -s $s emu kill | Out-Null; Say 'Stopped.'; exit 0
}
if ($Command -eq 'remove') {
  if (-not (Test-Path $HomeDir)) { Say 'Nothing to remove.'; exit 0 }
  if (-not (Ask "Delete $HomeDir and everything in it?")) { exit 0 }
  $s = Serial; if ($s) { & $Adb -s $s emu kill | Out-Null; Start-Sleep 5 }
  Remove-Item -Recurse -Force $HomeDir; Say 'Removed.'; exit 0
}

# ---- 1. Android pieces ----
function Fetch-Zip($pin, $base, $into) {
  $file, $size, $sum = $pin
  $downloads = Join-Path $HomeDir 'downloads'
  New-Item -ItemType Directory -Force -Path $downloads, $into | Out-Null
  $zip = Join-Path $downloads $file
  if (-not (Test-Path $zip) -or (Get-FileHash -Algorithm SHA1 $zip).Hash.ToLower() -ne $sum) {
    Say "Downloading $file ($(MB $size) MB)..."
    curl.exe -fL --retry 3 -o $zip "$base/$file"
    if ($LASTEXITCODE -ne 0) { Stop-Demo "could not download $file." }
    if ((Get-FileHash -Algorithm SHA1 $zip).Hash.ToLower() -ne $sum) { Remove-Item $zip; Stop-Demo "$file does not match its checksum; try again." }
  }
  Expand-Archive -Force -Path $zip -DestinationPath $into
  Remove-Item $zip
}

$ImgDir = Join-Path $Sdk 'system-images\android-35\google_apis\x86_64'
$need = 0
if (-not (Test-Path $Adb)) { $need += $PT[1] }
if (-not (Test-Path $Emulator)) { $need += $EMU[1] }
if (-not (Test-Path (Join-Path $ImgDir 'system.img'))) { $need += $IMG[1] }
if ($need -gt 0) {
  New-Item -ItemType Directory -Force -Path $HomeDir | Out-Null
  $free = (Get-PSDrive -Name (Split-Path -Qualifier $HomeDir).TrimEnd(':')).Free
  Say "First start: about $(MB $need) MB to download from Google, about 6 GB of disk once set up."
  Say "Folder: $HomeDir (free: $([math]::Floor($free / 1e9)) GB). This takes 5 to 30 minutes, depending on your connection."
  if (-not (Ask 'Download now?')) { Stop-Demo 'nothing downloaded.' }
  if (-not (Test-Path $Adb)) { Fetch-Zip $PT $Google $Sdk }
  if (-not (Test-Path $Emulator)) { Fetch-Zip $EMU $Google $Sdk }
  if (-not (Test-Path (Join-Path $ImgDir 'system.img'))) { Fetch-Zip $IMG "$Google/sys-img/google_apis" (Split-Path $ImgDir) }
}

# The emulator needs the Windows Hypervisor Platform (or AEHD). It reports what is missing:
$accel = (& $Emulator -accel-check 2>&1 | Out-String)
if ($accel -notmatch 'is installed and usable|is usable') {
  Say $accel
  Stop-Demo 'turn on "Windows Hypervisor Platform" (Settings, Optional features, More Windows features), restart, and run this again.'
}

# ---- 2. The virtual phone ----
$AvdDir = Join-Path $env:ANDROID_AVD_HOME "$AvdName.avd"
if (-not (Test-Path (Join-Path $AvdDir 'config.ini'))) {
  New-Item -ItemType Directory -Force -Path $AvdDir, $env:ANDROID_EMULATOR_HOME | Out-Null
  Set-Content -Encoding ASCII -Path (Join-Path $env:ANDROID_AVD_HOME "$AvdName.ini") -Value "avd.ini.encoding=UTF-8`npath=$AvdDir`ntarget=android-35"
  @"
AvdId=$AvdName
avd.ini.displayname=Phonemesh Demo
avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
image.sysdir.1=system-images\android-35\google_apis\x86_64\
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
"@ | Set-Content -Encoding ASCII -Path (Join-Path $AvdDir 'config.ini')
}

# ---- 3. The demo app ----
if (-not $Apk) {
  $api = if ($Version -eq 'latest') { "https://api.github.com/repos/$Repo/releases/latest" } else { "https://api.github.com/repos/$Repo/releases/tags/$Version" }
  try { $release = Invoke-RestMethod -Uri $api -UseBasicParsing } catch { Stop-Demo 'could not reach GitHub for the release.' }
  # Windows runs the x86_64 system image; the arm64 demo app runs on it through Android's translation layer.
  $asset = $release.assets | Where-Object { $_.name -like '*-demo-arm64-v8a.apk' } | Select-Object -First 1
  $sums = $release.assets | Where-Object { $_.name -eq 'SHA256SUMS' } | Select-Object -First 1
  if (-not $asset) { Stop-Demo 'this release has no demo app yet. Use -Apk FILE, or -Version with a release that has one.' }
  if (-not $sums) { Stop-Demo "this release has no SHA256SUMS to check the demo app against." }
  $downloads = Join-Path $HomeDir 'downloads'; New-Item -ItemType Directory -Force -Path $downloads | Out-Null
  $Apk = Join-Path $downloads $asset.name
  Say "Downloading the demo app ($($asset.name))..."
  curl.exe -fL --retry 3 -o $Apk $asset.browser_download_url
  $want = ((curl.exe -fsSL $sums.browser_download_url) -split "`n" | Where-Object { $_ -match "\s\*?$([regex]::Escape($asset.name))\s*$" } | Select-Object -First 1)
  $want = if ($want) { ($want -split '\s+')[0].ToLower() } else { '' }
  if (-not $want -or (Get-FileHash -Algorithm SHA256 $Apk).Hash.ToLower() -ne $want) { Remove-Item $Apk; Stop-Demo "the demo app does not match the release's SHA256SUMS." }
  Say "Checked: the demo app matches the release's SHA256SUMS."
}
if (-not (Test-Path $Apk)) { Stop-Demo "no such file: $Apk" }

# ---- 4. Start, install, open ----
$s = Serial
if (-not $s) {
  $opts = @('-avd', $AvdName, '-no-boot-anim', '-no-snapshot-save')
  if ($Headless) { $opts += '-no-window' }
  if ($NoMic) { $opts += '-no-audio' }
  Say 'Starting the virtual phone (the first start takes a few minutes)...'
  Start-Process -FilePath $Emulator -ArgumentList $opts -WindowStyle Hidden -RedirectStandardOutput (Join-Path $HomeDir 'emulator.log') -RedirectStandardError (Join-Path $HomeDir 'emulator-err.log')
  & $Adb start-server 2>$null | Out-Null
  for ($i = 0; $i -lt 120 -and -not $s; $i++) { Start-Sleep 3; $s = Serial }
  if (-not $s) { Stop-Demo "the emulator did not start; see $HomeDir\emulator-err.log" }
}
for ($i = 0; $i -lt 120; $i++) { if ((& $Adb -s $s shell getprop sys.boot_completed 2>$null) -match '1') { break }; Start-Sleep 3 }
if (-not ((& $Adb -s $s shell getprop sys.boot_completed 2>$null) -match '1')) { Stop-Demo 'the virtual phone did not finish starting.' }
Say 'Installing the demo app...'
& $Adb -s $s install -r $Apk | Out-Null
if ($LASTEXITCODE -ne 0) {
  Say 'The installed demo was signed on another computer: removing it first (its data goes too).'
  & $Adb -s $s uninstall $Package | Out-Null; & $Adb -s $s install $Apk | Out-Null
}
& $Adb -s $s shell am start -n "$Package/org.phonemesh.app.ChatsActivity" | Out-Null
Say 'Phonemesh Demo is open. Demo controls: pull down from the top of the virtual phone, or its "Demo controls" icon.'
Say 'Stop: .\start-demo.ps1 stop    Remove everything: .\start-demo.ps1 remove'
