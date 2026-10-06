# Sub-project 1: lightweight foundation

**Date:** 2026-10-06
**Status:** design agreed; revised after independent review (2026-10-06); awaiting owner approval
**Depends on:** [Phase 3 product decisions](2026-10-06-phase3-decisions.md)
**Followed by:** sub-project 2 (wire v2), sub-project 3 (UI rebuild)

## Goal

Make Phonemesh cheap to obtain and cheap to run on low-end phones in poorly connected areas, without changing what the app does or how it sounds.

**Success criteria**

- The release APK per ABI and the first-install pack download (engine plus one voice) are each measurably smaller than the 2026-10-06 baseline, with before and after numbers recorded.
- Packs can be downloaded inside the app, survive dropped connections, and are verified against hashes built into the app.
- Installed packs and the APK can be sent to another phone offline, and a receiving Phonemesh app verifies any pack it imports. (The non-reader receive experience, including Android's "install unknown apps" step, is designed in sub-project 3.)
- The `armeabi-v7a` APK is built and passes the same tests, but is not distributed until its decode time has been measured on a real 32-bit phone.
- All existing tests stay green; transcription and voice output are unchanged unless an adopted trade passed the rules below.

## Baseline (measured 2026-10-06)

| Item | Size |
|---|---|
| `app-arm64-v8a-debug.apk` | 35.2 MB |
| `libonnxruntime.so` (arm64, uncompressed in APK) | 22.2 MB (7.6 MB when compressed, measured with gzip -9) |
| `classes*.dex` (mostly `protobuf-java`) | ~8.2 MB |
| Universal debug APK | 131 MB |
| `phonemesh-en-US-whisper-tiny-piper-ljspeech-int8.zip` | 85.5 MB |
| `phonemesh-fr-whisper-tiny-piper-siwis-int8.zip` | 77.7 MB |
| Whisper Tiny `decoder.int8.onnx` inside each pack | 89.9 MB raw / 53.4 MB deflated; 79.66 MB of it is one FP32 tensor, `textDecoder.token_embedding.weight` [51865, 384] |
| `espeak-ng-data` dictionaries for unused languages (e.g. `ru_dict` 8.5 MB raw) | ~6 MB deflated per pack |

## Non-goals

- Message compression and multi-part messages (sub-project 2).
- Any interface redesign (sub-project 3). This sub-project adds only the minimum UI the new pack flow needs inside the existing screen; the setup cards arrive in sub-project 3.
- A custom slim onnxruntime build. Candidate follow-up spike after this sub-project.
- Anything requiring the real radio, the phone's microphone or speaker, or an unlocked phone.
- The receive-side sharing experience for non-readers (sub-project 3).

## 1. APK slimming

Each step is a separate commit with size measured before and after.

1. **protobuf-javalite.** Generate lite message classes for the Meshtastic protos in `radio-ble` and depend on `protobuf-javalite` instead of `protobuf-java`. First confirm lite code generation succeeds on the full proto set, including `nanopb.proto` and its custom options; the dex size target is unverified until then.
2. **No `.proto` sources in the APK.** Exclude `meshtastic/*.proto`, `google/protobuf/*.proto` and `nanopb.proto` from packaging.
3. **R8 and resource shrinking** for release builds. Keep rules cover the sherpa-onnx JNI surface (`com.k2fsa.sherpa.onnx.**` classes and members called from native code) and protobuf-lite generated classes.
4. **ABI set.** Distribute `arm64-v8a` and `armeabi-v7a` APKs only. `x86` and `x86_64` remain buildable for the emulator; the universal APK is no longer produced. `armeabi-v7a` stays because many budget phones are 32-bit.
5. **Remove unbuilt modules.** Delete `android/ble-transport/` and `android/mesh-bridge/`, which are not in `settings.gradle.kts` and duplicate the Meshtastic protos. First move `mesh-bridge/README.md` (the AIDL investigation record cited by `docs/android-design.md`) and `mesh-bridge/THIRD_PARTY_MESHTASTIC.md` into `docs/`, and update the reference.
6. **Compressed native libraries.** Set `packaging.jniLibs.useLegacyPackaging = true` so native libraries are compressed in the APK (`libonnxruntime.so` 22.2 → ~7.6 MB). They are extracted at install, so installed size grows; this favours download size (priority 1) over on-device storage. Both numbers are recorded, and the choice is revisited if storage proves to be the tighter limit.

## 2. Pack restructure

### Layout

| Pack | Contents |
|---|---|
| **Engine** `phonemesh-engine-whisper-tiny-<version>.zip` | `whisper/encoder.int8.onnx`, `whisper/decoder.int8.onnx`, `whisper/tokens.txt` |
| **Engine (optional)** `phonemesh-engine-whisper-base-<version>.zip` | same layout, Whisper Base |
| **Voice** `phonemesh-voice-<lang>-<voice>-<version>.zip` | `tts/model.onnx`, `tts/tokens.txt`, `tts/espeak-ng-data/` trimmed to the shared core files plus the dictionary and language file of every catalog language (see espeak-ng trimming) |

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

- The app contains a **built-in catalog**: for each known pack, its `id`, `version`, `kind`, download URL, ZIP size, and the **SHA-256 of its `SHA256SUMS` file**.
- On install, the `SHA256SUMS` hash must match the catalog entry for that pack `id` and `version`; then every file is checked against `SHA256SUMS`, as today.
- **Strict path allowlist, enforced before any file is written:** every archive entry must be `pack.properties`, `SHA256SUMS`, `NOTICE.txt`, `SOURCES.json`, or under `whisper/` (engine packs only) or `tts/` (voice packs only). Any other entry rejects the whole archive. Today's code extracts every entry and hashes only a subset; this closes that gap. `SHA256SUMS` must list every allowlisted file except itself.
- **Size:** a download must match the catalog ZIP size exactly. An imported or shared ZIP is not size-matched (it was rebuilt), only capped at the catalog size plus a small margin (implemented as the largest catalog ZIP plus an eighth, from rebuilt packs measured up to 8.2 % larger); integrity comes from the manifest.
- **Downgrades:** installing a pack whose `version` is older than the installed pack with the same `id` is rejected.
- Unknown packs (not in the catalog) are rejected. Development builds may enable a flag to accept them; release builds cannot, and a test asserts the flag is off in release.

The existing archive safety checks (entry count, path depth, traversal, duplicates, size caps, staged install with atomic rename) stay unchanged.

### PackStore changes

`PackStore` today assumes one combined pack. It changes to:

- separate pack records for engines (`recognizerModel`) and voices (`languageTag`, `whisperLanguage`);
- a store-level readiness check: exactly one active engine and at least one voice installed, with a distinct error for each missing part;
- a voice/engine check: the voice's `whisperLanguage` must be a language the installed engine supports (present in its `tokens.txt` language tokens). A `tokens.txt` with no `<|xx|>` language lines means every Whisper language: the sherpa-onnx Whisper exports keep the language tokens in the model metadata, and every accepted `recognizerModel` is multilingual. (Amended: real engines have no language lines, so they support every language.)

### Decoder re-quantization spike

The Whisper Tiny decoder is 89.9 MB raw despite the INT8 label. Inspection confirmed the cause: `textDecoder.token_embedding.weight` [51865, 384] is FP32 and accounts for 79.66 MB; every MatMul weight is already INT8. The weight is **tied**: it feeds both the token lookup (`Gather`) and the output logits (through `Transpose` → `Cast`), so quantizing it affects every decoding step's scores, not only the lookup.

1. **Re-quantize** offline with pinned tooling so that shared weight is INT8 for both uses, producing a deterministic file (expected decoder ~30 MB raw).
2. **Adopt automatically only if all hold:**
   - sherpa-onnx 1.13.8 loads it in the emulator native speech test;
   - transcripts of **every** existing EN/FR comparison clip are identical to the current decoder's;
   - decode time on the emulator is not worse.
3. If any transcript differs, the owner gets a side-by-side diff of every changed transcript and decides. Faster or smaller is never enough on its own.
4. If it is rejected or does not load, ship the original decoder and record the result in `docs/android-verification.md`.

The same procedure applies to the optional Base engine.

### espeak-ng trimming

The pack build keeps only the espeak-ng files needed for the catalog's languages: the shared phoneme tables (`phontab`, `phonindex`, `phondata`, `intonations`) plus each catalog language's dictionary and language file. Every voice pack carries this same union, because sherpa-onnx sets up espeak-ng once per process from the first voice loaded, and the app keeps one shared copy of it (amended: the first design trimmed each voice to its own language). Two required checks:

- **Native (gate):** an emulator instrumentation test synthesizes fixed EN/FR sentences through sherpa-onnx (whose espeak-ng is compiled into `libsherpa-onnx-jni.so`) with the trimmed and the full data, one process per variant, and requires identical output samples.
- **Build-time:** the pack script fails if the trimmed set omits a file the full set's language entry references.

### Lossy candidates

The only candidate in this sub-project: an English Piper **low** voice in place of the current medium LJSpeech voice (~7 MB smaller). The build produces a blind A/B listening set from the comparison sentences for the owner to play on the laptop. It is adopted only if the owner cannot tell the voices apart; otherwise English stays on medium.

### Build scripts

`android/scripts/build-model-packs.py` and `build-base-model-packs.py` are replaced by one script producing engine and voice packs from SHA-pinned upstream inputs, runnable with the network blocked once inputs are cached. The script also emits the catalog entries (ids, versions, sizes, manifest hashes) as a generated Kotlin source checked into `core`.

## 3. Downloader, pack manager, sharing

### Downloading

- Uses Android `DownloadManager`. The app gains the `INTERNET` permission, which `DownloadManager` requires of the calling app (Android API reference).
- **Network boundary:** one class (`PackDownloads`) is the only code that builds URLs or calls `DownloadManager`, and URLs come only from the built-in catalog. A build task inspects the compiled release dex and fails if networking APIs (`java.net.Socket`, `java.net.URL.openConnection`/`openStream`, `HttpURLConnection`, `javax.net.ssl`, `android.webkit.WebView`, `DownloadManager.enqueue`) are referenced outside that class or outside an explicit, reviewed list of library references. This is a guardrail against regressions, not an operating-system sandbox.
- Release builds keep `usesCleartextTraffic="false"`. Debug builds add a network security config permitting cleartext only to the emulator host (`10.0.2.2`) for the local test server, in case `DownloadManager` enforces the app's cleartext policy.
- Files download into the app-specific external files directory (no storage permission), then install through `PackStore`, then the ZIP is deleted.
- Mobile data is allowed. The size in MB is shown before the user starts the download.
- Download URLs come from the built-in catalog. The base URL is a build setting: a public Hugging Face model repository pinned to a commit once published; a local test server during development.

### Pack manager

The existing screen gains a minimal pack list: installed packs with size, download a missing engine or voice, remove a voice, import from file. The visual design of this area is replaced in sub-project 3.

- Import from file stays, now starting in `Download`, where Quick Share places received files.
- The app registers for `ACTION_VIEW` and `ACTION_SEND` of ZIP files so a received pack can be opened directly.
- Errors are distinct and user-facing: damaged file, wrong file, unknown pack, not enough space, missing engine.
- Large imports report progress.

### Sharing (plumbing only)

- "Share the app" sends the installed APK and rebuilt pack ZIPs through the Android share sheet (`ACTION_SEND_MULTIPLE`). Phonemesh is distributed as a single per-ABI APK, so the installed base APK is the complete app.
- Files are served by a small built-in `ContentProvider` that exposes only the APK and pack ZIPs in a dedicated cache directory, read-only, with temporary URI grants.
- Rebuilt ZIPs are verified on the receiving phone by the pinned manifest, like any other import.
- **Receiver flow, documented here and designed in sub-project 3:** Quick Share puts files in `Download`; Bluetooth location varies by device. The APK needs Android's per-app "install unknown apps" permission (Android 8+), a system screen Phonemesh cannot change. Received ZIPs are opened by tapping them (the app's `ACTION_VIEW` filter) or through import from file, which starts in `Download`. Sub-project 3 designs the icon-led guide for these steps.

