# Phonemesh Demo: try every screen without a radio

Phonemesh Demo is the Phonemesh app with a pretend radio, pretend people and a pretend local hub built in. It is for
designers, testers and anyone curious. You don't need a Meshtastic radio, a second phone or other people.

Everything you see is the real app: the same screens, the same encryption, the same receipts and retries. Only the
radio waves, the other people and the hub are simulated, inside the app.

## Get it

Each release from v0.1.1 has demo files next to the normal app, for example
`phonemesh-0.1.1-demo-arm64-v8a.apk`, and their checksums in `SHA256SUMS`: one for each kind of phone, and an
`x86_64` one for laptops.

- **On an Android phone (8.0 or newer):** install the demo file. Most phones made after 2017 take `arm64-v8a`, older
  ones `armeabi-v7a`. It installs next to the normal Phonemesh app as "Phonemesh Demo" and doesn't touch its data.
- **On a laptop (macOS, Linux or Windows):** run the launcher in [`demo/laptop`](../demo/laptop/README.md). It sets up a
  virtual Android phone in one folder and opens the demo in it.

**For v0.1.2, download the laptop ZIP from the
[demo pre-release](https://github.com/Tranquil-Flow/phonemesh/releases/tag/v0.1.2-demo.1).**
Unzip it, keep the folder together and open **Start Phonemesh Demo.command** on a Mac.
It contains version `0.1.2-demo` / code 3 and checks its included APK before installing.
A bare launcher without the included APK still uses the latest stable release, v0.1.1.
The full v0.1.2 production release is separate and remains unsigned.

The arm64 app was silently exercised on an Android 15 emulator: a typed Market group message
showed **Delivered to 2 of 2**, Kofi and Yaw replied, and fresh crash/error logs were empty.
The x86_64 APK was built and checked, but not run on an x86_64 emulator. The unchanged laptop
launcher was not run through a fresh download again for this pre-release. Older launcher
measurements in [demo-coverage.md](demo-coverage.md) belong to the earlier build.

**Let an agent set it up.** The demo pre-release page has a **Let your agent set it up** block. Paste it into an agent that
can run commands on your own computer. It downloads the exact ZIP and checks its SHA-256, checks the included APKs, starts the
launcher without microphone or sound, and helps you finish the first-run steps in the app. It is meant to leave an
existing demo installation and emulators alone unless you approve. The block belongs to the already published
pre-release; this guide does not change it.

To build a newer branch demo, use `:app:assembleDemo` (add `-Pphonemesh.x86_64=true` for
an x86_64 emulator), then `./start-demo.sh --apk <your demo file>` (Windows: `-Apk`).

## Who is in the demo

- **Demo radio**: your radio. **Find radio** lists it; tap it to connect.
- **Kofi**: you meet him in person. In **Add someone**, **Scan a code**, pick "Kofi's code".
- **Yaw**: he sent you his link. In **Scan a code**, pick "Yaw's link".
- **Ama**: she adds you by link about a minute and a half after your radio first connects. You can **Accept** or
  **Ignore**.
- **Sena**: she uses the plain Meshtastic app, not Phonemesh. She writes in the shared chat.
- **The local hub**: in Settings, **Local hub**, **Scan hub code**, pick "The local hub's code".
  Then **Updates** (v0.1.2 demo only) offers a newer "Demo voice" pack, signed with demo-only keys that the real app
  does not trust. Tap **Download** to watch the real copy, check and install.
- **A private group** (v0.1.2 demo only): in **Add someone**, tap **New private group**, choose Kofi and Yaw (and Ama once
  you have accepted her), name the group and tap **Create group**. A few seconds later they tap Join for themselves.
  Kofi and Yaw answer what you write in the group; Ama joins but never writes. Your message shows
  **Delivered to 2 of 2** once both have sent their receipts. Open **Group details** from the group's title to see
  the people, the group code and **Leave group**.

Kofi and Yaw answer your private messages. Everyone nearby sometimes writes in the shared chat. Each person has
their own picture: Kofi an emoji, Ama her letter, Yaw his letter on a colour from the colour wheel.

## The demo controls

Pull down from the top of the screen and tap the **Phonemesh Demo** notification, or open the **Demo controls** icon.
From there you can make things happen that would need other people or hardware:

- Kofi writes to you, sends a picture, or changes his picture and writes; Ama adds you by link; Sena writes to
  everyone or to you.
- See the safety pictures on Kofi's, Ama's or Yaw's phone, to compare with yours.
- Kofi goes out of range and comes back, so messages go through the hub.
- Switch your radio off and on, the hub off and on, and the hub's Wi-Fi or Bluetooth off and on.
- **Start over** erases the demo app's data and takes you back to the first screen.

A small yellow **DEMO** label at the top of the screen tells the demo apart from the real app.

## What to try

A first walk through, in about fifteen minutes:

1. Choose your language and follow the steps on the Chats screen: download the voice, say or type your name, find the
   radio, add Kofi, and say hello in the shared chat.
2. Open Kofi's chat and send him a message. Watch it go from Sent to Delivered, then read his answer.
3. Long-press a message to reply, react, copy, share, forward or delete it.
4. Open Yaw's link and compare the six safety pictures with the ones on Yaw's phone (demo controls).
5. Join the local hub, send Kofi out of range, and send him another message. It shows "Reached the hub", then
   Delivered.
6. Switch the radio off, send something, and switch it back on. The message waits, then goes.
7. Try French, and dark mode, in Settings.

The full list of flows, and which ones have been checked, is in [demo-coverage.md](demo-coverage.md).

## Talking and listening

**Talk** needs a microphone: the phone's own, or the laptop's in the virtual phone. Read aloud needs sound. Both work
as in the real app, on your device only.

## What the demo does not show

The demo cannot tell you how far a real radio reaches, how busy a real channel gets, or how real Bluetooth pairing and
Android's Bluetooth permission screens behave. Joining the hub's Wi-Fi and the hub's key check are also simulated.
Timings follow the real radio settings (LongFast), but they are an estimate, not a measurement.

In a demo group, Kofi, Ama and Yaw only know you, so they answer you and never each other. The demo does not set up a
member who is not in your contacts, or a group change that waits for a member who is away. The pack update in the demo
is signed with keys made on the phone each time the demo starts, so it shows how updates look and behave, not that the
project's real update keys work.

To save chats, open **Settings → Recovery kit → Contacts and chats**, select chats and the
time range, count the copy, write its recovery card down, then save and check it. Restoring
keeps the old messages in read-only **Saved chats**, never as live group membership. This
flow has instrumentation coverage; it has not had a manual demo walk in this release.

## Good to know

- The demo is built and signed on the machine that built the release. To update it, install the newer demo file. If
  Android refuses, uninstall the old demo first.
- The demo downloads voices from the same place as the real app, so the first voice download needs internet.
- Nothing you do in the demo leaves the phone, apart from that voice download.
