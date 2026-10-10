# Android architecture and scope

## Product path

Android 8.0/API 26+ native Kotlin application: explicit record/stop → local multilingual
Whisper Tiny INT8 → editable transcript → local Piper preview → recipient/channel and
send-confirmation dialog → Meshtastic text-only radio packet. Incoming text is shown
in an inbox and spoken only after a playback tap. There is no translation, cloud speech,
background microphone or automatic playback.

Piper is the first mobile default: small, single-language quantized voices avoid requiring
Kokoro's larger multilingual voice stack on every phone. This is an engineering choice,
not a measured guarantee for every handset. sherpa-onnx 1.13.8 supplies one CPU runtime
for Whisper and Piper; two compute threads, 30-second recording cap, bounded queues.
The official AAR packages arm64-v8a, armeabi-v7a, x86 and x86_64. Device support also
depends on Android version, BLE hardware, available memory and native runtime behavior.

## Corrected Meshtastic integration

The earlier exported-AIDL assumption is not valid for current Meshtastic Android.
At official commit `21031efe4244e575dff1b879ed555982d94da54d`,
`androidApp/src/main/AndroidManifest.xml` declares
`org.meshtastic.core.service.MeshService` with `android:exported="false"`.

The app therefore uses **direct Meshtastic Bluetooth GATT**, with official protobuf
schemas, rather than pretending a legacy AIDL bridge works against the current app.
Only one application should hold a node's Bluetooth connection: disconnect the stock
Meshtastic app before connecting in Phonemesh. Phonemesh may change the radio's LoRa region, and only the region, after a tap from the person (see `docs/specs/2026-10-07-radio-region-design.md`). Channels, keys and every other setting are configured with the stock app; Phonemesh does not change them. The AIDL investigation record in
`docs/android-aidl-investigation.md` is historical; it is not a runtime app dependency.

**Pairing inside one connection.** `BleMeshTransport.connect` reads the radio's bond state.
A bonded radio is opened at once, with the usual limits: 10 s per GATT operation, 30 s for
the whole connection, 35 s in `AppCore`. An unbonded radio is bonded first
(`BluetoothDevice.createBond()`, covered by the `BLUETOOTH_CONNECT` permission the app
already holds; `BLUETOOTH_ADMIN` up to Android 11) and the GATT link is opened only when
Android reports `BOND_BONDED`, so no GATT operation is waiting, and none can time out,
while the person types the code. `PairingWait` holds that state: it listens to
`ACTION_BOND_STATE_CHANGED` for the target radio from `connect` until the connection ends,
gives pairing 90 s, and ends the attempt once, through the usual failure report, when the
bond goes back to `BOND_NONE` or the 90 s run out. If `createBond()` cannot be started the
link is opened as before; should Android then start pairing by itself in the middle of that
connection, operation timeouts, the connection deadline and failed steps are ignored while
the bond state is `BOND_BONDING`, and the link is replaced by a fresh one, once, on
`BOND_BONDED`. Bond reports never touch a connection that is ready. The transport tells the
app with `MeshRadio.Listener.onPairing`: `AppCore` stops its 35 s clock while pairing and
restarts it when pairing ends, so the screens keep showing "Connecting…" and nothing starts
a second connection. The region flow's reconnects use the same `connect` and are unchanged
for a bonded radio. The receiver is registered as exported on Android 13 and later because
bond reports come from Android's Bluetooth app, not from the system user; the broadcast is
protected, so no other app can send it. The pairing logic is tested with fakes only; its
behaviour with a real radio and Android's own pairing prompt is not verified by tests.

**Tips and pictures in the chat.** A tip's picture and the cut-off notice's picture are as
wide as their card (2:1), with the sentence, speaker and close button under them. The draft
and the tip share one column that never takes the room of the bar below it nor of 96 dp of
messages; pictures shrink first, and a window under 560 dp high keeps the small picture
beside the words. The "delivery" tip shows only in a private chat, because a group message
ends at "Sent". The draft bar's Listen control is a filled round button (yellow; green
while playing), and the "listen" picture draws the bar as it is: Clear, Listen, Send. See
`docs/specs/2026-10-07-visual-guide-design.md`.

