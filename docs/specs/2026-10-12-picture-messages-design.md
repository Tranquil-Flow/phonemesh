# Picture messages over the radio (test branch)

Branch `feat/image-messages`, kept apart from the voice-only line so it can be dropped, kept separate, or merged.
Research behind every number: `private-operator-record:research_notes/lora-image-transfer/` in the main checkout (firmware v2.7.26.54e0d8d).

## Owner decisions (2026-10-12)

1. Pictures may take several radio packets, on a private Meshtastic port. This is limited to pictures; text messages
   keep their one-packet rule.
2. The default picture is small: 96 px on its longest side, 500 bytes or less. 128 px is an option for later
   testing, behind the test chooser.
3. Pictures go only in private chats (direct messages). There are none in the shared chat.
4. Everyday scenes matter; text inside photos need not be readable.
5. The branch is a test bench: several methods may be chosen and compared on the owner's phones.

## Keeping voice-only and pictures apart

- **Branch:** the branch holds all picture work. `main` and `feat/visual-guide` stay voice-only.
- **One app:** builds from this branch are the normal app (`org.phonemesh.app`) with pictures added (owner decision,
  2026-10-13).
  - Installing one migrates the chat history to version 2, which adds the pictures table.
  - Going back to a voice-only build afterwards means clearing the app's data: Android refuses to open a newer history.
- **Build switch:** `-Pphonemesh.pictures=false` builds the voice-only app from this branch, with no picture button and
  picture packets ignored. It keeps history version 2.

## Scope of this branch

- **Send:** in a private chat, take a photo with the phone's camera app or pick one with Android's own picker; neither
  needs a permission (added 2026-10-13). The camera writes into one file in the app's cache through `CaptureProvider`
  (not exported, a one-time grant), so nothing is saved in the gallery; the file is deleted when the picture screen
  closes. Every failure has its own sentence: unreadable photo, no camera app, no photo returned, cannot be made small
  enough. The phone shrinks it,
  encodes it, and shows the exact picture the other phone will get, with its size and the estimated time on air.
  The picture is sent only after Send is tapped.
- **Receive:** a placeholder (a blurred colour sketch) appears with the first packet, then the picture appears when every
  piece has arrived and checks out.
- **Methods on the phone for now:** WebP at three sizes (64 / 96 / 128 px) and colour or grey, plus "sketch only"
  (the 21-byte blurred colour sketch, ThumbHash). All use what Android already has, so there are no new libraries.
- **Methods compared on the laptop first:** WebP, AVIF, header-less JPEG and CompressAI (learned) on the owner's own
  photos, using `private-operator-record:image-lab/`. A method moves to the phone only after it wins there; CompressAI on the phone needs a
  new runtime dependency and an approved pack, which is a separate decision.
- **Not in this branch:** pictures in the shared chat, AI upscaling (a later, opt-in "enhanced" view), and captions.

## Wire format (port 300, version 1)

Every packet is one Meshtastic `Data` on `portnum = 300`, sent as a direct message with `want_ack`. 300 is in the
private range 256–511 and is not 256, which MeshTXT uses. Byte 0 is `version << 4 | kind`. Bytes 1–2 are the picture id
(big-endian u16, random per picture).

| kind | name | rest of the packet |
|---|---|---|
| 0 | HEAD | codec u8, width u16, height u16, total bytes u16, part count u8, CRC-32 of the whole picture u32, sketch length u8, sketch bytes |
| 1 | PART | index u8 (0-based), then up to 200 bytes of the picture |

- **Codecs:** 1 = WebP, 2 = sketch only (no parts; the sketch is the picture). Numbers 3–15 are reserved for
  methods proven on the laptop: 3 = CompressAI bmshj2018-hyperprior q1, 4 = raw AV1.
- **Limits:**
  - Pictures are at most 16 parts (3,200 B), at most 512 px per side, and the sketch is at most 32 B.
  - Every packet is at most 204 B of payload, under the 219 B a PKI-encrypted direct message allows.
  - Anything outside these limits, or carrying an unknown version, is dropped.
- **Order:** parts may arrive before the HEAD. The receiver keeps them, bounded.
- **Bounds:** at most 4 unfinished pictures per sender and 8 in total. An unfinished picture that hears nothing for
  10 minutes ends as "picture did not arrive".
- **Complete:** a picture is complete when every part has arrived and the CRC-32 matches. A failed CRC discards it.

## Sending rules

- **One packet at a time.** The next packet goes only after the radio has queued the previous one and the other radio
  has acknowledged it, or after it was given up on. The phone-to-radio Bluetooth queue holds only three packets, and
  dropping one is silent.
- **Retries:**
  - A packet without an acknowledgement is sent again up to 2 more times, each with a new packet id (the firmware
    ignores a repeated id).
  - After that the whole picture ends as failed, and the person may tap to send it again.
  - Nothing is resent by itself after that.
- **Status:** a picture is "sent" only when every packet was acknowledged by the other radio. That still does not mean
  it was seen.
- **No radio:** a picture sent while no radio is ready waits ("Waiting for a radio") and starts by itself once one is.
- **WebP container:** Android's encoder adds an ICC colour profile of about 500 bytes (measured on the emulator: a flat
  31×21 picture was 548 bytes). `WebpContainer.minimal` keeps only the image (and alpha when present), so decoders assume
  sRGB, which is what was encoded. Photos are also marked as having no transparency. Without this the 500-byte Medium
  budget could never be met.
- **Time on air:** the preview shows the estimated time on air for the radio's current settings, using the LoRa
  time-on-air formula. The estimate includes no relays or retries. It warns when a picture would use more than 10 % of
  the hour's allowance (36 s of 360 s in EU 868).

## Privacy

- The picture is re-encoded from pixels, so location and camera data never leave the phone.
- Pictures are stored only in the app's private folder. They are deleted with their message and never backed up
  (`allowBackup=false` already).
- The direct message is encrypted by Meshtastic with the radio's key, like text. The app adds no encryption of its own
  yet; the accounts design will.

## Verification

- **JVM tests (core):** wire round trips; malformed, oversized and unknown-version packets; part order; duplicates;
  CRC failure; bounds and timeouts; time-on-air figures against the Semtech formula; ThumbHash against the reference
  implementation's output.
- **Radio module tests:** port-300 packets are built and parsed; port-300 packets never reach the text path.
- **Emulator:** the picture flow against the fake radio, once the emulator is free.
- **Owner phones:** two phones and two radios, 96 px default, then the other methods; the owner reports time, look and
  failures.
- **APK size:** measured before and after. The limit is raised only with the owner's agreement.
