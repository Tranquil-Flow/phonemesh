# Linux Bluetooth bridge for the Phonemesh hub

Lets phones reach a Linux [local hub](../android/hub/README.md) over Bluetooth, as the
[Mac bridge](../hub-ble-bridge/README.md) does on a Mac. It advertises the hub's Bluetooth
service through BlueZ and passes each phone stream, unread, to the hub's HTTPS port on
**127.0.0.1**. Run it on the same computer as the hub, for example a Raspberry Pi.

TLS runs between the phone and the hub; the bridge only sees encrypted bytes. It uses the
hub's pin to name its Bluetooth service, so phones can tell which hub they found.

**One phone at a time.** BlueZ sends every notification to every connected phone, so this
bridge serves one phone at a time. Another phone that tries meanwhile is ignored and
retries later; its app sends by Wi-Fi or radio instead.

**Status:** checked in a Linux container against a pretend BlueZ that plays two phones
(`tests/run_fake_bluez.sh`). Not yet tried with real Bluetooth hardware and real phones.

## You need

- Linux with BlueZ 5.50 or newer and a Bluetooth adapter that can advertise (Raspberry Pi 3,
  4 and 5 have one built in).
- Python 3.9 or newer with `python3-dbus` and `python3-gi`, as on Raspberry Pi OS. If they
  are missing: `sudo apt install python3-dbus python3-gi`.
- Bluetooth on: `bluetoothctl power on`.
- Permission to use BlueZ: add your user to the `bluetooth` group
  (`sudo usermod -aG bluetooth $USER`, then log in again). Do not run the bridge as root.

## Run

Start the hub first and copy the 43-character **pin** from the hub card it prints
(`pin=...`). Then, from this folder:

```sh
python3 -m phonemesh_bridge --pin 'REPLACE_WITH_CARD_PIN' --port 8443
```

The port must be the hub's `port`. The bridge logs when its service is registered, when it
advertises, and when a phone subscribes. Press **Ctrl-C** to stop; it stops advertising
and closes every phone stream.

Exit codes: 64 for wrong options, 69 when BlueZ or a suitable adapter is missing (or
registration fails), 77 when BlueZ refuses access (see the `bluetooth` group above).

## Test

```sh
python3 -m unittest discover -s tests
```

runs the stream logic tests, ported from the Mac bridge (no Bluetooth needed). Set
`PM_GATT_VECTORS` to `android/core/build/gatt-vectors/service-uuids.txt` (made by the
Android core tests) to also check the service names against the app's own code.

`docker run --rm -v "$PWD":/work debian:bookworm-slim sh /work/tests/run_fake_bluez.sh`
checks the BlueZ side against a pretend BlueZ on a private D-Bus. It proves the bridge
follows BlueZ's D-Bus interface as the test understands it, not real radio behaviour.

## Licences

The bridge is AGPL-3.0, like the rest of Phonemesh. It uses the system's dbus-python (MIT)
and PyGObject (LGPL-2.1 or later); neither is bundled.
