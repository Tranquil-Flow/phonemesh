# Phonemesh local hub

The local hub was called the *village hub* before v0.1.0, and the v0.1.0 app labels its
screen **Village hub** (*Relais du village*). From v0.1.1 the app says **Local hub** (*Relais local*).

A JVM mailbox/relay with a Meshtastic radio on USB. It runs on macOS, Linux (including a
Raspberry Pi) and Windows. Phones reach its pinned HTTPS API by local Wi-Fi, or over
Bluetooth through an optional bridge on the same computer: the
[Mac bridge](../../hub-ble-bridge/README.md) or the [Linux bridge](../../hub-ble-bridge-linux/README.md).
Windows hubs are Wi-Fi only for now.
It cannot decrypt private account-message bodies. Shared-channel messages are not
end-to-end private, and packet metadata remains visible to the hub.

**Development setup, not a published appliance.** Earlier physical BLE/pinned-TLS/USB
text delivery passed in both directions. Production phone-to-hub Wi-Fi delivery and
final-candidate voice/restart recovery remain unqualified. See [project status](../../README.md).

## Choose the topology first

- **BLE (macOS, or Linux with the [Linux bridge](../../hub-ble-bridge-linux/README.md), one phone at a time):** phone → BLE bridge → `127.0.0.1:<port>` JVM hub → USB radio → mesh.
  The Mac needs BLE peripheral support and Bluetooth permission. The JVM process alone
  does not advertise Bluetooth; both processes must run on the same Mac.
- **Wi-Fi:** phone → hub Wi-Fi's **IPv4 default gateway** at the card's port → JVM hub
  → USB radio. The hub computer must be that gateway (or the topology must explicitly route the
  gateway endpoint to the hub). An ordinary client on the same access point is
  **not** the endpoint the Android app looks up. There is no arbitrary-host field.
  Android's in-app Wi-Fi join requires API 29+ and binds hub traffic to that local
  network, not mobile data. Configure the AP/gateway separately with the network owner.

`wifi` and `wifi_password` only describe the network in the enrollment card. **Neither
this config nor the bridge creates a Wi-Fi AP or Internet Sharing.** Do not change a
phone's hotspot or shared internet connection to follow this guide. Enrollment attempts
both Wi-Fi and BLE; an Android Wi-Fi chooser can appear even when BLE is intended.
If no hub AP exists, cancel that chooser rather than joining an unrelated network.

## Configure and start

Install Java 17 (a JDK, for its `keytool`) and set `JAVA_HOME`. Get the hub either as
`phonemesh-hub-<version>.zip` from a release (from v0.1.1), or build it from `android/` with
`./gradlew :hub:distZip` (it lands in `hub/build/distributions/`). Unzip it anywhere; it
holds `bin/phonemesh-hub` (macOS, Linux) and `bin/phonemesh-hub.bat` (Windows).

Choose a compatible, legally configured radio, plug it in, and find its port with
`bin/phonemesh-hub --ports`. It is usually `/dev/cu.usbmodem…` on a Mac, `/dev/ttyACM0`
or `/dev/ttyUSB0` on Linux, and `COM3` or similar on Windows. Do not copy another
machine's serial endpoint.
Opening USB serial can reset some boards; do not share an in-use port. The hub does
not flash firmware or configure channel/region settings. Its warning about rebroadcast
mode requires operator review in the stock radio tools, not an automatic setting change.

Create `hub.conf` in a private operator directory, outside the source checkout. Replace
the placeholders; this is a template, not credentials for an existing hub:

```ini
name = Local hub
serial = REPLACE_WITH_YOUR_RADIO_PORT
wifi = YOUR_HUB_SSID
wifi_password = REPLACE_WITH_YOUR_WIFI_PASSWORD
days = 30
duty = 10
port = 8443
dir = /absolute/private/path/to/hub-data
```