Submission/queueing, GATT write completion, peer radio reception, read and playback
are distinct events. The UI must not display a submission as confirmed delivery.
An emulator cannot qualify physical BLE/RF behavior.

## Model packs and multilingual behavior

Models are separate ZIP packs: a speech engine, and one voice per language. A pack reaches
the app by download, from a friend's phone, or as a ZIP chosen in Android's document
picker. Downloading packs is the app's only network use and the only reason it holds the
`INTERNET` permission. Every pack, however it arrives, is staged privately, bounded to
512 MiB/5000 entries, rejects traversal/duplicates and missing required model files, and
publishes atomically.

**Where packs are downloaded from.** The address is
`https://github.com/Tranquil-Flow/phonemesh-packs/releases/download/packs-1/` followed by
the file name in the app's built-in catalog (`GeneratedPackCatalog`). It is HTTPS only;
GitHub redirects the request to another HTTPS host. Four packs are published there, and
they are the whole catalog:

| File | Pack |
|---|---|
| `phonemesh-engine-whisper-tiny-1.zip` | Whisper Tiny engine |
| `phonemesh-engine-whisper-base-1.zip` | Whisper Base engine |
| `phonemesh-voice-fr-siwis-1.zip` | French voice (Siwis) |
| `phonemesh-voice-en-US-ljspeech-1.zip` | English voice (LJ Speech) |

The catalog holds, for each pack, the ZIP's size and the SHA-256 of its `SHA256SUMS`
file, which in turn lists the SHA-256 of every file in the pack. A release build installs
a ZIP only if its `SHA256SUMS` has the hash in the catalog and every file matches
`SHA256SUMS`; this holds for a downloaded, received or picked ZIP alike, so the server is
not trusted for the content. A download must also have the catalog's size. Debug builds
(`ALLOW_UNKNOWN_PACKS`) skip the catalog hash so test packs can be installed: they check
the size of a download and that every file matches the pack's own `SHA256SUMS`.

The address is a build setting (`android/app/build.gradle.kts`). Release builds use the
address above. Debug builds use `http://10.0.2.2:8777/`, the test pack server on the
development machine as the emulator sees it, which a real phone cannot reach; a debug
build for a phone is made with
`-Pphonemesh.packBaseUrl=https://github.com/Tranquil-Flow/phonemesh-packs/releases/download/packs-1/`.
The property replaces the address in every build type and must be an `https://` address
ending with `/`, or the build fails. Cleartext traffic is off in release, and allowed in
debug only to `10.0.2.2`.

The English voice built from the Lessac recordings (`voice-en-US-lessac-low`) is not
published and not in the catalog: the licence of its training data allows research use
only and forbids redistribution. `android/scripts/build_packs.py` refuses to add it to a
catalog. Original source and voice licences remain applicable to the published packs;
each ZIP carries them in `NOTICE.txt` and `SOURCES.json`.

```text
pack.properties          # format=1, languageTag, whisperLanguage, label; optional recognizerModel
SHA256SUMS               # required coverage of every runtime asset + pack.properties
whisper/encoder.int8.onnx
whisper/decoder.int8.onnx
whisper/tokens.txt
tts/model.onnx
tts/tokens.txt
tts/espeak-ng-data/...
```

Prepared packs: English (en-US/Whisper en/Piper LJSpeech medium INT8) and French
(fr/Whisper fr/Piper Siwis low INT8). The pipeline is pack-driven, not hard-coded to
these two languages. Additional converted Piper voices must match a Whisper-supported
language and pass the same import/inference tests; their quality is not inferred from
the first two packs. Kokoro mobile integration is not included in this first APK.

The hardening pass adds optional `recognizerModel=whisper-base` packs, alongside
`whisper-tiny` (the default for legacy packs). The language selector and per-language
model selector are separate. Model choice is saved per language; importing Base does
not remove Tiny. Base quality must be measured, not assumed. Silent synthetic-input
comparisons do not establish conversational microphone accuracy.

