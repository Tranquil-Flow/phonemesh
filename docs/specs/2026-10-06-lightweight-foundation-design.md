# Sub-project 1: lightweight foundation

**Date:** 2026-10-06
**Status:** design agreed; awaiting written-spec review
**Depends on:** [Phase 3 product decisions](2026-10-06-phase3-decisions.md)
**Followed by:** sub-project 2 (wire v2), sub-project 3 (UI rebuild)

## Goal

Make Phonemesh cheap to obtain and cheap to run on low-end phones in poorly connected areas, without changing what the app does or how it sounds.

**Success criteria**

- The release APK per ABI and the first-install pack download (engine plus one voice) are each measurably smaller than the 2026-10-06 baseline, with before and after numbers recorded.
- Packs can be downloaded inside the app, survive dropped connections, and are verified against hashes built into the app.
- Installed packs can be shared with another phone offline and verified on arrival.
- All existing tests stay green; transcription and voice output are unchanged unless an adopted trade passed the rules below.

## Baseline (measured 2026-10-06)

| Item | Size |
|---|---|
| `app-arm64-v8a-debug.apk` | 35.2 MB |
| `libonnxruntime.so` (arm64, uncompressed in APK) | 22.2 MB |
| `classes*.dex` (mostly `protobuf-java`) | ~8.2 MB |
| Universal debug APK | 131 MB |
| `phonemesh-en-US-whisper-tiny-piper-ljspeech-int8.zip` | 85.5 MB |
| `phonemesh-fr-whisper-tiny-piper-siwis-int8.zip` | 77.7 MB |
| Whisper Tiny `decoder.int8.onnx` inside each pack | 89.9 MB raw / 53.4 MB deflated |
| `espeak-ng-data` dictionaries for unused languages (e.g. `ru_dict` 8.5 MB raw) | ~6 MB deflated per pack |

## Non-goals

- Message compression and multi-part messages (sub-project 2).
- Any interface redesign (sub-project 3). This sub-project adds only the minimum UI the new pack flow needs inside the existing screen; the setup cards arrive in sub-project 3.
- A custom slim onnxruntime build. Candidate follow-up spike after this sub-project.
- Anything requiring the real radio, the phone's microphone or speaker, or an unlocked phone.

## 1. APK slimming

Each step is a separate commit with size measured before and after.

1. **protobuf-javalite.** Generate lite message classes for the Meshtastic protos in `radio-ble` and depend on `protobuf-javalite` instead of `protobuf-java`.
2. **No `.proto` sources in the APK.** Exclude `meshtastic/*.proto` from packaging.
3. **R8 and resource shrinking** for release builds. Keep rules cover the sherpa-onnx JNI surface (`com.k2fsa.sherpa.onnx.**` classes and members called from native code) and protobuf-lite generated classes.
4. **ABI set.** Distribute `arm64-v8a` and `armeabi-v7a` APKs only. `x86` and `x86_64` remain buildable for the emulator; the universal APK is no longer produced. `armeabi-v7a` stays because many budget phones are 32-bit.
5. **Remove unbuilt modules.** Delete `android/ble-transport/` and `android/mesh-bridge/`, which are not in `settings.gradle.kts` and duplicate the Meshtastic protos.
6. **Compressed native libraries.** Set `packaging.jniLibs.useLegacyPackaging = true` so native libraries are compressed in the APK. This favours download size over on-device storage. Both numbers are recorded.

## 2. Pack restructure

### Layout

| Pack | Contents |
|---|---|
| **Engine** `phonemesh-engine-whisper-tiny-<version>.zip` | `whisper/encoder.int8.onnx`, `whisper/decoder.int8.onnx`, `whisper/tokens.txt` |
| **Engine (optional)** `phonemesh-engine-whisper-base-<version>.zip` | same layout, Whisper Base |
| **Voice** `phonemesh-voice-<lang>-<voice>-<version>.zip` | `tts/model.onnx`, `tts/tokens.txt`, `tts/espeak-ng-data/` trimmed to the shared core files plus that language's dictionary |

Every pack also contains `pack.properties`, `SHA256SUMS`, `NOTICE.txt` and `SOURCES.json`.

Exactly one engine and at least one voice must be installed for the speech loop to run. Whisper is multilingual, so one engine serves every voice.

### Format 2

`pack.properties`:

```
format=2
kind=engine|voice
id=<stable pack id, e.g. engine-whisper-tiny or voice-fr-siwis>
version=<integer>
label=<human label>
# engine only
recognizerModel=whisper-tiny|whisper-base
# voice only
languageTag=<canonical tag, e.g. fr or en-US>
whisperLanguage=<e.g. fr>
```

Format 1 packs are rejected with a clear error. Only development phones hold them.

### Integrity: pinned manifests

Today a pack is accepted if its own `SHA256SUMS` matches its files, so any self-consistent pack installs. Format 2 adds a pin:

- The app contains a **built-in catalog**: for each known pack, its `id`, `version`, download URL, ZIP size, and the **SHA-256 of its `SHA256SUMS` file**.
- On install, the `SHA256SUMS` hash must match the catalog entry for that pack `id` and `version`; then every file is checked against `SHA256SUMS`, as today.
- The ZIP container bytes are not pinned, so a ZIP rebuilt on another phone for sharing verifies identically.
- Unknown packs (not in the catalog) are rejected. Development builds may enable a flag to accept them, off in release builds.

The existing archive safety checks (entry count, path depth, traversal, duplicates, size caps, staged install with atomic rename) stay unchanged.

### Decoder re-quantization spike

