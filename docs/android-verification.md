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

armeabi-v7a: see the Fairphone 3 measurements below (32-bit process on a 64-bit phone); v7a APK not distributed until measured on a 32-bit phone.

The `minifiedDebug` build keeps only the specific `kotlin.*` stdlib classes the androidx.test runner and the test sources reference (`android/app/proguard-minified-debug.pro`); new test code that uses other stdlib classes needs an entry there.

### Final measurements on HEAD (Task 16, 2026-10-06)

Built from `74ad921` plus the measurement test `DeviceMeasurementTest`. "Before" is commit `e0ea58f`, built in a temporary worktree. The emulator `Phonemesh_API_35` is arm64 (Apple Silicon host), so the arm64-v8a APKs stand in for the plan's x86_64 ones on both sides. Receipts are `.local/receipts/task16-*`; the host-side sampler is `.local/receipts/task16-measure.py`.

| Item | Before (`e0ea58f`) | After (`74ad921`) |
| --- | --- | --- |
| arm64-v8a release APK (download) | 34573669 bytes | 12270750 bytes |
| armeabi-v7a release APK (download) | 24924009 bytes | 11313514 bytes |
| Installed app on the emulator (`du -sk` of the app's `/data/app` dir) | 34308 KB (debug, libs left in the APK) | 45944 KB (debug), 45284 KB (minifiedDebug; libs extracted, 31220 KB) |
| English speech download | 85505570 bytes (one combined pack) | 74115322 bytes (engine 60136101 + voice 13979221) |
| French speech download | 77652402 bytes (one combined pack) | 66470695 bytes (engine 60136101 + voice 6334594) |
| Second language download | 77652402 or 85505570 bytes (a full combined pack) | 6334594 (fr) or 13979221 (en-US) bytes (the voice only) |
| First install, arm64 APK + English | 120079239 bytes | 86386072 bytes |
| First install, arm64 APK + French | 112226071 bytes | 78741445 bytes |
| Unpacked English speech data | 140988453 bytes | 123841942 bytes (engine 103611006 + voice 20230936) |
| Emulator cold start, median of 3 (`am start -W` TotalTime) | 545 ms (545, 574, 499; debug) | 597 ms (597, 494, 619; debug); 546 ms (546, 525, 576; minifiedDebug) |
| Emulator peak RAM during `NativeSpeechTest`, median of 3 runs | 332509 KB (318052, 352857, 332509; debug) | 267382 KB (265527, 267382, 344981; minifiedDebug) |

Pack ZIP sizes: `engine-whisper-tiny-1` 60136101 bytes, `engine-whisper-base-1` 93630372, `voice-en-US-ljspeech-1` 13979221, `voice-fr-siwis-1` 6334594 (`task16-sizes.txt`, with SHA-256). These four are the catalog and are published at `https://github.com/Tranquil-Flow/phonemesh-packs/releases/download/packs-1/`. `voice-en-US-lessac-low-1` (13885501 bytes) was built for comparison only; it is not in the catalog and not published, because the licence of its training data forbids redistribution. Release APKs were re-measured by `scripts/check.sh` on HEAD (`task16-check.txt`, `APK_SIZE` lines). The release APK is unsigned and cannot be installed, so installed size is measured on the debug and minifiedDebug builds.

How to read the speed and memory rows: cold start on the emulator moved by less than its run-to-run spread (494 to 619 ms), so no change in either direction is claimed. Peak RAM is `dumpsys meminfo` `TOTAL` PSS sampled every 0.5 s while the test ran, so a short peak can fall between samples; one "after" run reached 344981 KB. The test is the same round trip (synthesize then transcribe English, then French) on both sides, but the packs differ (one combined pack per language before; engine plus voice after) and the build types differ: the baseline test APK targets `debug`, the current one targets `minifiedDebug` (`testBuildType`).

#### Fairphone 3 (real low-end phone)

Owner-authorised test phone: Fairphone 3 (FP3), Android 10 (SDK 29), Snapdragon 632, 3.6 GB RAM, ABIs arm64-v8a and armeabi-v7a. HEAD minifiedDebug build. No microphone, playback, radio or Bluetooth use; synthesis goes to samples only. Each run of `DeviceMeasurementTest` installs the engine and the en-US ljspeech voice, synthesizes "This is a radio test. Meet me at the library." once with zero VITS noise, and decodes that clip three times. Peak RAM is the highest `dumpsys meminfo` `TOTAL` PSS sampled every 0.5 s during the run. Every run is a fresh process; values are the median of three runs.

Decoders, arm64-v8a, same clip (63232 samples, PCM SHA-256 `99277baa…57622` in all six runs, the same hash as on the emulator), same voice, same build. Runs alternated current and candidate engines.

| Engine | ZIP bytes | Peak RAM (3 runs) | Decode s, median per run | Decode s, median | Model load s | Transcript |
| --- | --- | --- | --- | --- | --- | --- |
| current (original decoder) | 60136101 | 346583 KB (346009, 346583, 350826) | 1.614, 1.838, 1.647 | 1.647 | 10.43 | identical to the input text in all 9 decodes |
| requantized candidate | 31153380 | 454980 KB (454980, 454965, 455322) | 3.228, 2.562, 2.766 | 2.766 | 9.57 | identical to the input text in all 9 decodes |

On this phone the requantized decoder uses about 108000 KB (106 MiB) more memory and decodes about 1.7 times slower than the current one; it only saves download size. Decode times also vary within a run. Candidate, decodes 1 to 3 per run: 2.297, 3.294, 3.228 s; 2.562, 2.991, 2.141 s; 2.425, 2.928, 2.766 s. The first decodes (median 2.425 s) are faster than the run medians (median 2.766 s). Current, same layout: 1.823, 1.614, 1.584 s; 1.838, 1.643, 2.005 s; 2.544, 1.647, 1.614 s. The cause of this spread was not investigated. Receipts: `task16-fp3-arm64-decoders-summary.txt`, per run `task16-fp3-arm64-{current,candidate}-{1,2,3}.json` and `-ram-N.txt`.

arm64-v8a vs armeabi-v7a, current engine. The v7a APK was installed with `adb install --abi armeabi-v7a` (`primaryCpuAbi=armeabi-v7a`, and the test reports `Process.is64Bit() == false`). This is a 32-bit process on a 64-bit phone (Fairphone 3), not a 32-bit phone.

| Measurement | arm64-v8a | armeabi-v7a (32-bit process) |
| --- | --- | --- |
| Cold start | not measured: the phone was locked for the whole session | not measured: the phone was locked for the whole session |
| `DeviceMeasurementTest` peak RAM, median of 3 | 346583 KB | 253761 KB (253761, 252388, 265319) |
| Decode s, median | 1.647 | 2.141 (per run 2.174, 2.141, 2.130) |
| Synthesis s, median | 3.42 | 6.71 |
| Model load s, median | 10.43 | 11.74 |
| `NativeSpeechTest` | OK (1 test), peak 326727 KB | OK (1 test), peak 246598 KB |

The v7a clip has the same length and transcript but a different PCM hash (`19e474fb…69b3cb`); 32-bit floating-point code paths do not give bit-identical synthesis. In the v7a `NativeSpeechTest` run (default VITS noise, so audio differs run to run) the French transcript repeated a fragment ("Bonjour, ceci est un message radio. Bonjour, ce"); the test passes because it checks for "bonjour". Receipts: `task16-fp3-v7a-summary.txt`, `task16-fp3-v7a-*`, `task16-fp3-arm64-native*`.

armeabi-v7a: measured as a 32-bit process on a 64-bit phone (Fairphone 3); the APK installs, speaks and transcribes there. It is still not distributed until measured on a 32-bit phone, because a phone with a 32-bit CPU (older cores, less RAM) is the case the 64-bit Fairphone cannot show.

#### Decisions and open items

- Whisper Tiny decoder: **owner decision: keep the original decoder**. The measurements favour it (lower peak RAM, faster decoding, identical transcripts on the Fairphone clip); the candidate only saves download size. Emulator (Task 9): 2 of 8 clips transcribe differently, decode time ratio 1.281, ZIP 60136101 → 31153380 bytes. Fairphone 3: identical transcript on the one English clip, decode 1.647 s → 2.766 s (ratio 1.68), peak RAM 346583 → 454980 KB.
- espeak-ng trimming: adopted. Trimmed and full data give bit-identical samples for all four sentences in fr and en-US, each variant in its own process ("Trimmed vs full espeak data, one process per variant" below; re-run with `android/scripts/compare_espeak_variants.py`). Every voice carries the union of the catalog languages' espeak files, and the app keeps one shared copy so removing a voice cannot break another language (Task 13).
- English voice A/B: no candidate left to compare. The lessac-low candidate cannot be offered: the licence of its training data allows research use only and forbids redistribution, so it is not in the catalog and not published. Its ZIP is 13885501 bytes vs 13979221 bytes for ljspeech (93720 bytes, 0.7% smaller).
- Resume after a dropped connection: observed (Task 11 section: `Range: bytes=1000000-` answered 206, pack verified and installed).
- Low space: refused before downloading (Task 11 section: `NotEnoughSpace`, nothing enqueued).
- Network boundary: the release dex references networking APIs only from `PackDownloads` (and its nested classes). `android/network-boundary-allowlist.txt` lists no library exceptions; `:app:checkNetworkBoundary` prints `NETWORK_BOUNDARY_OK` on HEAD (`task16-check.txt`), requires `PackDownloads` to be present, and was shown to fail on a planted `MainActivity` → `URL.openStream` call (`task12-bites.txt`).
- APK ceilings in `android/gradle.properties` stay at 12700000 (arm64-v8a) and 11700000 (armeabi-v7a) bytes: HEAD's release APKs (12270750 and 11313514 bytes) are larger than when the ceilings were set (12240298 and 11283062), so there was nothing to tighten. Headroom is now 429250 and 386486 bytes (3.4% and 3.3% of the ceilings).

#### Verification on HEAD

- `scripts/check.sh`: `CHECK_OK` (ruff, 162 Python tests, JVM tests, all APK builds, lint, size and network-boundary checks; `task16-check.txt`).
- Emulator, every class in `app/src/androidTest` in one run: `OK (30 tests)` (`task16-full-instrumentation.txt`). Packs pushed: catalog Tiny and Base engines, both catalog voices, plus the reference engine and full voices for `PackEquivalenceTest`; local pack server on port 8777 for the download tests. The two gated `PackDownloadTest` cases (connection drop, low space) skip by assumption in this run; they need a dropping server or a filled small AVD and were not repeated (results in the Task 11 section).
- The first full run had 4 failures (`NativeSpeechTest`, `PackEquivalenceTest#candidateDecoderTranscriptsAndTiming`, `PackManagerUiTest#englishStillSpeaksAfterTheFrenchVoiceIsRemoved`, `SpeechComparisonTest`), because the new `DeviceMeasurementTest` deleted its pack folder after it ran. The fix: it now clears that folder only before it runs and no longer deletes it afterwards. The re-run passed: `OK (30 tests)`.
- After the final review fixes: `scripts/check.sh` `CHECK_OK` (180 Python tests, JVM and release unit tests; release APKs 12277058 B arm64-v8a and 11319822 B armeabi-v7a; `NETWORK_BOUNDARY_OK` with the broader socket patterns and the `PackDownloads` positive control; `final-fix-check-c6.txt`). Emulator, every class in one run: `OK (36 tests)` (`final-fix-full-instrumentation.txt`): the in-process espeak test was removed (-1) and the new download (+2), sharing (+1) and pack-list (+3) tests added; four gated tests skip by assumption (connection drop, low space, candidate decoder, single espeak variant). `PackDownloadTest` alone `OK (7 tests)`, `SharingTest` alone `OK (6 tests)`.
- Import size cap: the largest catalog ZIP plus an eighth, 105334168 bytes (Whisper Base 93630372 + 11703796). Packs rebuilt the way sharing rebuilds them (ONNX entries at BEST_SPEED, other files at BEST_COMPRESSION), measured with the same `java.util.zip` code on the host: Tiny 60136101 → 65057882 B (+8.18 %), Base 93630372 → 101312922 B (+8.21 %), fr-siwis 6334594 → 6441011 B (+1.68 %), en-US-ljspeech 13979221 → 14340556 B (+2.58 %); `PackSharing.rebuild` on the emulator gave the same 6441011 B for fr-siwis. All BEST_COMPRESSION rebuilds were 112 to 224 B over the catalog size; storing ONNX entries uncompressed would add about 70 % and was not used.
- Limit of the combined run: within one process, espeak-ng keeps the data path of the first voice it initialised. In the 30-test run, `PackEquivalenceTest#trimmedEspeakSynthesizesIdenticalSamples` therefore most likely synthesizes with that first voice's espeak data, not with each trimmed or full voice's own copy, so it does not test the trimming there. The same applies inside that one test in the standalone Task 9 run (`.local/receipts/task9-equivalence.txt`): the full French voice synthesizes first. The clean evidence is the separate-process comparison below; that in-process test has since been removed.

**Trimmed vs full espeak data, one process per variant.** `PackEquivalenceTest#singleVariantSynthesis` (gated by `-e variant fr-full|fr-trimmed|en-full|en-trimmed`; it skips in the normal suite) installs the Tiny engine and exactly one voice variant, passes that voice's own `tts/espeak-ng-data` to espeak-ng, synthesizes the two fixed sentences of its language with VITS `noiseScale = noiseScaleW = 0`, and writes the SHA-256 of the float samples, the sample count, the sample rate, a Whisper transcript, the data directory given to espeak-ng and the process id. Before each run the app was force-stopped and the shared copy `files/espeak-ng-data` and every other pack dir were deleted (`sharedCopyPresent` is false in all receipts). Emulator `Phonemesh_API_35`, five `am instrument` runs, five different process ids:

| Run | Variant | espeak-ng data given | Sentence 1 samples / SHA-256 | Sentence 2 samples / SHA-256 |
| --- | --- | --- | --- | --- |
| 1 | fr full | 355 files, 17991651 bytes | 33583 / `82523fc1…7bdd` | 44800 / `0562a017…b5a6` |
| 2 | fr trimmed | 8 files, 878224 bytes | 33583 / `82523fc1…7bdd` | 44800 / `0562a017…b5a6` |
| 3 | en-US full | 355 files, 17991651 bytes | 63232 / `99277baa…7622` | 64256 / `4680ddd6…2cf4` |
| 4 | en-US trimmed | 8 files, 878224 bytes | 63232 / `99277baa…7622` | 64256 / `4680ddd6…2cf4` |
| 5 | fr full (repeat) | 355 files, 17991651 bytes | 33583 / `82523fc1…7bdd` | 44800 / `0562a017…b5a6` |

Result: trimmed equals full, byte-identical, for French and for English; the repeat of fr full in a new process gives the same bytes. `android/scripts/compare_espeak_variants.py --serial emulator-5584 --receipts DIR` repeats the four runs (one `am instrument` process each, through `run_instrumentation.py`) and compares the hashes; on the final-fix build it printed `ESPEAK_VARIANTS_IDENTICAL` with the same sample counts and hashes as the table, pids 2214, 2260, 2304, 2346 (`.local/receipts/final-fix-espeak-compare.txt`). Sample rates 16000 (fr) and 22050 (en-US). Receipts: `.local/receipts/task16-espeak-{1..5}-*.json` and `.txt`, host comparison `task16-espeak-compare.txt`. With the new gated test the normal suite has 31 tests: `OK (31 tests)` with the three gated tests skipped (`task16-fix2-full-instrumentation.txt`).

## Task 9 gates: espeak trimming and decoder requantization

Emulator `Phonemesh_API_35` (arm64), `PackEquivalenceTest`, 2/2 pass.

**Trimmed espeak data (superseded).** This in-process result is not valid evidence: espeak-ng keeps the data of the first voice it initialises in a process, and the full French voice synthesized first, so the trimmed data was never used. The test was removed; the evidence is the one-process-per-variant comparison in the Verification on HEAD section. Original text: with VITS `noiseScale = 0` and `noiseScaleW = 0`, full-vs-full synthesis of each sentence is bit-identical (determinism guard), and trimmed-vs-full synthesis is bit-identical for all four sentences in fr and en-US. Trimmed means the core files plus `fr_dict`, `lang/roa/fr`, `en_dict`, `lang/gmw/en`; full means the untrimmed upstream `espeak-ng-data`. Voice pack sizes, trimmed vs full: fr-siwis 6334594 vs 14876735 bytes, en-US-ljspeech 13979221 vs 22521362 bytes.

**Requantized Whisper Tiny decoder: not adopted (owner decision: keep the original decoder).** Sizes: engine ZIP 60136101 bytes (original decoder) vs 31153380 bytes (requantized). 2 of 8 clips transcribe differently (both fr, same sentence), and the total decode-time ratio candidate/reference is 1.281 (adoption requires all identical and a ratio of at most 1.05). Differences are in `.local/receipts/decoder-diff.md`. RAM was not measured.

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

Emulator `Phonemesh_API_35` (arm64), minified debug build, host server `android/scripts/test_pack_server.py` serving `android/model-packs/`, reached from the emulator at `10.0.2.2`. The debug base URL (the default of debug builds; release builds and builds given `-Pphonemesh.packBaseUrl` use an HTTPS address, see `docs/android-design.md`) and the server default use port 8777, because another local service listens on `127.0.0.1:8765` on the development host (the emulator's `10.0.2.2` maps to host loopback, so it would answer instead). Run: `uv run --group packs python android/scripts/test_pack_server.py --dir android/model-packs --log .local/receipts/server.jsonl`, then `python3 android/scripts/run_instrumentation.py --serial emulator-5584 --classes org.phonemesh.app.PackDownloadTest --expected 5 --receipt .local/receipts/task11-download.txt`; the test still accepts instrumentation argument `port` to override.

**Normal run.** `PackDownloadTest`, `OK (5 tests)` (the drop and low-space tests skip by assumption). `voice-fr-siwis` (6334594 bytes) downloads through `DownloadManager`, matches the catalog size, installs through `PackStore`, and the ZIP is deleted; when two instances collect a finished download at the same time on two threads, exactly one result comes back (a success) and the ZIP is gone. A download the server answers with 404 is reported as "Problème de connexion. Réessayez." and leaves no ZIP and nothing pending. Server log: two full `GET`s, no `Range` (one earlier run also showed a `HEAD`).

**Dropped connection: resume observed.** Server started with `--drop-after 1000000`. `PackDownloadTest#resumesAfterTheConnectionDrops`, `OK (1 test)`. The server log shows the first `GET` (no `Range`) cut after 1000000 bytes, then a `GET` with `Range: bytes=1000000-` answered `206` (64, 66, 71 and 75 s later in four runs); the pack then verified and installed.

**Low space.** Small AVD `Phonemesh_API_35_small` (`system-images;android-35;default;arm64-v8a`). The emulator enforces a 6 GB minimum data partition for this image (`disk.dataPartition.size=2G` and `-partition-size 2048` were both raised to 6 GB), so the partition was filled with `fallocate` in `/data/local/tmp` to about 40950 KB available. `PackDownloadTest#lowSpaceIsRefusedBeforeDownloading` (`-e lowspace 1`), `OK (1 test)`: `start()` returned `NotEnoughSpace` and nothing was enqueued. `downloadsVerifiesAndInstalls` on the same device failed only at its first assertion with `NotEnoughSpace(neededBytes=69003782, freeBytes=41934848)`.

## Phase 3 sub-project 3a: UI core loop

Spec: `docs/specs/2026-10-06-ui-core-loop-design.md`. Emulator `Phonemesh_API_35` (arm64), minified debug build, fake radio and fake speech injected into `AppCore`; no microphone, playback or radio transmission in automated tests.

| Item | Before (Task 1 baseline) | After | Change |
| --- | --- | --- | --- |
| arm64-v8a release APK | 12277058 B | 12352398 B | +75340 B (budget about 150,000 B) |
| armeabi-v7a release APK | 11319822 B | 11395158 B | +75336 B |
| classes.dex (arm64 release) | 818484 B | 911348 B | +92864 B |
| resources.arsc | 40 B | 15068 B | +15028 B |
| name clips (res/raw) | none | name_fr.m4a 3831 B, name_en.m4a 3970 B | renamed by resource shrinking in the APK |

- Baseline dex and `resources.arsc` were measured from a clean release build of the baseline commit, whose APK sizes match the recorded baseline to the byte.
- `scripts/check.sh`: `CHECK_OK` on the final tree (`.local/receipts/ui-final-check.txt`).
- Emulator, every class in `app/src/androidTest`: `OK (134 tests)` (`.local/receipts/ui-final-full.txt`), with real packs pushed and the local pack server running. Skipped by assumption: connection drop, low space, candidate decoder, single espeak variant, name-clip export, screenshot tour (runs only in the screenshot build).
- Screen checks: every screen state passes the 48 dp, description, eight-word, technical-word and 18 sp checks (`ScreenChecksTest`). `A11yCheckTest` proves each check reports a deliberate violation. Normal builds set FLAG_SECURE on every screen; release always does (`ReleaseFlagsTest`).
- Screenshots: `docs/screenshots-phase3/` (nine states, captured from the test-only build without FLAG_SECURE).
- `speech-smoke` is outside the gate and did not compile before this work (`PackDownloads.kt`: unresolved `BuildConfig`); unchanged here.
- Owner walkthrough on the Pixel: pending.

## Sub-project 3a-2: visual guide

Spec: `docs/specs/2026-10-07-visual-guide-design.md`.

| Measure | Before | After | Change |
|---|---|---|---|
| arm64-v8a release APK | 12352398 B | 12542422 B | +190024 B (budget 250,000 B) |
| armeabi-v7a release APK | 11395158 B | 11585186 B | +190028 B |
| caption clips (res/raw) | none | 12 files, 84724 B | |
| how-to clips (res/raw) | none | 4 files, 28092 B | |

- `scripts/check.sh`: `CHECK_OK` on the final tree. Emulator `Phonemesh_API_35`, fake radio and fake speech: `OK` for all 247 selected tests in seven batches. Left out: the four real-speech classes and two voice-removal tests that load a real voice; the argument-gated tool tests skip by assumption.
- Screen checks: every state passes in light and in dark (`ScreenChecksTest`).
- Screenshots: `docs/screenshots-phase3/` (21 states in light and dark, and 27 `howto-*.png` stills, from the test-only build without FLAG_SECURE).

Fairphone 3, welcome screen, 25 s, one whole loop of 24 s and a little more (`dumpsys gfxinfo`): pending (the owner unlocks the phone first).

Fairphone 3, Help page (26 pictures, scrolled), 20 s: pending.

Owner check on the Pixel 8 Pro: pending.

### Voice step choices and welcome pacing

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-10, on the tree with the numbered voice choices, the friend guide and the paced welcome story.

- `scripts/check.sh`: `CHECK_OK`. Release APKs: arm64-v8a 12586645 B, armeabi-v7a 11629405 B, +234247 B over the size before the visual guide (budget 250,000 B).
- Instrumented tests: 331 selected tests, all passed.
- `StoryTimelineTest` (JVM): every numbered step and the opening caption stay 4,000 ms in a loop of 24,000 ms, the action's pictures are unchanged, and a rest starts and ends on the picture the action stopped at.
- Screenshots: `docs/screenshots-phase3/` now holds 105 files; the two new states, in light and dark, are the voice step after a download that could not reach the internet and the friend guide.
- During setup the chats screen shows only the steps: the chat list, its empty hint and the plus button are absent until setup ends (`ChatsActivityTest`, `ScreenChecksTest`).
- Not checked on a screen by a person: the paced story in motion. The tests turn animations off, so the screenshots show its still frame.
- The sign for "no internet" is a voice download that failed without reaching the server or is waiting for the network; the app holds no permission to ask the phone about its connection.

### Pairing wait, the listen picture, large tips

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-10.

- `scripts/check.sh`: `CHECK_OK`. Release APKs: arm64-v8a 12590393 B, armeabi-v7a 11633153 B, +237995 B over the size before the visual guide (budget 250,000 B).
- Instrumented tests: 343 selected tests, all passed. `docs/screenshots-phase3/` holds 107 files; the new state, in light and dark, is a chat with a tip and its large picture.
- Pairing: `PairingWaitTest` (JVM, 8 tests) covers one connection call for a radio that is not paired yet, the wait outliving the usual time limits, refused pairing and the 90 s limit; `AppCoreTest` and `RadioActivityTest` cover what the screens show, through the fake radio. Pairing with a real radio: pending — owner.
- The delivery tip shows only in a private chat (`TipsTest`, `ConversationActivityTest`).

### Stop by touch, returning tips, writing dots, radio and chat names

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-11.

- `scripts/check.sh`: `CHECK_OK`. Release APKs: arm64-v8a 12593485 B, armeabi-v7a 11636245 B, +241087 B over the size before the visual guide (budget 250,000 B).
- Instrumented tests: 361 selected tests, all passed. `docs/screenshots-phase3/` holds 109 files; the new state, in light and dark, is the mic while words are being written (`07-writing*.png`).
- Stop: a locked recording stops with a real touch, and a touch the mic declined stays declined until it ends (`ConversationActivityTest`).
- Tips: a tip counts as learned only after its action or after it is closed; an unused tip comes back up to 3 times, the least shown first, one tip at a time (`TipsTest`, `SettingsTest`).
- Writing: while speech becomes text the mic shows three moving dots and "Writing your words…" (`ConversationActivityTest`).
- Names: the radio screen shows the name the radio announces; the shared chat is titled "Everyone nearby", or the radio's main channel name when its owner set one (preset names such as LongFast count as none) (`MeshtasticProtocolTest`, `RadioActivityTest`, `ChatsActivityTest`, `ConversationActivityTest`, `AppCoreTest`).
- Not checked on a phone: the dots in motion (the tests turn animations off), the Stop fix with a finger, and both names read from a real radio. Pending — owner.
- Later, 2026-10-12: the "slide left" tip shows only while a finger holds the microphone; locking by hand ends it as one showing, with no other tip in that recording (`TipsTest`, `ConversationActivityTest`). 363 selected tests, all passed; `07-recording-tip*.png` now shows a held recording.
- Later, 2026-10-12: the "slide up to lock" tip follows the same rule. A finger counts as holding only after `RecordGesture.TAP_MS` (300 ms), so a quick tap with a real finger never flashes a tip; a rotation lifts the hold and its tip leaves without counting a showing (`TipsTest`, `ConversationActivityTest`). 364 selected tests, all passed; `07-recording*.png` (a tapped recording) now shows no tip.

### Show the guide again, deleting a message

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-12. The owner raised the growth allowance and both APK ceilings by 100 KB for this work (350,000 B; arm64 12,800,000 B; armeabi-v7a 11,800,000 B); it used about 1.7 KB.

- `scripts/check.sh`: `CHECK_OK`. Release APKs grew to +243163 B (arm64-v8a) and +243167 B (armeabi-v7a) over the size before the visual guide.
- Instrumented tests: 369 selected tests, all passed. No screenshot state changed: the new Settings button sits below the part `09-settings` shows; it was checked on the emulator with the capture-allowed debug build.
- Settings → "Show the guide again" / "Revoir le guide" brings back every tip and the hold-to-talk hint, then opens the welcome story. The radio, the voices and the finished setup stay (`AppCoreTest`, `SettingsActivityTest`).
- A long press on a message asks "Delete this message?" / "Supprimer ce message ?" with "Only on this phone. Others keep it."; Cancel keeps it. Deleting stops its playback, sends nothing, and a late delivery report finds nothing (`HistoryTest`, `AppCoreTest`, `ConversationActivityTest`). Each bubble is named for TalkBack (the sender in a group, then the words) and its long press is read as "Delete".
- Still to make: a how-to picture for the long press (deleting has no tip yet).

### Familiar chat features, wave 1 (no radio use)

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-12. Spec: `docs/specs/2026-10-12-chat-features-design.md`.

- `scripts/check.sh`: `CHECK_OK`. Release APKs: +251915 B over the size before the visual guide (allowance 350,000 B); this wave added about 8.8 KB.
- Instrumented tests: 379 selected tests, all passed. JVM: `DaysTest` (4).
- Long press on a message opens one menu: Copy, Share, Forward (only when another chat exists), Delete (asks once more). Forward fills the other chat's draft and opens it; nothing is sent (`ConversationActivityTest`, `AppCoreTest`).
- Day dividers "Aujourd'hui" / "Hier" / the long date; one per day; a short bubble stays narrow (`DaysTest`, `ConversationActivityTest`). Screenshots `05-conversation*` and `05-conversation-mic-hint*` now show the divider.
- Long press on a chat: pin a private chat (it follows the group) or delete a chat after asking; the group only empties. The history moves to version 2 and a version-1 database keeps its chats (`HistoryTest`, `ChatsActivityTest`, `AppCoreTest`).
- Settings → "Lire les nouveaux messages à voix haute", off by default; a new message is read only while a screen is open, the microphone is free and nothing plays (`AppCoreTest`, `SettingsActivityTest`). Screenshots `09-settings*` show the switch.
- A first gate run caught every bubble stretched to full width (the row became vertical for the divider); fixed and covered by a test before this record.
- Still to make: how-to pictures for the message and chat long presses and for forwarding.

### Familiar chat features, wave 2: replies and reactions

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-12. Spec: `docs/specs/2026-10-12-chat-features-design.md`.

- `scripts/check.sh`: `CHECK_OK`. Release APKs: +262175 B over the size before the visual guide (allowance 350,000 B); this wave added about 10.3 KB.
- Instrumented tests: 387 selected tests, all passed. JVM: `MeshtasticProtocolTest` (18), including Meshtastic's `reply_id` and `emoji` fields both ways, ids read as unsigned, and a reaction refused without the message it answers.
- Received messages keep their radio id; history version 3; a version-1 database still upgrades (`HistoryTest`).
- Reply: the bar above the draft, its cross, the send carrying the answered id, the quote in the bubble, and "Un message absent de ce téléphone" (`AppCoreTest`, `ConversationActivityTest`).
- Reactions: sent as the emoji alone with the reaction mark; one per person; never a bubble, unread or read aloud; dropped when over 32 bytes; nothing kept when the radio cannot take it (`AppCoreTest`, `HistoryTest`, `ConversationActivityTest`).
- A message that never went out offers neither Reply nor React.
- New screenshot state `05-conversation-reply` (light and dark): reactions under a message, a reply bubble with its quote, the reply bar above a draft. `docs/screenshots-phase3/` holds 111 files.
- Not checked with a real radio yet: replies and reactions between two phones, and how the official Meshtastic app shows ours.

### Familiar chat features, wave 3: planned messages

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-12. Spec: `docs/specs/2026-10-12-chat-features-design.md`.

- `scripts/check.sh`: `CHECK_OK`. Release APKs: +269623 B over the size before the visual guide (allowance 350,000 B); this wave added about 7.4 KB.
- Instrumented tests: 394 selected tests, all passed. JVM: `PlanTimesTest` (4): the three choices, "this evening" only before 18:00, month end and the clock change, just after midnight.
- Permissions added and pinned by `AppBoundaryTest`: `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (Android 12 and older), `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` (never asked for). The service is a `shortService`, so `FOREGROUND_SERVICE_CONNECTED_DEVICE` is not used.
- Planning: "Plus tard" under a draft that fits, never while the region card asks; the draft and its reply become a planned message (history version 4); planned messages wait above the draft with "Envoi à …" / "Envoi demain à …" and a cross; the alarm follows the next one due (`AppCoreTest`, `HistoryTest`, `ConversationActivityTest`).
- Sending: a due message waits while the radio is away and goes out when it becomes ready, as a reply when one was planned, marked "Prévu pour …" (`AppCoreTest`, `ConversationActivityTest`). The alarm's receiver started the service, which sent the due message and stopped (`AppCoreTest`); on the emulator the service reached the foreground.
- New screenshot state `06-draft-planned` (light and dark). `05-conversation-reply*` and `06-draft-cut*` now show "Plus tard" under the draft. `docs/screenshots-phase3/` holds 113 files.
- A first gate run showed "Plus tard" pushing the region card's listening tip off the screen; it is now hidden while the region card asks, and `13-region-blocked-send` checks that.
- Not checked on a phone: an alarm waking the closed app (Android's background-start exemption for exact alarms), the notification, and the Fairphone (Android 10) path without a service type.

## Radio region: Togo legal notes and results

Spec: `docs/specs/2026-10-07-radio-region-design.md`.

### Legal notes (Togo)

These come from ARCEP Décision N°226/ARCEP/DG/22. They are outside what Phonemesh controls, so they are recorded here, not built:

- Short-range devices are licence-exempt but must be type-approved (Art. 5). Whether Heltec radios are approved in Togo is not known.
- Power amplifiers are forbidden (Art. 6).
- 500 mW is ERP, so it includes antenna gain. Meshtastic's 27 dBm `EU_868` limit is conducted power. A high-gain antenna at full power can exceed the limit. Phonemesh leaves `tx_power` alone. A later spec may cap it per country.
- There is no 915 MHz band in Togo.

The decision was read through OCR of a scanned PDF. The rows used here are clear and match CEPT ERC Rec 70-03.

### Country sources

Copied from `android/core/src/main/resources/countries.txt`, the only place a country is added:

```
DE | EU_868 | h:000000,dd0000,ffce00 | Bundesnetzagentur Vfg. 91/2025 (November 2025), Tabelle 2, Band Nr. 54: 869.4-869.65 MHz, 500 mW ERP, frequency access and interference mitigation techniques [7] or duty cycle <= 10 %
TG | EU_868 | h:006a4e,ffce00,006a4e,ffce00,006a4e; canton:d21034; star:ffffff | ARCEP Togo Décision N°226/ARCEP/DG/22 (30 Dec 2022), Annex 2: 869.4-869.65 MHz, 500 mW ERP, 10 % duty cycle
```

The `[regions]` and `[flags]` lines in that file are pictures only and support no country.

### Size

Release APKs from `:app:checkApkSize`, measured 2026-10-10. "Before" is the tree before any radio-region code.

| Measure | Before | After | Change | Ceiling |
|---|---|---|---|---|
| arm64-v8a release APK | 12542422 B | 12581837 B | +39415 B | 12700000 B |
| armeabi-v7a release APK | 11585186 B | 11624597 B | +39411 B | 11700000 B |

The growth is above the 30 KB per ABI at which the cause is to be looked up. The arm64-v8a size along the way: 12559513 B with the radio's region read and write (+17091 B), 12565233 B with the write, reconnect and verify flow and the send block (+5720 B), 12580065 B with the cards, the picture, the flag drawing, the two lists and their English and French strings (+14832 B), 12580113 B with the screen checks' shortened wait (+48 B), 12581837 B with the way back from a failure card, the block on Send again, the log lines and the all-or-nothing write (+1724 B). No dependency, permission or resource file other than `countries.txt` and strings was added.

### Emulator checks

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-10. No real radio is involved in any of these.

- Region screen states (16, each in light and dark): Set up for Germany, Set up for Togo, the same question after setup, Change, no country known, country without rules, "My country is not here", working, ready, did not come back, did not take the setting, not changed, the blocked send on the chat screen, the country list, the helper list with nothing chosen, the helper list with a region chosen and the box ticked.
- `ScreenChecksTest` and `RegionScreensTest`: `OK (34 tests)`. For every region state in both themes `RegionScreensTest` runs the every-screen checks (48 dp targets, descriptions, 18 sp text, eight words, no technical words), the theme background and FLAG_SECURE, and checks that no word is cut or ends in dots, that no control is cut at the sides, and that the picture is whole, twice as wide as high and holding its still frame. The Germany and Togo pictures are checked for their flag colours.
- `ScreenshotTour`: `OK (1 test)`; `docs/screenshots-phase3/` now holds 101 files: 37 states in light and dark (21 earlier states, 16 region states) and 27 `howto-*.png` stills, from the test-only build without FLAG_SECURE. The tests turn animations off, so every region picture in the screenshots is its still frame.
- `RegionCardsTest`, `RegionActivityTest`, `RegionFlowTest` and `FlagPainterTest`: 57 tests, all passed.
- `RegionSetupTest` (JVM): 38 tests, 0 failures.
- "Did not come back" is reached in the screen states by shortening the 60 s wait through a test hook; the 60 s timer itself is covered by `RegionSetupTest` with a hand-run clock.

### Results

| Check | Result |
|---|---|
| Emulator checks | passed, see above (the complete suite and `scripts/check.sh` on the final tree: pending — owner) |
| Size before/after | arm64-v8a 12542422 B → 12581837 B; armeabi-v7a 11585186 B → 11624597 B; both under their ceilings |
| Pixel 8 Pro, new radio (region write on a Heltec, real restart timing) | pending — owner |
| Fairphone 3, new radio (region write on a Heltec, real restart timing) | pending — owner |
| Keep (faked US) | pending — owner |
| Helper (faked BJ) | pending — owner |
| Pairing code | pending — owner |
| Local `get_config` reply and passkey behaviour on real firmware | pending — owner |
| Country guess from a real SIM or network | pending — owner |
| Visual check of the cards and lists on the Pixel 8 Pro and the Fairphone 3 | pending — owner |

### Chat-gesture pictures, the "hold" tip, the time window and forward pictures

Emulator `Phonemesh_API_35`, fake radio and fake speech, 2026-10-12. Owner approved round 10 (`.local/visual-guide-mockups/phonemesh-howto-chat-r10.html`). Spec: `docs/specs/2026-10-12-chat-features-design.md`.

- Gate `chat4-gate3`: `GATE_OK`. 400 instrumented tests selected, all passed. After it, the time window's sun and moon got their own icons (`ic_plan_sun`, `ic_plan_moon`): the first version had overwritten Settings' Light/Dark icons. Gate `chat4-gate5` with that fix built and passed the size check (+302,671 B), but its device tests were cut short: the Mac had restarted, and the emulator came back with broken graphics ("Failed to find ColorBuffer"), so animated pictures never let the screen go idle. After a clean emulator restart, the classes in the failed batches passed on their own: `ThemeTest` (now counting 36 icons), `ChatsActivityTest`, `HowToViewTest` 14/14 and `ScreenChecksTest` 40/40. JVM: 207 core tests (new `PlanWindowTest` 8, `HowToScenesTest` and `TipsTest` extended) and 105 app unit tests. Release APKs: +302,671 B over the size before the visual guide (allowance 350,000 B); this work added about 33 KB.
- "Envoyer plus tard" replaces the three preset times: Aujourd'hui / Demain, "Choisir un jour" (Android's calendar, today to a year ahead), the time between − and + (15 minutes), the time opening Android's clock, sun or moon, a past time red with Planifier off, the speaker reading the choice. Opens one hour ahead, rounded up to a quarter (`ConversationActivityTest`: the window, a past time, the calendar).
- Dates in the window, the planned row and Android's clock and calendar follow the app's language, not the phone's. A first gate showed "Mon, Oct 12" and "Thursday 15 October" in a French app; `Ui.date` and `Ui.inAppLanguage` fixed it, and a test checks it.
- Dialog buttons ("Annuler", "OK") are now 18 sp and named for TalkBack (`Actions.readable`), which `A11yCheck` can now check in dialogs too.
- "Transférer à…" shows each chat's picture beside its name.
- The "hold a message" tip shows once after the "A message came" tip was closed; a long press also counts as learned.
- Help has a new group, "Autres gestes", with eight pictures: hold, reply, react, forward, delete, pin, later, later_pick. They draw the app's own icons and words. Four approved sentences were shortened to meet the eight-word rule (see the spec).
- New screenshot states (light and dark): `05-forward-to`, `05-hold-tip`, `06-plan-window`, `06-plan-window-day`, `06-plan-clock`, `06-plan-calendar`, `10-help-more`; plus eight `howto-*.png` stills. No existing screenshot changed. `docs/screenshots-phase3/` holds 135 files.
- Not checked on a phone: the real alarm sending a planned message while the app is closed, and the Fairphone (Android 10) look of the clock and calendar.

### Picture messages folded in, photo and camera fixes, and "waiting for a radio"

Emulator `Phonemesh_API_35`, fake radio, 2026-10-13/14. Picture work from `~/Desktop/phonemesh-pictures.patch` (sha256 `dd1f4adb…`), applied to `b13c3aa`. Spec: `docs/specs/2026-10-12-picture-messages-design.md`.

- Gate `pic-gate4`: the build, unit tests and size checks passed; device batches 1–8 passed; the emulator was then killed (exit -9, "Failed to find ColorBuffer"). After a cold boot, batches 9–11 passed on their own: `ScreenChecksTest` + `ScreenshotTour` 44/44 (`fin-9`), Settings/Sharing/StoryView/Theme 50/50 (`fin-10`). The run before that (`pic-gate3`) passed all 417 tests before the last layout fix. JVM: core 238, radio-ble 105, app 106.
- Size: release arm64 12,693,389 B, armeabi-v7a 11,736,149 B; growth 341,015 B. The owner raised the Gradle limits to 12,800,000 / 11,800,000 B and the growth allowance to 450,000 B (2026-10-14).
- **"Picture cannot be opened" on every photo (owner report):** `PictureMaker.load` took Android's measuring step, which returns no picture by design, as a failure. Fixed; `PictureMakerTest` reads a real JPEG and rejects a non-photo.
- **Taking a photo (owner report):** the screen only had the photo picker. Now "Prendre une photo" (camera app, writing into `CaptureProvider`'s one cache file, no permission, nothing in the gallery, deleted when the screen closes) and "Choisir une photo". Each failure has its own sentence.
- **WebP colour profile:** Android's encoder added about 500 B to every picture (a flat 31×21 picture was 548 B), so the 500 B Medium budget could never be met. `WebpContainer.minimal` drops it (`WebpContainerTest`); a scene photo at Medium is now 96×72 px, 492 B, about 8 s on air at LongFast.
- **Nothing that needs the radio is silent any more (owner decision):** with no radio ready, messages, pictures and reactions wait with a clock and "En attente d'une radio" (new status `QUEUED`), survive closing the app, and go by themselves in order once a radio is ready. FAILED now means the radio took a message and then reported it could not send it (tests updated to make failures that way).
- The six picture tests that had never run (`PictureCoreTest`) pass, plus one for a picture waiting for a radio.
- New screenshots (light and dark): `05-picture-choose`, `05-picture-ready`, `05-picture-bubbles`. On pictures, the status words go under the picture so they are never cut; the size and look buttons have no tick, so French words are not split.
- Not checked on phones yet: camera and gallery on the Pixel and Fairphone, real radio delivery times per size, the owner's reports.

## Picture viewer, upscaling and use in the chat

Tap a picture to open it large. It has seven views: exact pixels, smoothed, sharper (no AI), and four AI upscalers.
The AI views run on the phone's CPU through the ONNX Runtime that sherpa-onnx already ships, via a 7 KB JNI bridge
(`app/src/main/cpp/upscale_jni.c`). The models are not in the APK; they are listed in `docs/dependencies.md` and copied
onto test phones into `files/upscalers/` (the app's private storage; on Android 11 and later the app cannot read files
`adb push` puts in its shared folder). Large models work in tiles; a progress bar shows the tiles, or moves without end
while a model loads.

The view on screen can be saved to Photos (JPEG in Pictures/Phonemesh, dated now) and shared. Saving does not change the
chat. "Utiliser dans la discussion" makes that view the chat's picture on this phone only: the app keeps a JPEG at the
bubble's width (60% of the screen) and remakes the full size when the picture is opened. "Revenir à l'original" undoes
it. Deleting the message deletes the kept view. A long press on a picture gives Open, Save, Share, Forward (private chats
only; the same bytes go again, after Send), Reply and React (once the other radio has confirmed the picture, which both
phones then name by its first piece's radio id), and Delete.

Results (2026-10-14):
- ONNX export parity with PyTorch: max abs diff 3.0e-6. Mac CPU timing: small models 12-128 ms, large 0.3-3.1 s.
- On the emulator, `PictureLooksTest` (the bridge with a tiny model, whole and tiled paths, the views without a model),
  `PictureCoreTest` (radio ids for replies, forwarding) and the viewer test in `ConversationActivityTest` (menu, forward,
  views, progress bar, save, use in chat, back to the original, cleanup) all pass.
- Full suite: 429 tests. Batch 10 hit the harness timeout under host load after 33 passing tests; the remaining 11 passed
  as `Class#method` (receipt `use-rest`).
- Phone speeds of the AI views are not yet measured; the viewer shows the time each view took.

### Upscaling: cancelling a view (2026-10-14)

A Pixel test with screen recording showed every AI view stuck at 0 %. The log showed no error: the picture screen was
opened five times in 80 s, and each AI request waited behind the first large-model job, which kept running after the
screen closed. Fix: a newer choice, or leaving the screen, cancels the running view at its next piece; "Arrêt de
l'affichage précédent…" shows meanwhile; the time shown counts only the work; each view's time goes to the app log
(tag `PhonemeshPicture`). `PictureLooksTest` (5 with the new cancellation test) and the viewer test in
`ConversationActivityTest` pass (receipt `cancel-a`). No other test or screenshot state reaches the viewer.

### Upscaling speed (2026-10-14)

- Pieces for the large models follow the phone's memory: 7 GB or more → 256 px (a Maximum picture in one go), 3 GB or
  more → 96 px, otherwise 48 px. On the Mac CPU, a Maximum picture through Real-ESRGAN x4plus took 6.8 s in 48 px
  pieces, 4.0 s in 96 px pieces and 3.2 s whole. Peak memory: about 320 MB at 96 px, about 700 MB whole.
- AI views already made are kept in the app's cache (JPEG 95, at most 50 MB, oldest first) and open at once; deleting
  the picture deletes them.
- Engine trial: the first run of a model compares ONNX Runtime's CPU kernels with XNNPACK and remembers the faster.
  sherpa-onnx's runtime is built without XNNPACK ("not supported in this build"), so the CPU engine is kept; the
  trial costs one failed open per model, once.
- `PictureLooksTest` (8) and the viewer test pass (receipt `speed-a`).

### Upscaling on ncnn: graphics chip and processor (2026-10-14)

- ncnn 20260526 (Vulkan build) is in the app with the bridge `upscale_gpu.cpp`. The models' ncnn copies (pnnx 20260704,
  fp16 weights) sit beside the ONNX files on test phones. Release APK growth for ncnn: +3,668,435 B (arm64), about
  +2.8 MB (armeabi-v7a). The owner approved it; `BUDGET` became 4,120,000 and the ceilings 16,470,000 / 14,610,000.
- `PictureGpuModelsCheck` (by hand, with the real models copied onto the emulator; receipt `gpu-models7`):
  - ncnn on the processor in 32-bit matches ONNX Runtime (largest difference 0.003-0.015 on a noise pattern); the
    conversion is right.
  - 16-bit arithmetic on the processor differs by up to 0.28, too much.
  - The emulator's software Vulkan (llvmpipe) gives wrong pictures in both modes. That says nothing about phones, but
    it showed the trial's safety net working: the trial rejected both modes and kept ncnn on the processor.
- On the emulator, ncnn on the processor was 5.3× faster than ONNX Runtime for the small model (37 vs 197 ms per
  64 px trial piece) and 2.7× faster for the large one (1.3 vs 3.6 s).
- The first run of a model on a phone tries ONNX Runtime, ncnn on the processor, and ncnn on the graphics chip in 16-
  and 32-bit. It keeps the fastest whose picture matches ONNX Runtime's (mean difference ≤ 0.004, largest ≤ 0.05), and
  logs each result under `PhonemeshPicture`. The trial is not counted in the time shown.
- `PictureLooksTest`, `PictureGpuModelsCheck` (no models: nothing to compare) and the viewer test pass (receipt `gpu-b`).
- **First-use trial (2026-10-14):** the trial now runs on a 48 px piece. A way whose warm-up picture is already wrong
  is not timed. Without an ncnn copy there is no trial. While it runs, the viewer shows "Setting up for this phone
  (once): N %" with a bar, one step per engine. `PictureLooksTest` covers the steps (0, 25, 50, 75, 100), no second
  trial, and no trial without an ncnn copy, using a tiny ncnn test model in `androidTest/assets` (receipt `prep-a`;
  10 tests with the viewer test).
- **Crash on the second picture (Pixel 8 Pro, 2026-10-14):** SIGABRT in `libncnn.so` (`__kmp_debug_assert` →
  `__kmp_fatal` → `abort`, ncnn's OpenMP runtime), each time on a fresh `Thread-N`: the viewer started a new thread
  per request. Fix: every AI view (and `release`) now runs on one long-lived worker, `PictureLooks.worker`, and
  `KMP_AFFINITY=disabled` is set before ncnn loads. Test `ncnnViewsWorkFromSeveralThreadsAndFromTheWorker`.
- **Setup once per model family:** the quick and gentle models share one design, as do the two large ones. After the
  first full trial in a family, its sibling only checks that the kept engine gives its picture too (one run per
  engine), then uses it. Test `theSecondModelOfAFamilyOnlyChecksTheChosenEngine`; receipt `omp-b`, 12 tests.
- **A bar that moves over time (2026-10-14):** with ncnn a whole photo is one or two pieces, so a bar fed only by
  pieces jumped 0 → 100 %. Each model's speed on this phone (ms per source pixel) is now kept: first from its engine
  trial, then from every view. The bar rises over the expected time up to 95 %; finished pieces can move it ahead,
  never back. With no time known yet it rises like 90·(1−e^(−t/4 s)). Tests `theNextViewOfAModelKnowsAboutHowLongItTakes`,
  `theEngineTrialGivesAFirstGuessAtTheSpeed`; receipt `bar-b`, 14 tests.
- Updating the app keeps its engine choices, so a model set up under an earlier build skips the setup.
- **Fairphone 3 (Adreno 506, Vulkan 1.1, driver 2020-05-28), first-use trial (log `fp3-trial-log.txt`):** the
  graphics chip's pictures are right (mean difference 2.5e-4 in 32-bit) but it is slower than ncnn on the
  processor: AI quick 586 ms vs 95 ms, AI large 9.0 s vs 2.9 s per trial piece (ONNX Runtime 206 ms / 3.3 s). ncnn on
  the processor is kept for both families, which is correct. The driver has no 16-bit storage or arithmetic, so its
  "16-bit" run equalled the 32-bit one (596 vs 586 ms) and only lengthened the large setup (about 56 s). The trial now
  skips 16-bit on such chips (`NativeGpu.fp16()`).

### Accounts port broadcasts, probe T1, and reconnecting on Android 10 (2026-10-14)

- `radio-ble`: port 301 (`ACCOUNT_PORT`) broadcasts. `buildBroadcastDataToRadio` sends with no want_ack and only
  on 301. The transport passes 301 broadcasts from other radios to `Listener.onBroadcastData`, with
  "rssi/snr/hops". JVM tests in `PrivateDataProtocolTest`.
- Probe build `-Pphonemesh.t1probe=true` (debug only; `BuildConfig.T1_PROBE` is false otherwise):
  - `T1ProbeRun` is started by adb broadcast and logs to `files/t1-probe.log`.
  - Its payload is `core/T1Probe`, with every byte checked on arrival.
  - Results are in `.local/accounts-t1.md` and the spec.
- **Reconnecting (Fairphone 3, Android 10):** the app asked for the 512 MTU 1-2 ms after the link came up, and
  Android 10 dropped the request ("configureMTU() - No connection"). The app then gave up after 10 s. Connects that
  failed with status 133 were not retried, so one session needed four tries. Fix:
  - The first request now waits 500 ms after the link comes up.
  - The MTU request times out after 4 s.
  - A setup that fails before the radio is ready is tried twice more, 1 s and 3 s later (`SetupRetry`), but never
    after the radio was ready or while pairing.
  - JVM tests: `SetupRetryTest`, `SafeGattQueueTest`; 687 JVM tests pass.
  - Emulator: `AppBoundaryTest` and `AppCoreTest`, 44 OK (receipt `ble-retry`). Those are the only androidTest
    classes that reach `BleMeshTransport` (grep).

### Accounts crypto, step 2: primitives (2026-10-14)

- `core/.../crypto/`:
  - `P256`: compressed points, with every outside point checked (on the curve, x < p, no identity); ECDH; ECDSA
    with raw r‖s and strict DER at the JCA boundary.
  - `Hkdf`: RFC 5869, SHA-256.
  - `Aead`: AES-256-GCM, the nonce never sent, with a test hook that sees every key and nonce pair.
  - `Bytes`: constant-time equality and fixed-width integers.
  - Platform crypto only, no new library.
- Tests (JVM, 15; core 261 pass):
  - NIST CAVP P-256 ECDH (25), public-key validation (12) and SigVer with SHA-256 (15), from the official zips.
  - RFC 5869 A.1-A.3, parsed from the RFC text.
  - 160 cases cross-checked against pyca/cryptography 44.0.2: `.local/crypto-vectors/crosscheck.py`, seed
    20261014, output in `core/src/test/resources/crypto/crosscheck-primitives.txt`. ECDSA is checked by
    verification, since its nonces are random.
  - Bad points, out-of-range and non-strict signatures, and every tampered GCM byte are refused.
- Still to confirm on Android (step 9): the same JCA names (`AlgorithmParameters EC`, `ECDH`, `SHA256withECDSA`,
  `AES/GCM/NoPadding`) on Android 10 and 15, and Keystore keys with `KeyAgreement` on Android 12+.

### Accounts crypto, steps 3-6: cards, starts, ratchet, wire v2 (2026-10-14)

- `core/.../account/`:
  - `ContactCard`: v1, the prekey signed by the identity key; strict decoding.
  - `AccountId`: SHA-256(IK)[0..8], ordered unsigned.
  - `InviteLink`: base64url after `#`, no padding.
  - `SafetyPicture`: 4 of 16 icons.
- `core/.../session/`:
  - `OwnKeys` and `SoftKeys`.
  - `Start`: the QR start and the link start with its hello and hello codes.
  - `Ratchet` and `SessionState`, versioned bytes.
- `core/.../wire/`: `WireV2` header, `Body` kinds 1-7, `HubAck` (port 302), `SharedChatWire` (port 303). Packets
  are at most 228 bytes.
- Tests: core 290 pass. The new classes are `AccountTest` 7, `StartTest` 6, `RatchetTest` 8, `TranscriptTest` 1
  and `WireTest` 7. They cover:
  - both QR sides agree and either may write first; the L/H split; a hello opens only for its owner on its day
    (±1);
  - forward secrecy (a stored state cannot reopen a delivered packet, and no used key is kept); healing (a state
    copied mid-conversation cannot read after two turns); replay refused; state round-trips;
  - the delayed step, and a held keyless packet;
  - no key and nonce pair is ever used twice (1,200+ seals watched);
  - 10,000 seeded random-delivery runs (order, loss, duplicates): nothing is delivered twice or altered, and
    without loss everything arrives;
  - the size limits (text 199/164, picture part 198, receipt 30, shared chat 228);
  - 100,000 random inputs into every decoder: no exception.
- **Independent cross-implementation:** `.local/crypto-vectors/transcript.py` (pyca/cryptography 44.0.2)
  implements the same rules and writes `core/src/test/resources/crypto/transcript.txt`. It holds 15 packets: QR and
  link starts, steps, out-of-order delivery across a step, a delayed step and a replay. Kotlin reproduces every
  packet byte for byte.

## Accounts crypto review fixes (step 7)

- Review: `.local/accounts-crypto-review.md` (independent sub-agent, report only, PoCs in its scratch dir).
- Fixes listed in the accounts spec, "Changes after the crypto review".
- `./gradlew :core:test`: 304 tests, 0 failed (`.local/receipts/crypto-review-fixes3.log`). Includes
  `TranscriptTest` (the Python implementation `.local/crypto-vectors/transcript.py`, rewritten for the new rules: 31
  sends, receipts and the hello byte for byte), the 10,000-run random delivery test, the key-and-nonce reuse watch
  over QR and link sessions, and one attack test per fixed finding.
- `:radio-ble:test :app:testDebugUnitTest :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`: OK.

## Hub prototype, step 15 (JVM)

- `mesh-protocol` split out of `radio-ble`; `radio-ble` 224 and app 217 JVM tests pass, androidTest compiles. The debug
  APK's classes match the previous demo build's for `org.meshtastic.*` (906) and `org.phonemesh.ble.*` (113); the only
  change is `MeshtasticProtocolKt` becoming `BleUuidsKt` (`.local/receipts/hub-step15b.log`).
- `hub` 23 tests (`.local/receipts/hub-step15.log`): framing with any split and log text between frames, airtime
  against the Semtech formula (436, 682, 1911, 2116 ms for 1, 36, 202, 228 bytes on LongFast), mailbox by code,
  hellos and shared posts, duplicates, malformed packets, restart, a crash-cut record, expiry, long poll; turns between
  phones, the duty cycle over an hour, the queue cap, acknowledgement batching, the rebroadcast-mode warning; the
  serial radio against a fake radio; HTTPS with a pinned key (a wrong key is refused) and a real sealed message from
  one phone's session through the hub to the other's.
- Not yet checked: the radio on the Mac's USB port (needs the owner's radio).

## Accounts step 9: keys and storage on the phone

- `AccountStore` (app): the identity key in the Android Keystore (P-256, sign and agree) on Android 12+, otherwise a
  software key sealed by `Vault` (a Keystore AES-256-GCM key; every sealed value bound to its row's label). Signed
  prekeys are sealed software keys, rotated every 30 days, the previous one kept 30 more. Sessions are stored sealed,
  with their serial outside the sealed bytes; an older or equal serial is refused (review F6), and a packet is stored
  in the same transaction as the state that sealed it (F3).
- History v6: `account`, `prekeys`, `contacts`, `sessions`, `outbox`, `hellos_seen`; chat kind `PERSON`. Skipped
  message keys stay inside each sealed session state and recipient codes are derived from it, so the plan's
  `skipped_keys` and `codes` tables are not needed. Old radio chats become read-only with the step 12 screens.
- Emulator (API 35), `.local/receipts/step9-a.txt`: `AccountStoreTest` 8, `HistoryTest` 13, `AppBoundaryTest` 3, OK (24).
  Covers the Keystore and the forced Android 10/11 path each running a real QR start and messages both ways with the
  core, keys and agreements surviving a restart, prekey rotation (a hello to the old card opens for 30 days, not 60),
  sessions refusing an older state and a sealed state moved to another row, hello replays, no key in logcat, and a
  version-5 history keeping chats, messages and pictures. `AppCoreTest` 41 rerun: `.local/receipts/step9-b.txt`.
- `grep -ln History app/src/androidTest` lists only `AccountStoreTest` and `HistoryTest`, both run.
- Still to check on the owner phones: the Keystore path on the Pixel (Android 15, hardware) and the sealed path and
  JCA names on the Fairphone (Android 10).
