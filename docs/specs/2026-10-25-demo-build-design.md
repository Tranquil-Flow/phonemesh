# Demo build

**Date:** 2026-10-25
**Status:** approved by the owner ("make a demo build now ... available for future versions as we release them").
**Read with:** `docs/android-design.md`, `docs/specs/2026-10-14-accounts-design.md`, `docs/demo.md` (the user guide).

## Goal

Anyone (a UX designer, a tester, a funder) can try every screen of Phonemesh on one Android phone or an emulator,
with no Meshtastic radio, no second phone and no hub. The same demo ships with every release.

## Decisions

1. **A separate build type, `demo`.** Application id `org.phonemesh.app.demo`, label "Phonemesh Demo", version name
   `<version>-demo`. It installs beside the real app and never shares its data, keys or links. Screenshots and screen
   recording are allowed (no `FLAG_SECURE`). Speech packs come from the published catalog with the release rules.
   It is signed with the build machine's debug key: an update installs over the previous demo only when built on the
   same machine; otherwise uninstall the old demo first.
2. **The shipped app does not change.** All demo code lives in `android/app/src/demo/` and its tests in
   `android/app/src/testDemo/`; release and debug builds contain none of it. The demo replaces the process-wide
   `AppCore` at start with one built through `AppCore.create(radioFactory = …)` plus demo hub links, and replaces
   `ScanActivity.startCamera`. No file under `src/main` refers to the demo.
3. **A simulated world, running the real engines.** One in-process "LoRa mesh" connects:
   - the phone's **demo radio** (a `MeshRadio`): found by Find radio, connected, region set, packets queued and aired
     with LongFast-like airtime;
   - three **demo people** with real accounts (`SoftKeys`, `ContactCard`) and the real `Postman`, so private chats use
     the actual X3DH, Double Ratchet, receipts and retries: **Kofi** (met in person by QR; replies), **Ama** (sends a link
     request; sends receipts only), **Yaw** (you open his link; replies);
   - a **demo local hub** running the real `Hub`, `Mailbox` and `Outbox` from `android/hub`, reached through demo Wi-Fi
     and Bluetooth hub links (`HubLink`/`HubWire`), with the hub's real store-and-forward, acknowledgements and retry
     cooldown.
   Demo people and the hub keep their state in the demo app's own storage, so the demo survives app restarts.
4. **Labelled, and controllable from the app.** A small "DEMO" label in the status bar on every screen marks the demo.
   Android does not deliver touches to the status bar, so the demo controls open from a persistent "Phonemesh Demo"
   notification or from their own "Demo controls" launcher icon. The controls follow the app's language and
   appearance. They trigger what a real deployment would do: people adding you, messages arriving, the
   radio dropping, a person going out of range, the hub or its Wi-Fi or Bluetooth going down, starting over. In the demo,
   **Scan a code** shows a "what is in front of the camera?" picker (a demo person's code or the demo hub's code)
   and hands the chosen text to the app's real scan handling.
5. **Kept working in every release.** `scripts/check.sh` builds the demo and runs its unit tests, so a change to the
   app that breaks the demo fails the project check. The publication checklist attaches
   `phonemesh-<version>-demo-arm64-v8a.apk` and `-armeabi-v7a.apk` with their own checksums to each GitHub release.

## What the demo is not

- Not evidence about radios, range, Bluetooth, Wi-Fi joining or delivery in the field. Timings are simulated.
- Not a second phone: demo people reply with short canned phrases in the message's language.
- Not a security boundary: the demo's people and hub run inside the app, so the demo can read everything in it.
