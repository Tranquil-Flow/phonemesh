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
