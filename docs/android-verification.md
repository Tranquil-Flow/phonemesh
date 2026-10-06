# Android hardening verification — 2026-10-03

## Current result

The final hardening APK was built, tested and installed on the Pixel 8 Pro. The APK pulled back from the phone matches the built artifact byte-for-byte (SHA-256 below). The optional English and French Whisper Base packs were built from verified local assets without network access during assembly and copied to the phone's `Download/Phonemesh` directory. They remain optional; the user's existing Tiny pack and selection were not replaced by the benchmark.

## Implemented

- Stable French connected/disconnected banner, separate operation feedback and optional raw diagnostics.
- Explicit last-radio reconnect; no automatic reconnect or message replay.
- Per-packet SUBMITTING / QUEUED / ACKNOWLEDGED / FAILED / UNCONFIRMED states. ACKNOWLEDGED means a correlated acknowledgement from the destination radio, not human reading or playback.
- Routing parser requires explicit error_reason; route discovery and empty payloads cannot masquerade as ACKs. Destination/local-node/request-ID correlation; unrelated errors cannot terminate pending sends.
- Duplicate/invalid IDs rejected; per-send expiry; delayed callbacks cannot turn expired sends into acknowledged ones. Disconnect invalidates pending sends without replay. Pre-write failures emit an observable status even before tracking begins.
- Radio readiness requires local node identity and hop configuration. Routine drain/scan diagnostics do not replace the readiness banner.
- Tiny/Base model selector with per-language preferences, backward-compatible Tiny metadata, and SAF import starting at the filesystem provider's Download/Phonemesh folder.

## Actual gates

| Gate | Result |
|---|---|
| Python tests | 130 passed |
| Ruff (`src tests`) | Passed |
| Android JVM tests | 49 passed: core 10, radio-ble 39; no failures/errors/skips |
| APK + Android-test APK + Android lint | Passed |
| Final emulator instrumentation | 7 passed: UI status/delivery/model selection, permission/app boundaries, actual offline native speech |
| Silent Pixel Tiny/Base instrumentation | 1 passed, exactly 12 real inference calls |
| Paired benchmark input identity | All six Tiny/Base input PCM SHA-256 pairs match |
| Installed APK equality | Verified by pulling phone APK and comparing SHA-256 |
| Internet permission | Absent from final APK |
| Base ZIP copies on phone | SHA-256 equals the local packs |