The import picker initially targets **Pixel/internal storage → Download → Phonemesh**:
the separate Downloads provider may omit ZIPs copied via USB even when they exist.

While the numbered setup guide shows (`SetupView.Guide`), the chats screen shows only
the header and the steps: the chat list, the "No messages yet" hint and the ➕ button are
hidden, and the steps may use the whole height under the header, scrolling only if they
are taller than that. Step 3's own button ("Try it") opens the group chat, so the hidden
list is not needed to finish setup. The list, the hint and ➕ appear when the guide ends,
together with the green "Ready" card (`SetupView.Complete`), and stay from then on. After
setup a problem card is limited to three quarters of that height, so the chats always
keep some room. A message that arrives during setup is stored as usual and is seen in the
list once the guide has ended.

Setup step 1 ("Download the voice") offers two ways, numbered like the two parts of the
step's how-to picture: **1 · Download** and **2 · From a friend**. A lost-voice card after
setup shows one picture without numbered parts, so there the same two buttons carry no
numbers.

- **Download** starts the pack download, as before.
- **From a friend** opens a short guide inside the step's card; it never opens the file
  picker by itself. The guide shows two small how-to pictures with one sentence each
  (the friend opens Settings → "Share the app"; accept the file on this phone, then tap
  it), a secondary **Choose the file** button that opens the document picker described
  above, and **Back**. The system back button also closes the guide. The open guide
  survives rotation and closes by itself once the voice is installed. A received pack
  that is tapped and opened with Phonemesh still goes straight to the "install this
  pack?" question.
- The guide's two sentences have no recorded clip, so their speaker button appears only
  when a voice is ready to read them; the step's own two sentences keep their clips.

**Emphasis without internet.** The app does not hold `ACCESS_NETWORK_STATE` and does not
add it, so it cannot ask the phone whether it is online. Its only sign is its own pack
download: one that failed without reaching the server (`ERROR_HTTP_DATA_ERROR`,
`ERROR_CANNOT_RESUME`), or one that `DownloadManager` reports paused waiting for the
network. While that sign is present, "From a friend" is the filled button and Download
is outlined. Otherwise Download is the filled button. The sign is cleared by the next
download start or success. The decision is `SetupPlan.voiceChoices` (pure Kotlin) fed by
`DeviceAccess.noInternet()`, which tests replace.

**A running download.** Its progress bar takes the place of the Download button, and
"From a friend" always stays under the bar (outlined unless the sign above is present).
An offline phone can show a download that sits at 0 % without reporting a pause for the
network, so the sign alone cannot be what keeps the second way reachable. A pack
installed from a file while its own download is still running ends that download; the
downloads of other packs go on.

On the sender's side, the note shown when "Share the app" opens the share sheet suggests
Quick Share first and Bluetooth when it is missing.

Wire messages remain readable `[pm1:en-US] ...` TEXT_MESSAGE_APP text, capped at 200
UTF-8 bytes including the tag. Canonical tags, Unicode preview safeguards and explicit
language selection are preserved. The Android codec can represent additional Piper
language tags; the initial Python/Kokoro registry still accepts only its own supported
intersection. Unsupported receivers must not silently switch language.

## Welcome story pacing

The first screen's story (`StoryTimeline`, drawn by `StoryView`) has 10 s of action
(`DURATION_MS`), addressed by a story time from 0 to 1 (`StoryTimeline.at`). On screen
the action plays at that speed, and each of the five numbered steps then rests so that
it is shown for `STEP_MS` = 4,000 ms; a step whose action already lasted that long
would not rest. The un-numbered caption is shown from the closing (500 ms of action)
across the loop's seam to the end of the opening (600 ms), and rests between the two for
the 2,900 ms that are left of `STEP_MS`. The loop on screen is `LOOP_MS` = 24,000 ms,
six captions of 4,000 ms each, computed from `STEP_MS`.