### Licence notices

Each pack's `NOTICE.txt` and the app's about information list Whisper (MIT), the Piper voices and their datasets (LJSpeech public domain; SIWIS CC-BY 4.0 with attribution), and espeak-ng (GPL-3.0) with source links. The repository adopts AGPL-3.0.

## 4. Testing and verification

### Gate before every commit

- `uv run ruff check src tests scripts android/scripts` and `ruff format --check` on the same paths; `uv run pytest`.
- From `android/`: `:core:test :radio-ble:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest`.
- Emulator instrumentation (`Phonemesh_API_35`) via `.local/run_android_instrumentation.py`, judged by the instrumentation result, never by the adb exit code. After R8 is enabled, instrumentation runs against the `minifiedDebug` build.

### New tests

| Area | Tests |
|---|---|
| Format 2 | valid engine and voice install; manifest hash not in catalog rejected; tampered file rejected; missing or extra file rejected; voice without engine reported; format 1 rejected |
| espeak trimming | emulator: identical synthesized samples for fixed EN/FR sentences through sherpa-onnx, trimmed versus full data; build: no referenced file missing |
| Decoder spike | load and decode in emulator native test; comparison-clip transcripts; decode time |
| Downloader | local HTTP server reachable from the emulator (`10.0.2.2`) supporting `Range`: complete download; **interrupted then resumed** (server drops the connection after N bytes on the first request; the test asserts the system retry completes and the server saw a `Range` request); wrong content rejected; wrong size rejected; **insufficient space** (a small-data-partition test AVD, plus a unit test of the pre-download free-space check) |
| Network boundary | the dex-inspection build task above, with a fixture class that must make it fail |
| Sharing | rebuilt ZIP verifies against the pinned manifest; provider refuses paths outside its directory and refuses writes |
| Size budget | build check failing if a release APK exceeds a ceiling set from the measured post-slimming size plus a small margin |
| Minified build | a `minifiedDebug` build type (R8 on, debug signing) set as `testBuildType`, so instrumentation runs against R8 output |
| Pack store | path allowlist rejects `META-INF/x` and a top-level extra file; downgrade rejected; engine/voice readiness and language checks; dev flag off in release |

### Recorded measurements

In `docs/android-verification.md`, before and after: APK size per ABI (download and installed), pack sizes, emulator cold start, peak RAM during one decode. Only measured values are recorded.

`armeabi-v7a` decode time cannot be measured on the available hardware (the Pixel 8 Pro does not run 32-bit apps; the emulators are 64-bit). It is recorded as unmeasured until a borrowed 32-bit phone is available; that measurement is the exit criterion for distributing the v7a APK.

## Risks

| Risk | Mitigation |
|---|---|
| R8 strips classes used from native code | Keep rules plus instrumentation run against the minified build |
| Re-quantized decoder loses accuracy | Strict accept rule; original decoder remains the fallback |
| espeak trimming drops a file a voice needs | Phonemization equivalence test; native speech test with trimmed packs |
| Tied embedding makes INT8 decoding diverge | Identical-transcript gate; owner diff review; original decoder as fallback |
| `armeabi-v7a` too slow on 32-bit phones | Decode time measured on a real 32-bit phone before v7a ships |
| Hugging Face unreachable from Togo | Configurable base URL; reachability checked on a local SIM before launch; offline sharing as backup |
| Compressed native libs increase on-device storage | Both sizes measured; decision revisited if storage proves tighter than bandwidth |