The Whisper Tiny decoder is 89.9 MB raw despite the INT8 label. The suspected cause is an FP32 token embedding (51,865 × 384 × 4 B ≈ 80 MB) left unquantized.

1. **Confirm.** List the decoder's initializers by size and dtype. If the embedding does not account for the bulk, record what does and stop.
2. **Re-quantize** offline with pinned tooling so the embedding is also INT8 (or the output projection shares it), producing a deterministic file.
3. **Accept only if all hold:**
   - sherpa-onnx loads it in the emulator native speech test;
   - transcripts of the existing EN/FR comparison clips are identical, or no worse under the scoring used in `docs/speech-comparison-summary.csv`;
   - decode time on the emulator is not worse.
4. Otherwise ship the original decoder and record the result in `docs/android-verification.md`.

The same procedure applies to the optional Base engine.

### espeak-ng trimming

The pack build keeps only the espeak-ng files needed for the voice's language: the shared phoneme tables (`phontab`, `phonindex`, `phondata`, `intonations`) plus that language's dictionary and any voice file it references. A Python test checks that phonemization of fixed EN/FR sentences is identical with the trimmed and the full data.

### Lossy candidates

The only candidate in this sub-project: an English Piper **low** voice in place of the current medium LJSpeech voice (~7 MB smaller). The build produces a blind A/B listening set from the comparison sentences for the owner to play on the laptop. It is adopted only if the owner cannot tell the voices apart; otherwise English stays on medium.

### Build scripts

`android/scripts/build-model-packs.py` and `build-base-model-packs.py` are replaced by one script producing engine and voice packs from SHA-pinned upstream inputs, runnable with the network blocked once inputs are cached. The script also emits the catalog entries (ids, versions, sizes, manifest hashes) as a generated Kotlin source checked into `core`.

## 3. Downloader, pack manager, sharing

### Downloading

- Uses Android `DownloadManager`. The app contains no other network code.
- The app gains the `INTERNET` permission. Whether `DownloadManager` requires it of the calling app is verified during implementation; if it does not, the permission is not added.
- Files download into the app-specific external files directory (no storage permission), then install through `PackStore`, then the ZIP is deleted.
- Mobile data is allowed. The size in MB is shown before the user starts the download.
- Download URLs come from the built-in catalog. The base URL is a build setting: a public Hugging Face model repository pinned to a commit once published; a local test server during development.

### Pack manager

The existing screen gains a minimal pack list: installed packs with size, download a missing engine or voice, remove a voice, import from file. The visual design of this area is replaced in sub-project 3.

- Import from file stays, starting in `Download/Phonemesh`.
- The app registers for `ACTION_VIEW` and `ACTION_SEND` of ZIP files so a received pack can be opened directly.
- Errors are distinct and user-facing: damaged file, wrong file, unknown pack, not enough space, missing engine.
- Large imports report progress.

### Sharing

- "Share the app" sends the installed APK and rebuilt pack ZIPs through the Android share sheet (`ACTION_SEND_MULTIPLE`).
- Files are served by a small built-in `ContentProvider` that exposes only the APK and pack ZIPs in a dedicated cache directory, read-only, with temporary URI grants.
- Rebuilt ZIPs are verified on the receiving phone by the pinned manifest, like any other import.

### Licence notices

Each pack's `NOTICE.txt` and the app's about information list Whisper (MIT), the Piper voices and their datasets (LJSpeech public domain; SIWIS CC-BY 4.0 with attribution), and espeak-ng (GPL-3.0) with source links. The repository adopts AGPL-3.0.

## 4. Testing and verification

### Gate before every commit

- `uv run ruff check src tests scripts android/scripts` and `ruff format --check` on the same paths; `uv run pytest`.
- From `android/`: `:core:test :radio-ble:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest`.
- Emulator instrumentation (`Phonemesh_API_35`) via `.local/run_android_instrumentation.py`, judged by the instrumentation result, never by the adb exit code. After R8 is enabled, instrumentation also runs against the minified build.

### New tests

| Area | Tests |
|---|---|
| Format 2 | valid engine and voice install; manifest hash not in catalog rejected; tampered file rejected; missing or extra file rejected; voice without engine reported; format 1 rejected |
| espeak trimming | identical phonemization of fixed EN/FR sentences, trimmed versus full |
| Decoder spike | load and decode in emulator native test; comparison-clip transcripts; decode time |
| Downloader | local HTTP server reachable from the emulator: complete download, interrupted then resumed, wrong content rejected, insufficient space handled |
| Network boundary | build check failing if app sources reference sockets or HTTP client APIs other than `DownloadManager` |
| Sharing | rebuilt ZIP verifies against the pinned manifest; provider refuses paths outside its directory and refuses writes |
| Size budget | build check failing if a release APK exceeds a ceiling set from the measured post-slimming size plus a small margin |

### Recorded measurements

In `docs/android-verification.md`, before and after: APK size per ABI (download and installed), pack sizes, emulator cold start, peak RAM during one decode. Only measured values are recorded.

## Risks

| Risk | Mitigation |
|---|---|
| R8 strips classes used from native code | Keep rules plus instrumentation run against the minified build |
| Re-quantized decoder loses accuracy | Strict accept rule; original decoder remains the fallback |
| espeak trimming drops a file a voice needs | Phonemization equivalence test; native speech test with trimmed packs |
| Hugging Face unreachable from Togo | Configurable base URL; reachability checked on a local SIM before launch; offline sharing as backup |
| Compressed native libs increase on-device storage | Both sizes measured; decision revisited if storage proves tighter than bandwidth |