| Caption | Action | Rest | Shown | Rests at action time |
|---|---|---|---|---|
| un-numbered | 500 ms closing + 600 ms opening | 2,900 ms | 4,000 ms | 0 ms (the loop's first instant) |
| 1 | 2,400 ms | 1,600 ms | 4,000 ms | 2,800 ms |
| 2 | 1,600 ms | 2,400 ms | 4,000 ms | 3,700 ms |
| 3 | 1,600 ms | 2,400 ms | 4,000 ms | 6,000 ms |
| 4 | 1,000 ms | 3,000 ms | 4,000 ms | 7,000 ms |
| 5 | 2,300 ms | 1,700 ms | 4,000 ms | 8,900 ms |

A step rests on the last picture before it starts to change into the next step's, not
on the instant its caption changes: the caption changes while a camera move or a fade
is under way. What is left of the step's action (200 to 900 ms) plays after the rest,
still under its caption. Step 2 is the one compromise: the camera's pull-back starts
100 ms before the last letter is typed, so the rest waits for the whole word, with the
camera 2 % into its move.

The un-numbered caption's rest sits at the start of the loop, on the story's first
picture (`at(0)`, the whole scene in view), so the first launch begins with it: 2,900 ms
of rest, the 600 ms opening, then step 1 at 3,500 ms. The story's last picture (`at(1)`)
shows the same as its first (the two differ only in parts drawn with no opacity), so the
loop passes from the closing into the rest without a jump.

`StoryTimeline.beat(ms)` maps a time in the loop to the story time and the rest it falls
in; `paced(ms)` returns the picture. During the action `paced` equals `at` exactly.
During a rest it is the held picture with one value, the breath (0 to 1, a whole number
of raised-cosine cycles of about `BREATH_MS` = 1,600 ms, so it is 0 with no speed at both
ends of the rest), applied as: a raised phone up to `PHONE_BOB` = 1.5 scene units higher,
the bubble up to `BUBBLE_BOB` = 2 higher, her open mouth, the sound bars and his sound
waves up to `PULSE` = 30 % smaller or fainter, ring radii up to `RING_PULSE` = 10 %
smaller. A radio light that is on in the held picture keeps blinking at its usual rate.
Camera, captions, letters and every opacity stay as held. In the un-numbered caption's
rest nothing is raised or lit, so there the breath lifts the two lowered phones, with the
hands that hold them, by up to `PHONE_BOB` and brings them back, twice in the rest; no
light, ring, bubble or sound mark appears.

`StoryView.freezeAt(t)` still takes a story time from 0 to 1 and shows `at(t)` without
any breath, so screenshots and the reduced-motion frame (`STILL` = 0.54) are unchanged.
A frozen or stopped picture resumes from `StoryTimeline.msOf(t)`, the first instant the
loop shows that story time.

## Privacy and lifecycle

- Microphone permission only after a record action; permission checked again at capture.
- Recording stays in memory, ends on Stop/backgrounding or the 30-second cap.
- Inference runs off the UI thread; transcript errors remain visible/editable.
- Model/runtime failure closes the cached speech engine so a later attempt can recover.
- Only confirmed text reaches the radio; no audio/embeddings/paths in the radio payload.
- Playback is explicit, stops on backgrounding, and does not run while recording.
- App backups disabled; transcript UI protected from ordinary screenshots.
- Bluetooth/location permissions are platform requirements, not permission to collect
  location history. No location history or speech telemetry is uploaded.

## Verification gates

1. JVM codec/import/protobuf tests, including malformed input and exact byte budgets.
2. APK build, lint, ABI/package inspection and the network-boundary check (networking APIs only in `PackDownloads`).
3. Android emulator launch and real JNI Whisper/Piper tests using synthetic speech only.
4. Physical phone: BLE handshake, recording/playback, thermal/latency/memory, private
   channel send/receive with two real nodes and user-confirmed delivery.

Only completed gates may be reported as passed. A Pixel 8 Pro physical test has now
verified phone-to-Nova text, the reverse inbox path, and user-initiated playback.
Reconnect/reliability and final hardened-candidate evidence are tracked separately in
`android-verification.md`. Project licence remains proposed AGPL-3.0 pending owner confirmation;
this is a private debug/test artifact, not a public release.
