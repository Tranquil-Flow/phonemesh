# Android architecture and scope

## Product path

Android 8.0/API 26+ native Kotlin application (target API 35): record/stop → local
Whisper Tiny or Base → editable transcript → optional local Piper preview → explicit
Send in the conversation → Meshtastic, directly or through a local hub. Audio never
crosses the radio. No translation, cloud speech or background microphone. Playback is
manual by default; an explicit Settings opt-in reads new messages while the app is open.
Keep that switch off for manual-only playback. See the [Android quick-start](../README.md#setting-up).

This describes v0.1.0 (released 2026-10-25; see the [release notes](releases/v0.1.0.md)).
Release APKs use a dedicated public signer, and add-me links use
`https://tranquil-flow.github.io/phonemesh/add`. Account recovery/transfer is later work,
not a v0.1 feature. For an overview, start with [how Phonemesh works](how-it-works.md).

Piper is the first mobile default: small, single-language quantized voices avoid requiring
Kokoro's larger multilingual voice stack on every phone. This is an engineering choice,
not a measured guarantee for every handset. sherpa-onnx 1.13.8 supplies one CPU runtime
for Whisper and Piper; two compute threads, bounded queues. The UI recording cap is
**15 seconds**, set by `RecordGesture.LIMIT_MS` and used by `AppCore.recordLimitMs`;
the speech engine's separate 30-second input validator is not the UI cap.
The upstream AAR includes x86 variants, but this app packages **arm64-v8a** and
**armeabi-v7a** splits only. Device support also
depends on Android version, BLE hardware, available memory and native runtime behavior.

## Corrected Meshtastic integration

The earlier exported-AIDL assumption is not valid for current Meshtastic Android.
At official commit `21031efe4244e575dff1b879ed555982d94da54d`,
`androidApp/src/main/AndroidManifest.xml` declares
`org.meshtastic.core.service.MeshService` with `android:exported="false"`.

The app therefore uses **direct Meshtastic Bluetooth GATT**, with official protobuf
schemas, rather than pretending a legacy AIDL bridge works against the current app.
Only one application should hold a node's Bluetooth connection: disconnect the stock
Meshtastic app before connecting in Phonemesh. Phonemesh may change the radio's LoRa
region, and only the region, after explicit confirmation (see the [region design](specs/2026-10-07-radio-region-design.md)).
Channels, keys and every other setting are configured with the stock app; Phonemesh
does not change them. The [AIDL investigation](android-aidl-investigation.md) is
historical, not a runtime app dependency.

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
[visual-guide design](specs/2026-10-07-visual-guide-design.md).

Submission/queueing, GATT write completion, peer radio reception, read and playback
are distinct events. The UI must not display a submission as confirmed delivery.
An emulator cannot qualify physical BLE/RF behavior.

## Accounts, wire formats and hub routes

The setup guide now orders voice → name/colour → radio → add someone → say hello.
Its radio step still uses direct-radio readiness; do not infer complete hub-only
first-run setup from hub-only conversation eligibility. Accounts are local identities,
independent of a particular radio. People
add each other using QR codes or add-me links, accept requests and compare six ordered
safety pictures through a trusted channel. Old radio-addressed private chats are
read-only; a newly established account session is not a recovery of a lost account.

Private account messages use the encrypted accounts protocol (`WireV2`) on port 301; hub
acknowledgements use 302. Shared named messages/replies/reactions use port 303 and are
protected only by the Meshtastic channel key: a displayed name is claimed, not proven.
Pictures have their own multipart format. Do not describe every Android packet as
readable `[pm1:...]` stock-client text. Legacy tagged TEXT_MESSAGE_APP traffic remains
a compatibility path with its own 200-byte UTF-8 limit. Actual draft budgets depend
on chat type and framing; a cut transcript is marked and remains editable before Send.

Hub routing prefers Wi-Fi, then BLE, then an available direct radio. The Wi-Fi client
uses Android's selected local network and its **IPv4 default gateway**, not a discovered
arbitrary host. This join needs API 29+. A Mac that is just another client on the AP
does not meet that topology. A hub card contains Wi-Fi credentials, HTTPS port and a
public-key pin; it does not create an AP. Hub enrollment can show a Wi-Fi join prompt
even when BLE is intended.

The Mac BLE bridge forwards opaque TLS streams to the same Mac's JVM HTTPS listener.
The phone checks the card's pin on both transports. A linked/advertised hub is not yet
authenticated readiness; the current `canTalk()` accepts a usable hub only after a
pinned answer on that exact wire, alongside ready speech packs. Disconnect/replacement
must not reuse a previous wire's readiness. Foreground discovery includes a 60-second
retry; this is source behavior awaiting final qualification, not a recovery-time promise.
Background hub use requires the explicit opt-in foreground service, not merely enrollment.

The hub stores encrypted private envelopes without private-message decryption keys;
shared content and metadata are not hidden from it. Hub acceptance is **Reached the
hub**, never automatically recipient **Delivered**. Mailbox retention is configurable;
delivery, delayed collection, duplicates and restart behavior need their own checks.
See [hub setup/lifecycle](../android/hub/README.md) and [native BLE bridge](../hub-ble-bridge/README.md).

## Model packs and multilingual behavior

Models are separate ZIP packs: a speech engine, and one voice per language. A pack reaches
the app by download, from a friend's phone, or as a ZIP chosen in Android's document
picker. Network use includes HTTPS pack downloads **and local pinned-TLS hub traffic**;
`INTERNET` is not evidence of cloud speech. Hub traffic is bound to the selected local
Wi-Fi network or carried over BLE. Every pack, however it arrives, is staged privately, bounded to
512 MiB/5000 entries, rejects traversal/duplicates and missing required model files, and
publishes atomically.

**Where packs are downloaded from.** The address is
`https://github.com/Tranquil-Flow/phonemesh-packs/releases/download/packs-1/` followed by
the file name in the app's built-in catalog (`GeneratedPackCatalog`). It is HTTPS only;
GitHub redirects the request to another HTTPS host. The built-in catalog lists these
four pack files; this document is not a fresh availability check of the server:

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

The address is a build setting ([app build configuration](../android/app/build.gradle.kts)). Release builds use the
address above. Debug builds use `http://10.0.2.2:8777/`, the test pack server on the
development machine as the emulator sees it, which a real phone cannot reach; a debug
build for a phone is made with
`-Pphonemesh.packBaseUrl=https://github.com/Tranquil-Flow/phonemesh-packs/releases/download/packs-1/`.
The property replaces the address in every build type and must be an `https://` address
ending with `/`, or the build fails. Cleartext traffic is off in release, and allowed in
debug only to `10.0.2.2`.

The English voice built from the Lessac recordings (`voice-en-US-lessac-low`) is not
published and not in the catalog: the licence of its training data allows research use
only and forbids redistribution. [The pack builder](../android/scripts/build_packs.py) refuses to add it to a
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

On the tested Pixel, the import picker targets **internal storage → Download → Phonemesh**:
the separate Downloads provider may omit ZIPs copied via USB even when they exist.

While the numbered setup guide shows (`SetupView.Guide`), the chats screen shows only
the header and the steps: the chat list, the "No messages yet" hint and the ➕ button are
hidden, and the steps may use the whole height under the header, scrolling only if they
are taller than that. The final "Say hello" step's "Try it" button opens the group chat, so the hidden
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

**Emphasis without internet.** The setup button emphasis uses its own pack download
signals, not a general online/offline verdict. The app now holds `ACCESS_NETWORK_STATE`
for hub networking. The signs are a pack download that failed without reaching the
server (`ERROR_HTTP_DATA_ERROR`, `ERROR_CANNOT_RESUME`), or one that `DownloadManager`
reports paused waiting for the network. While that sign is present, "From a friend" is the filled button and Download
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

Canonical language tags, Unicode preview safeguards and explicit language selection
apply across the relevant codecs. The Android codec can represent additional Piper
language tags; the separate Python/Kokoro registry accepts only its own supported
intersection. Unsupported receivers must not silently switch language. Account/shared
wire formats and the legacy text budget are distinguished above.

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
- Recording stays in memory, ends on Stop/backgrounding or the 15-second UI cap.
- Inference runs off the UI thread; transcript errors remain visible/editable.
- Model/runtime failure closes the cached speech engine so a later attempt can recover.
- Voice sends confirmed transcript text, not audio/embeddings/local paths. Separately,
  picture messages carry encoded image data over multiple packets.
- Playback is manual by default (optional incoming read-aloud while open), stops on
  backgrounding, and does not run while recording.
- App backups disabled; transcript UI protected from ordinary screenshots.
- History/picture files rely on Android app storage and file encryption, not a separate
  application-level encrypted transcript database. Account/session secrets are sealed
  with Android Keystore-backed AES-GCM; this does not encrypt every history field.
- Hub cards/config/startup logs include sensitive provisioning information (including
  Wi-Fi passwords); they are not public diagnostic or source-release material.
- Bluetooth/location permissions are platform requirements, not permission to collect
  location history. No location history or speech telemetry is uploaded.

## Verification gates

1. JVM codec/import/protobuf tests, including malformed input and exact byte budgets.
2. APK build, lint, ABI/package inspection and the network-boundary check. Its reviewed
   owners include `PackDownloads`, `HubClient`, `HubEngineTls` and `WifiHubLink`; inspect
   [the checker](../android/scripts/check_network_boundary.py) and [task configuration](../android/app/build.gradle.kts),
   not the obsolete pack-only boundary.
3. Android emulator launch and real JNI Whisper/Piper tests using synthetic speech only.
4. Physical phone: BLE handshake, recording/playback, thermal/latency/memory, private
   channel send/receive with two real nodes and user-confirmed delivery.

Only completed gates on the identified artifact may be reported as passed. Recorded
physical coverage includes Fairphone 3/API 29, Pixel 8 Pro/API 36 and firmware 2.7.26
in the exercised setup. Earlier bidirectional BLE/pinned-TLS/Mac/USB hub text delivery
and explicit playback do not prove production Wi-Fi delivery, hub-only voice on the
changed candidate, automatic restart recovery, or universal hardware support. Those
final-candidate acceptance checks remain separate; emulator/fake paths are not RF proof.

Build prerequisites, the public pinned runtime downloader, unsigned-release limitation
and proposed publication gates are in [the root README](../README.md#build-android-from-source).
No private build input is required by that documented source path; an isolated clean
export still must actually be built and checked. Project licence is
[AGPL-3.0](../LICENSE); distribution notices/source materials remain a publication gate.