- `#` starts a comment; avoid it in values. Unknown setting names are rejected.
- `days`: retention, 1–3650, default 30. Keep it at 3 or more: below that, a message
  a sender is still retrying can expire and be stored again as new mail (still bounded
  by the sender's three-day retry window). `duty`: 1–100 percent, default 10; it is an
  airtime budget, **not** a certification of legal operation in your region. Set it
  appropriately before transmitting.
- `port`: 1–65535, default 8443. Keep the bridge port identical. The HTTPS server binds
  all interfaces; restrict access to intended local networks, not public internet.
- `wifi`: nonempty SSID, at most 32 UTF-8 bytes. Password: empty for an open network or
  8–63 characters; the Android join uses WPA2 when nonempty. Set the access point to
  **WPA2-Personal**, not WPA2/WPA3 mixed (transition) mode: Android 10 phones cannot
  join a mixed-mode network through the app (confirmed 2026-10-25). Use the actual AP
  values for Wi-Fi; a card label alone does not create a reachable network.
- `dir`: use an absolute private path. Without it, data goes to `hub-data/` beside the
  config; a supplied relative path is relative to the process working directory.

From `android/`:

```sh
./gradlew --no-daemon --console plain :hub:test
./gradlew --no-daemon --console plain :hub:run --args="/absolute/private/path/to/hub.conf"
```

Or, from the unzipped hub: `bin/phonemesh-hub /absolute/private/path/to/hub.conf` (Windows:
`bin\phonemesh-hub.bat C:\path\to\hub.conf`).

The second command opens hardware/network services. On first start it creates a TLS
identity using JDK `keytool`; later starts reuse it. Mail lives under `dir/mail`, keys
under `dir/keys`. Startup prints the radio identity, any rebroadcast warning and an
encoded `phonemesh-hub:1;...` enrollment card. The program prints text, not a QR image:
render that **exact** card with a trusted offline QR generator for enrollment. Do not
paste it into a public QR website. If using BLE, start the bridge with the card's pin
and the same HTTPS port as described in the [bridge guide](../../hub-ble-bridge/README.md#run-and-stop).

**Treat config, card/QR, startup logs, mail and keys as sensitive.** The encoded card
contains the Wi-Fi password; the hub logs it even though `HubCard.toString()` redacts it.
Never commit, publish or attach real operator data/logs to bug reports. Keep the data
folder across restarts: replacing its TLS identity changes the pin and requires
trusted re-enrollment. Do not delete it as a connectivity workaround.

## On Linux

Checked on 2026-10-26 in Linux containers (arm64 and x86_64) with a pretend radio on a
pseudo-terminal: the hub opened the port, woke the radio, read its answer and served
HTTPS. Not yet tried with a real radio or real phones on Linux.

- **Port access:** add yourself to the group that owns the port (`ls -l /dev/ttyACM0`;
  usually `dialout`, or `uucp` on Arch), then log in again. Prefer the stable name under
  `/dev/serial/by-id/` in `hub.conf`, since `ttyACM0` can change after a replug.
- **Wi-Fi:** the hub computer must be the phones' gateway. With NetworkManager:
  `nmcli device wifi hotspot ifname <wifi device> ssid <name> password <password>` makes a
  WPA2-Personal network with this computer as gateway (usually `10.42.0.1`). Put the same
  name and password in `hub.conf`.
- **Firewall:** allow the hub's port on that network, for example `sudo ufw allow 8443/tcp`.
- **Bluetooth:** the [Linux bridge](../../hub-ble-bridge-linux/README.md) lets phones reach
  the hub over Bluetooth, one phone at a time. Not yet tried with real hardware.
- **Keep it running:** run `bin/phonemesh-hub /path/to/hub.conf` as an ordinary user, for
  example from a systemd service (not yet tried), and keep `dir` on storage that survives
  reboots.

## On Windows

**Not yet tested on Windows.** The hub uses the same portable serial library as on Linux,
and Gradle's Windows start script.

- **Port:** `bin\phonemesh-hub.bat --ports` lists them (for example `COM4  USB Serial Device`).
  Use that name, such as `serial = COM4`. Some radios need the USB driver for their chip
  (CP210x or CH340).
- **Wi-Fi:** the hub computer must be the phones' gateway. Windows **Mobile hotspot** does
  this (the computer is usually `192.168.137.1`). Choose WPA2 if Windows offers a security
  type, and put the hotspot's name and password in `hub.conf`. Windows may require an
  internet connection to share before it turns the hotspot on; if so, use a separate
  router that forwards the hub's port to this computer instead.
- **Firewall:** allow the port once, in an administrator PowerShell:
  `New-NetFirewallRule -DisplayName "Phonemesh hub" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow`.
- **File permissions:** on Windows the hub cannot restrict its key files to your user the
  way it does on macOS and Linux. Keep `dir` inside your own user folder.

## Enroll and use a phone

1. In Phonemesh open Settings → **Local hub → Scan hub code** (**Village hub** in v0.1.0). Scan the operator's
   code in person; do not enroll from an untrusted forwarded card.
2. Grant requested camera/Bluetooth permissions and, for a real hub AP, approve the
   local Wi-Fi join. With BLE-only setup, cancel the unavailable Wi-Fi chooser.
3. Look for **Hub answered. It matches your code.** A **Linked, not checked** state
   means a connection only, not authenticated hub readiness. The screen lists Wi-Fi,
   Bluetooth and **Sending by** separately; routes prefer Wi-Fi, then BLE, then radio.
4. Use the conversation normally. **Reached the hub** is durable mailbox acceptance,
   not **Delivered** to the recipient. A missing answer, busy hub or missing radio
   route must not be read as successful delivery.

Foreground hub discovery/retry retries about once a minute; that is not a promise of
recovery within 60 seconds. On real phones, rc3 reconnected automatically after a hub
restart with the same pin and data, and v0.1.0 recovered a lost receipt through the
sender's retry. Each was a single trial, not a guarantee of continuous recovery.

Background use is **off by default**. **Keep hub when app is closed** explicitly opts
into a foreground service with a silent notification and additional battery use. Check
its status line: a selected switch alone does not prove the service started. Turn it
off (or use the notification's Stop) to end background use. **Forget this hub** clears
enrollment and disables background use; using it again requires another trusted scan.

## Durable forwarding and its limits

The production startup uses `outbox.bin` under the configured data directory for
phone-origin forwarding intents. It admits at most **2,000 pending intents globally /
200 queued packets per phone address**, with a **three-day** forwarding horizon from
first storage, separate from mailbox retention (`days`). Full queues return busy;
mailbox acceptance is not a promise that the radio or recipient received anything.

When a phone resubmits the same private port-301 packet, the hub may forward its exact
ciphertext once more: at most once a minute per full packet digest across addresses
and process restarts. The sender's retry delays start at about 2 minutes and double up to about
4 hours, at most 24 retries in up to three days, stopping on an
authenticated recipient receipt. The hub never decrypts or adds a cleartext retry
marker, never refreshes the stored message's sequence/time/retention, and never
starts a periodic rebroadcast loop. Shared port-303 posts keep single forwarding.
The version-2 journal reads version 1 without dropping pending intents or airtime
reservations; old stored private mail can receive one requested retry before its
new cooldown is recorded.

The hub persists the rolling airtime reservations and next-send gap before serial
submission, so process restart does not reset that budget. A definite pre-submission
refusal retains work with backoff; an uncertain write retains the intent but parks it
until process recovery or a later eligible explicit resubmission by a phone, rather
than repeatedly transmitting it in the same process. Storage failures retain work for
retry. There is **no periodic mailbox rebroadcast**.

The radio queue counts seconds on air, not packets. The hub's own acknowledgements go
first, then short packets (receipts and most texts) before full ones such as picture
pieces. Among phones, the one that used the least radio time in the last hour goes next.
Phones' packets together get at most **180 seconds an hour** (5 %), inside `duty`, so
relays and acknowledgements keep room. The hub cannot read private mail, so packet size
is the only sign of what a packet is.

When the recipient's phone is on the hub's Wi-Fi (it asked for mail in the last 90
seconds), a new private packet for it waits up to **2 minutes** for that phone to fetch
it. A packet fetched over Wi-Fi is not sent by radio; the recipient's receipt tells the
sender as usual. A later resend by the sender still goes by radio. The hub keeps the
recipient codes it was asked for in memory only, for 90 seconds, with no phone address.
Atomic replacement and file sync support process-restart recovery, not sudden
power-loss safety (the directory is not synced), exactly-once radio submission or
guaranteed delivery. A crash around serial submission can cause a duplicate attempt.

On Android, schema 15 stores pinned `/send` acceptance in `hub_custody`, bound to the
outbox item and hub pin. It remains durable custody evidence across a phone process
restart; it no longer suppresses scheduled retries of non-receipt private packets.
Accepted receipts are not periodically retried, avoiding receipt loops. An unsigned
port-302 radio observation is **not** pinned custody. Neither kind of hub observation
is a recipient delivery acknowledgement.

Hub-only Talk readiness requires a response on the current pinned connection, not
just a Bluetooth/Wi-Fi link. The physical checks behind these behaviours are listed in
the [release notes](../../docs/releases/v0.1.0.md).

## Stop and restart

Run hub and bridge in separate foreground terminals. Stop the bridge with Ctrl-C, then
stop the Gradle/JVM hub with Ctrl-C. Verify the specific bridge/JVM process and HTTPS
listener have exited before reusing the radio or port; a terminal closing is not proof
that every child exited. There is no installed service/supervisor or automatic startup
in these commands. Restart with the same config/data directory and the same bridge
pin/port. Never run two hub processes against the same serial port/data directory.

## API reference

All phone calls use TLS pinned to the hub public key from the card (over Wi-Fi or BLE).

| Call | Result |
|---|---|
| `GET /hello` | `phonemesh-hub 1`, name, newest mail sequence |
| `POST /send?port=301` (private) or `port=303` (shared) | One packet, at most 228 bytes; `stored` / `already` means reached hub; 400 refused, 429 busy |
| `POST /mail?since=N&wait=S&hellos=1&shared=1` | Recipient codes in the body, hex, one per line; mail after N, waiting at most 30 seconds |

Implementation: [config](src/main/kotlin/org/phonemesh/hub/HubConfig.kt),
[startup](src/main/kotlin/org/phonemesh/hub/HubMain.kt),
[HTTPS/TLS](src/main/kotlin/org/phonemesh/hub/HubServer.kt),
[Android gateway selection](../app/src/main/java/org/phonemesh/app/HubLink.kt).
Commands above are operational instructions; no build, hardware or network test is
claimed merely by documenting them.
