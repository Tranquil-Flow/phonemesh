# Phonemesh hub (Mac prototype)

The village hub from the accounts spec ("Stage 2: hub"): a Meshtastic radio on USB, a blind mailbox, and an HTTPS API
for phones on the hub's Wi-Fi. It never decrypts anything.

## Run

1. Plug the radio into the Mac and find it: `ls /dev/cu.usb*`.
2. Write `hub.conf`:

   ```
   name = Village hub
   serial = /dev/cu.usbmodem1101
   wifi = Phonemesh-Hub
   wifi_password = choose-one
   days = 30        # mail kept
   duty = 10        # percent of radio time (EU 868)
   port = 8443
   ```
3. `./gradlew :hub:run --args="$PWD/hub.conf"` from `android/`.

At start it prints the radio's node, a warning if the radio's rebroadcast mode is not ALL, and the hub card (Wi-Fi,
port and TLS pin) that the hub QR will carry. Mail and the TLS key live in `hub-data/` next to the config.

## API (phones, over HTTPS pinned to the hub key)

| Call | Does |
|---|---|
| `GET /hello` | `phonemesh-hub 1`, name, newest mail seq |
| `POST /send?port=301` | body: one packet. `stored` / `already` (reached the hub), 400 not ours, 429 busy |
| `POST /mail?since=N&wait=S&hellos=1&shared=1` | body: recipient codes in hex, one per line. Mail after seq N, waiting up to 30 s |
