# Phonemesh Demo on a laptop

Runs the Phonemesh Demo app in an Android emulator on your laptop. You don't need a phone or a radio. The scripts put
everything in one folder you own, and they never need administrator rights.

What the demo is and what you can try in it: [docs/demo.md](../../docs/demo.md).

## You need

- macOS (Apple silicon or Intel), Linux on x86_64, or Windows 10/11 on x64.
- About 2.3 GB to download once and 6 GB of free disk.
- 8 GB of memory or more.
- Linux: `/dev/kvm` (hardware virtualisation on, and your user in the `kvm` group).
- Windows: the "Windows Hypervisor Platform" feature turned on.

## Start

macOS or Linux, in a terminal:

```sh
./start-demo.sh
```

Windows, in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\start-demo.ps1
```

The first start asks before it downloads the emulator and Android 15 from Google, and checks them against pinned
checksums. Then it downloads the demo app from the latest Phonemesh release and checks it against the release's
`SHA256SUMS`. Later starts take a minute or two.

To use a demo app file you already have: `./start-demo.sh --apk phonemesh-0.1.1-demo-arm64-v8a.apk` (Windows:
`-Apk`).

The virtual phone uses your laptop's microphone for **Talk**. To start without the microphone or sound, add
`--no-mic` (Windows: `-NoMic`).

## Use

Click and type in the virtual phone as you would on a real one. To open the demo controls, pull down from the top of
the screen and tap the "Phonemesh Demo" notification, or open the **Demo controls** icon.

## Stop and remove

```sh
./start-demo.sh stop      # stop the virtual phone (your demo data stays)
./start-demo.sh remove    # delete the folder and everything in it
```

On Windows: `.\start-demo.ps1 stop` and `.\start-demo.ps1 remove`.

The folder is `~/.cache/phonemesh-demo-laptop` (Windows: `%LOCALAPPDATA%\phonemesh-demo-laptop`). Set
`PHONEMESH_DEMO_HOME` to use another one.

## Good to know

- Windows and Linux laptops run an x86_64 Android. The demo app is built for ARM phones and runs there through
  Android's built-in ARM translation, so it is a little slower.
- If the demo app on the virtual phone was built on another computer, the script removes it first, because Android
  only updates an app signed with the same key. Your demo data goes with it.
