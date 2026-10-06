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
Meshtastic app before connecting in Phonemesh. Configure channel/region/keys with the
stock app first. Phonemesh does not change them. The standalone `mesh-bridge` module
records the historical AIDL investigation; it is not a runtime app dependency.

Submission/queueing, GATT write completion, peer radio reception, read and playback
are distinct events. The UI must not display a submission as confirmed delivery.
An emulator cannot qualify physical BLE/RF behavior.

## Model packs and multilingual behavior

Models are separate ZIP imports via Android's document picker. The messaging app needs
no Internet permission. An import is staged privately, bounded to 512 MiB/5000 entries,
rejects traversal/duplicates and missing required model files, and publishes atomically.
Original source/voice licences remain applicable; public redistribution needs review.

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

Wire messages remain readable `[pm1:en-US] ...` TEXT_MESSAGE_APP text, capped at 200
UTF-8 bytes including the tag. Canonical tags, Unicode preview safeguards and explicit
language selection are preserved. The Android codec can represent additional Piper
language tags; the initial Python/Kokoro registry still accepts only its own supported
intersection. Unsupported receivers must not silently switch language.

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
2. APK build, lint, ABI/package inspection and no-INTERNET-permission check.
3. Android emulator launch and real JNI Whisper/Piper tests using synthetic speech only.
4. Physical phone: BLE handshake, recording/playback, thermal/latency/memory, private
   channel send/receive with two real nodes and user-confirmed delivery.

Only completed gates may be reported as passed. A Pixel 8 Pro physical test has now
verified phone-to-Nova text, the reverse inbox path, and user-initiated playback.
Reconnect/reliability and final hardened-candidate evidence are tracked separately in
`android-verification.md`. Project licence remains proposed AGPL-3.0 pending owner confirmation;
this is a private debug/test artifact, not a public release.
