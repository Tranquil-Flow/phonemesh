# Mac BLE bridge for the Phonemesh hub

A CoreBluetooth GATT peripheral that forwards opaque phone streams to the JVM hub's
HTTPS server at **127.0.0.1**. It is not the mailbox, a radio driver or a Wi-Fi AP.
Run the [JVM hub](../android/hub/README.md) and this bridge on the **same Mac**.

TLS terminates at the JVM hub, not here. The phone checks the hub's public-key pin;
the bridge uses that pin only to derive the advertised service UUID. Advertising or
connecting does not prove the hub answered. Private message encryption is a separate
phone-to-phone layer; this bridge does not decrypt those messages or hold TLS keys.

## Build and test

Requirements: **macOS 12+**, a Swift **5.7+** toolchain (Xcode/Command Line Tools), and
BLE peripheral-capable hardware for live use. Foundation, CryptoKit, CoreBluetooth and
Network are system frameworks; the package has no third-party dependencies.

From the repository root:

```sh
cd hub-ble-bridge
swift test
swift build -c release
```

The pure `BridgeCore` tests use fakes and require neither Bluetooth nor a running hub.
Passing them does not qualify native Bluetooth, Android or RF behavior. These commands
are defined by [Package.swift](Package.swift), not a claim they ran for this revision.

## Run and stop

1. Start the JVM hub with its approved USB radio/config; keep its data directory so its
   TLS identity survives restart. Obtain the **43-character base64url pin** from the
   printed enrollment card, not a certificate fingerprint in another format.
2. In a separate terminal, from `hub-ble-bridge/`, replace the placeholder:

   ```sh
   .build/release/hub-ble-bridge --pin 'REPLACE_WITH_CARD_PIN' --port 8443
   ```

   Both options are required. The port must match the JVM HTTPS listener (1–65535).
   There is no remote-host option, environment fallback or default port. The pin is
   public-key identity material, not a password; the **whole hub card contains a Wi-Fi
   password**, so keep cards/config/startup logs private and out of source control.
3. Approve macOS Bluetooth access when prompted. The package embeds Bluetooth usage
   text in the binary. If denied, allow the program/launching application in the OS
   Bluetooth privacy controls (System Settings → Privacy & Security → Bluetooth on
   newer macOS; System Preferences → Security & Privacy → Privacy on macOS 12), then
   restart. Do not disable OS privacy protections.
4. With Bluetooth on, stderr reports advertising and the loopback port. With Bluetooth
   off it waits and resumes when powered on. Unsupported peripheral hardware exits 69;
   denied/restricted access exits 77; malformed arguments exit 64. `--help` shows usage.
5. Enroll the phone using the same hub card: [phone instructions](../android/hub/README.md#enroll-and-use-a-phone).
   The Mac being on the phone's Wi-Fi is irrelevant to this BLE path. For Wi-Fi instead,
   the app expects the hub at the network's IPv4 gateway; this bridge does not create it.
6. Press **Ctrl-C** to stop. SIGINT/SIGTERM handlers stop advertising and close active
   phone streams/loopback connections. Stop the JVM hub separately and verify its
   listener/process also exited before releasing the radio. No background service or
   auto-launch configuration is installed by these commands.

## Troubleshooting and qualification limits

- **Advertising but no hub answer:** check the JVM hub is listening on the selected
  loopback port and both processes use the same card identity. Do not bypass the pin.
- **Pin changed after restart:** preserve/restore the intended operator data directory;
  don't silently regenerate trust. An intentional new identity needs a new trusted scan.
- **Android shows a Wi-Fi chooser:** enrollment tries Wi-Fi and BLE. If there is no real
  hub AP, cancel that chooser; it is not proof the BLE service was absent.
- **Reached the hub:** not recipient delivery, read or playback. BLE connectivity does
  not qualify radio reachability, delayed-mail behavior or hub-only voice.

Earlier physical bidirectional text delivery used this native bridge with pinned TLS,
Mac JVM hub and USB radio. Final changed-candidate voice/recovery and production Wi-Fi
qualification remain separate. Do not infer universal hardware support or automatic
host-restart recovery from that earlier result.

Source: [native lifecycle/loopback bridge](Sources/hub-ble-bridge/main.swift),
[argument parser](Sources/BridgeCore/Options.swift),
[framing](Sources/BridgeCore/Framing.swift).
