# Phonemesh Demo: start here

Phonemesh Demo is the real Phonemesh app with a pretend radio, pretend people (Kofi, Ama, Yaw) and a pretend local
hub, so you can try every screen without a phone or a radio. On a laptop it runs inside an Android emulator: a virtual
phone in its own window.

## What you need

- A Mac with Apple silicon (M1 or newer). This is the tested setup. Linux on x86_64 uses the same files but has not
  been tried yet; Intel Macs and Windows are untested.
- 8 GB of memory or more (16 GB is smoother).
- About 7 GB of free disk, and about 2.3 GB to download once from Google.

## Steps on a Mac

1. Download the demo zip and unzip it (double-click). Move the **Phonemesh Demo** folder somewhere you keep things,
   such as Documents. Keep every file in it together.
2. Double-click **Start Phonemesh Demo.command**. A Terminal window opens and says what it is doing.
   - If macOS says it cannot check the file: click **Done**, open **System Settings**, then **Privacy & Security**,
     scroll down and click **Open Anyway** next to "Start Phonemesh Demo.command", then confirm. You only do this once.
3. It asks before downloading the emulator and Android 15 from Google. Type `y` and press Return. The first start takes
   5 to 30 minutes, depending on your connection. Later starts take about a minute.
4. When macOS asks whether Terminal may use the microphone, click **Allow** if you want to try speaking (**Talk** and
   **Say your name**). What you say stays on your Mac.
5. The virtual phone opens with Phonemesh Demo on it. Use it with the mouse and keyboard.

## Using the demo

- The setup guide walks you through five steps. To add someone, tap **Add someone**, then **Scan a code**, and pick
  **Kofi's code**: there is no camera, so you choose what is in front of it.
- **Demo controls** make things happen: Kofi writes or sends a picture, Ama asks to talk, the radio or the hub goes off,
  and **Pretend country** (pick **Togo** to see the radio region screen with its flag). Open them from the
  **Demo controls** icon, or pull down from the top of the virtual phone and tap the "Phonemesh Demo" notification.
- Everything you can try, screen by screen: `docs/demo.md` and `docs/demo-coverage.md` in the Phonemesh repository.

## Stop, start again, remove

- Stop: double-click **Stop Phonemesh Demo.command**, or close the virtual phone's window. Your demo chats are kept.
- Start again: double-click **Start Phonemesh Demo.command**.
- Remove everything (about 6 GB): in Terminal, in this folder, run `./start-demo.sh remove`.

## If something goes wrong

- The Terminal window says what stopped it. The emulator's own log is `~/.cache/phonemesh-demo-laptop/emulator.log`.
- **Talk says "Not understood"**: check that Terminal is allowed in **System Settings**, **Privacy & Security**,
  **Microphone**, speak close to the Mac, and pause any video or music.
- Send the Terminal text and the last lines of the log to whoever gave you the demo.

## Give this to your AI agent

If you use an AI coding agent on your Mac, you can paste this to it:

```text
Help me run Phonemesh Demo, an Android app inside Google's Android emulator, on this Mac.

1. Check this computer: chip (Apple silicon or Intel), macOS version, memory (8 GB or more) and free disk (7 GB or
   more). Tell me if anything falls short before going on.
2. The demo folder is "Phonemesh Demo" (it contains start-demo.sh, Start Phonemesh Demo.command, an APK and
   SHA256SUMS). In that folder, check the APK: shasum -a 256 -c SHA256SUMS must say OK.
3. In that folder, run: ./start-demo.sh --yes
   It downloads Google's emulator, platform-tools and an Android 15 system image into ~/.cache/phonemesh-demo-laptop,
   checks them against pinned SHA-1 sums, installs the demo app and opens it. Nothing is installed system-wide.
4. Rules: never use sudo; do not install Android Studio, Homebrew packages or anything else; do not edit the scripts;
   do not change macOS privacy settings yourself. If macOS asks for microphone access, I decide that.
5. If it fails, show me the exact error from the script and the last 40 lines of
   ~/.cache/phonemesh-demo-laptop/emulator.log, and stop.
6. Stop the demo with ./start-demo.sh stop. Remove everything with ./start-demo.sh remove (asks first).
```