Final APK: `android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

SHA-256: `fb2e4a141b0b1f4a665fa4a4d6729a17d079fda56573d7a7250de72502f6d953`

Rollback APK: `.local/known-good-phone-before-hardening.apk` (previous user-tested build).

## Tiny versus Base: small synthetic-input pilot

Run on the real Google Pixel 8 Pro, CPU / 2 threads, with local Piper-generated input. Four distinct sentences (two English, two French), with quieter variants of two sentences, produce six paired inputs and twelve decodes. Input is generated once and reused byte-for-byte for both recognizers. No microphone or speaker was used. The app has no Internet permission.

| Language | Model | Word errors / reference words | Observed median decode |
|---|---|---:|---:|
| English | Tiny | 0 / 30 | 0.53 s |
| English | Base | 0 / 30 | 0.90 s |
| French | Tiny | 10 / 24 | 0.61 s |
| French | Base | 6 / 24 | 1.51 s |

Base reduced aggregate French errors in this small sample, but did not fix French recognition. Both models still mistranscribed the sentence about the mesh node. On the other French sentence Base introduced `ce site` for `ceci`, while Tiny repeated an ending. There is no evidence here that Base resolves the user's natural-English microphone difficulty.

These are synthetic convenience samples, not a natural-speech accuracy benchmark or a latency qualification. Quiet is amplitude attenuation, not background noise. Timing is a single bounded run with mixed first/subsequent decodes; do not infer a broad speed law. Recommendation: retain Tiny as the default, offer Base for deliberate comparison, and obtain natural speech evidence before changing the default.

CSV reports:
- `docs/speech-comparison-summary.csv`
- `docs/speech-comparison-samples.csv`

Detailed result: `.local/android-speech-comparison/pixel-results.json` (transcripts, input hashes, load/decode times, RTF, process PSS).

## Physical evidence and open boundaries

The earlier user-operated baseline demonstrated phone-local STT and TTS, phone→BLE→GrosseKatze→LoRa→Nova text reception, the reverse reply in the phone inbox, and user-triggered local playback. That baseline predates the new ACK/reconnect implementation.

**The updated physical ACK/reconnect path has not been retested.** The phone was locked during the hardening pass; no attempt was made to unlock it, activate its microphone/speaker, or send new radio traffic. An unlocked, explicitly confirmed send/reconnect check is still required. The first historical phone-send failure remains unknown; this implementation makes subsequent failures observable rather than proving that historical cause.

The independent UI reviewer failed before reviewing code (provider HTTP 400, unsupported `reasoning_effort`). Parent review and executable tests were completed; no independent-review pass is claimed.

Delivery rows/inbox are session-local, not durable message history. Broadcast has no destination acknowledgement. This remains a debug/test build, not a reliability-qualified release. Natural microphone comparison, native-speaker French listening review and broader RF reliability testing remain unperformed. Model redistribution notices are included, but public-release legal clearance and the project's AGPL decision remain separate.

## Receipts and cleanup

- `.local/android-hardening-final-receipt.json`
- `.local/continuation-final-build.log`
- `.local/continuation-final-emulator.txt`
- `.local/android-speech-comparison/pixel-instrumentation.txt`
- `.local/android-speech-comparison/base-pack-sources.json`

The Phonemesh emulator was shut down. The phone's instrumentation-only APK and benchmark scratch model ZIP/WAV inputs were removed; the production app, installed user packs, Download ZIPs and benchmark result JSON were retained. No commits or radio configuration/key changes were made.

## Phase 3 sub-project 1 size measurements

| Step | arm64 release APK | dex total | note |
| --- | --- | --- | --- |
| baseline (before Task 2) | 34573669 bytes | 7416288 bytes | `classes.dex` + `classes2.dex`; 40 `.proto` files packaged (491937 bytes) |
| protobuf-javalite + no .proto | 33557866 bytes | 4967760 bytes | single `classes.dex`; 0 `.proto` files packaged |
| R8 release | 32247146 bytes | 738108 bytes | single `classes.dex`; 0 `.proto` files packaged; minified debug instrumentation 7/7 pass |
| ARM-only + compressed native libs | 12240298 bytes (armeabi-v7a: 11283062 bytes) | 738108 bytes | `useLegacyPackaging = true`; ABI splits `arm64-v8a` and `armeabi-v7a` only, no universal APK; budget gate `:app:checkApkSize` (12700000 / 11700000 bytes) |
| installed size (arm64 debug APK, emulator) | n/a | n/a | legacy packaging true: 14909084-byte APK, 45792 KB under `/data/app` (14564 KB `base.apk` + 31220 KB extracted `lib`); legacy packaging false: 33954980-byte APK, 33164 KB under `/data/app`. Compressed native libs cost about 12.6 MB of extra installed storage and save about 20 MB of download. |

armeabi-v7a decode time: unmeasured (no 32-bit device); v7a APK not distributed until measured.

The `minifiedDebug` build keeps only the specific `kotlin.*` stdlib classes the androidx.test runner and the test sources reference (`android/app/proguard-minified-debug.pro`); new test code that uses other stdlib classes needs an entry there.

## Task 9 gates: espeak trimming and decoder requantization

Emulator `Phonemesh_API_35` (arm64), `PackEquivalenceTest`, 2/2 pass.

**Trimmed espeak data.** With VITS `noiseScale = 0` and `noiseScaleW = 0`, full-vs-full synthesis of each sentence is bit-identical (determinism guard), and trimmed-vs-full synthesis is bit-identical for all four sentences in fr and en-US. Trimmed means the core files plus `fr_dict`, `lang/roa/fr`, `en_dict`, `lang/gmw/en`; full means the untrimmed upstream `espeak-ng-data`. Voice pack sizes, trimmed vs full: fr-siwis 6334594 vs 14876735 bytes, en-US-ljspeech 13979221 vs 22521362 bytes.

**Requantized Whisper Tiny decoder: not adopted, owner decision needed.** Sizes: engine ZIP 60136101 bytes (original decoder) vs 31153380 bytes (requantized). 2 of 8 clips transcribe differently (both fr, same sentence), and the total decode-time ratio candidate/reference is 1.281 (adoption requires all identical and a ratio of at most 1.05). Differences are in `.local/receipts/decoder-diff.md`. RAM was not measured.

| tag | variant | identical | reference s | candidate s |
| --- | --- | --- | --- | --- |
| en-US | normal | yes | 0.154 | 0.219 |
| en-US | quiet | yes | 0.144 | 0.211 |
| en-US | normal | yes | 0.151 | 0.197 |
| en-US | quiet | yes | 0.151 | 0.221 |
| fr | normal | yes | 0.119 | 0.162 |
| fr | quiet | yes | 0.116 | 0.160 |
| fr | normal | NO | 0.157 | 0.236 |
| fr | quiet | NO | 0.304 | 0.253 |

Totals: reference 1.296 s, candidate 1.659 s, ratio 1.281.

## Task 11 gates: in-app pack downloads

Emulator `Phonemesh_API_35` (arm64), minified debug build, host server `android/scripts/test_pack_server.py` serving `android/model-packs/`, reached from the emulator at `10.0.2.2`. The debug base URL and the server default use port 8777, because another local service listens on `127.0.0.1:8765` on the development host (the emulator's `10.0.2.2` maps to host loopback, so it would answer instead). Run: `uv run --group packs python android/scripts/test_pack_server.py --dir android/model-packs --log .local/receipts/server.jsonl`, then `python3 android/scripts/run_instrumentation.py --serial emulator-5584 --classes org.phonemesh.app.PackDownloadTest --expected 5 --receipt .local/receipts/task11-download.txt`; the test still accepts instrumentation argument `port` to override.

**Normal run.** `PackDownloadTest`, `OK (5 tests)` (the drop and low-space tests skip by assumption). `voice-fr-siwis` (6334594 bytes) downloads through `DownloadManager`, matches the catalog size, installs through `PackStore`, and the ZIP is deleted; when two instances collect a finished download at the same time on two threads, exactly one result comes back (a success) and the ZIP is gone. A download the server answers with 404 is reported as "Problème de connexion. Réessayez." and leaves no ZIP and nothing pending. Server log: two full `GET`s, no `Range` (one earlier run also showed a `HEAD`).

**Dropped connection: resume observed.** Server started with `--drop-after 1000000`. `PackDownloadTest#resumesAfterTheConnectionDrops`, `OK (1 test)`. The server log shows the first `GET` (no `Range`) cut after 1000000 bytes, then a `GET` with `Range: bytes=1000000-` answered `206` (64, 66, 71 and 75 s later in four runs); the pack then verified and installed.

**Low space.** Small AVD `Phonemesh_API_35_small` (`system-images;android-35;default;arm64-v8a`). The emulator enforces a 6 GB minimum data partition for this image (`disk.dataPartition.size=2G` and `-partition-size 2048` were both raised to 6 GB), so the partition was filled with `fallocate` in `/data/local/tmp` to about 40950 KB available. `PackDownloadTest#lowSpaceIsRefusedBeforeDownloading` (`-e lowspace 1`), `OK (1 test)`: `start()` returned `NotEnoughSpace` and nothing was enqueued. `downloadsVerifiesAndInstalls` on the same device failed only at its first assertion with `NotEnoughSpace(neededBytes=69003782, freeBytes=41934848)`.
