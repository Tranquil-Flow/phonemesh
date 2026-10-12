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
