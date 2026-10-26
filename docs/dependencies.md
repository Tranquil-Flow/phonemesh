# Dependency intake

## Android release components

The application is AGPL-3.0; dependency licences and notices remain applicable.
Checked-in texts are in [licenses/](licenses/) with source URLs and integrity hashes in
[provenance.json](licenses/provenance.json). These records are not an independent
rebuild of the distributed native binaries or a completed legal/source audit.

| Component | Pin / source | Licence and distribution boundary |
|---|---|---|
| sherpa-onnx | [v1.13.8](https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8) | Apache-2.0; Android AAR acquired by `android/scripts/fetch-runtime.py`, which verifies its pinned size and SHA-256. |
| eSpeak NG for Piper | [fork revision ed530aa113046142eb5115cf2fc9157854d0ffe1](https://github.com/csukuangfj/espeak-ng/tree/ed530aa113046142eb5115cf2fc9157854d0ffe1) | GPL-3.0-family component; retain `licenses/espeak-piper-COPYING` and corresponding source/build information. |
| piper-phonemize | [fork revision f3ff95afc03640bc1399e113e83361192a2fafb4](https://github.com/csukuangfj/piper-phonemize/tree/f3ff95afc03640bc1399e113e83361192a2fafb4) | MIT; retain `licenses/piper-phonemize-LICENSE.md`. Its bundled uni-algo includes the Unlicense/public-domain dedication and alternative MIT text in `licenses/uni-algo-LICENSE.md`. |
| ONNX Runtime Android | [1.28.2](https://github.com/microsoft/onnxruntime/tree/v1.28.2) | MIT; retain `licenses/onnx-LICENSE` and `licenses/onnx-ThirdPartyNotices.txt`. |
| Meshtastic definitions | Vendored in `android/mesh-protocol` | GPL-3.0 text retained; exact vendored revision remains a provenance gate. See [Meshtastic provenance](third-party-meshtastic.md). |
| protobuf-javalite / protoc | 3.25.5 | BSD-style protobuf licence; runtime packaged, compiler is a build tool. |

The native revision pins above come from sherpa-onnx v1.13.8's upstream
[eSpeak recipe](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/espeak-ng-for-piper.cmake),
[Piper recipe](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/piper-phonemize.cmake), and
[Android ONNX recipe](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/cmake/onnxruntime-android-aarch64.cmake).
They establish recipe provenance, **not independent verification of the embedded AAR's
source revision or a reproducible binary rebuild**. The separate upstream eSpeak 1.52.0
COPYING reference in the licence directory must not be mistaken for the fork's revision.
Complete the corresponding-source/notices review before public distribution; URLs and
licence copies alone do not close that gate.

## Legacy Python CLI pins

`pyproject.toml` pins Meshtastic **2.7.11**, faster-whisper **1.2.1**, Kokoro **0.9.4**,
and Misaki **0.9.4**. `uv.lock` contains exact resolutions and package integrity hashes.
Radio dependencies are installed in this project's environment; speech dependencies
are optional. No pre-existing project environment was modified.

## Sources and observations

| Boundary | Evidence | Consequence |
|---|---|---|
| Kokoro language mapping | Upstream README/pipeline and model VOICES.md | Nine profiles/eight base languages; matching local voice file required |
| Whisper language inventory | OpenAI tokenizer LANGUAGES; installed faster-whisper API | Only intersection with Kokoro advertised; explicit transcription language |
| SDK sendText | Installed 2.7.11 source, run through real sendData/_sendPacket | UTF-8 TEXT_MESSAGE_APP, channel and direct-only ACK request preserved |
| SDK receive | Installed protobuf decoder → pubsub text topic, exercised | Filter exact interface/channel and reject malformed payloads |
| SDK payload limit | Installed `mesh_pb2.Constants.DATA_PAYLOAD_LEN == 233` | Product cap is independently 200 bytes including tag |
| Kokoro truncation | 0.9.4 pipeline source | Direct G2P + length check + generate_from_tokens avoids silent phone truncation |
| Offline speech | Explicit local paths/flags, spaCy preflight | Missing assets fail rather than download; provisioning stays separate |

Sources:
- <https://github.com/hexgrad/kokoro> (Kokoro source/API; package pin above)
- <https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md> (voices/capability caveats)
- <https://github.com/openai/whisper/blob/main/whisper/tokenizer.py> (language inventory)
- <https://github.com/SYSTRAN/faster-whisper> (local-file-only transcription API)
- <https://pypi.org/project/meshtastic/2.7.11/> (installed radio SDK version)

## Documented versus observed

The source-backed language registry does not establish equal intelligibility or installed
asset coverage. Test doubles exercise speech adapter call shape and WAV handling only.
A historical local Kokoro smoke used Kokoro-82M snapshot
`f3ff3571791e39611d31c381e3a41a3af07b4987` and `af_heart.pt` with Kokoro/Misaki 0.9.4.
That observation does not qualify Android speech packs or all advertised CLI voices.

The SDK spike captures real ToRadio protobufs instead of writing to a serial device.
It is not proof of device handshake, RF delivery, peer ACK or stock-client display.
See `tests/test_radio.py` for the rerunnable CLI spike. Android uses its own direct
BLE/protobuf transport and sherpa-onnx inference; this CLI spike does not qualify them.

## Licences

Original Whisper/faster-whisper code is MIT; Kokoro weights/code are Apache-2.0.
Meshtastic's SDK and eSpeak bring GPL-family components. Voice/model/dictionary assets
can carry additional attribution or distribution requirements. Audit the exact
redistributed assets and their corresponding-source obligations before release.
No XTTS or non-commercial-only model dependency is included in v1.

## Code ported into the Android app

| Code | Where | Licence | Notes |
|---|---|---|---|
| ThumbHash (<https://github.com/evanw/thumbhash>) | `android/core/src/main/kotlin/org/phonemesh/core/ThumbHash.kt` | MIT | Line-for-line Kotlin port; the MIT notice is kept in the file. The reference Java is test-only (`android/core/src/test/java`) and checks bytes and pixels against the port. |
| ONNX Runtime C API headers 1.28.2 (Microsoft) | `android/app/src/main/cpp/include/` | MIT | Headers only, unchanged. The picture upscaling bridge (`upscale_jni.c`, about 7 KB per ABI) uses the `libonnxruntime.so` that sherpa-onnx already ships, found at run time; nothing new is linked. |
| ncnn 20260526, Android Vulkan shared build (Tencent, <https://github.com/Tencent/ncnn>) | `android/app/src/main/cpp/ncnn/<abi>/` (`libncnn.so` and headers, unchanged from the release zip; each file's CRC checked on download) | BSD-3-Clause | Runs the upscaling models on the graphics chip through Vulkan (`upscale_gpu.cpp`, bridge `libphonemesh_gpu.so`). `libncnn.so` is 10.0 MB (arm64) / 6.5 MB (armeabi-v7a), about 4.0 / 2.8 MB compressed in the APK. Needs only system libraries; Vulkan is loaded at run time. |

Apart from ncnn for upscaling, pictures use only what Android already has (BitmapFactory, ExifInterface, the platform
WebP encoder).

## Picture upscaling models (not in the app)

**Optional upscaling weights are not distributed with v0.1.** The app works without
them and shows the AI views as unavailable. The following research inventory is not
a download offer, redistribution clearance or a release qualification claim.

| Model | File | Size | Licence | Source |
|---|---|---|---|---|
| Real-ESRGAN general x4v3 | `realesr-x4v3-strong.onnx` | 4.9 MB | BSD-3-Clause | <https://github.com/xinntao/Real-ESRGAN> release v0.2.5.0 |
| Real-ESRGAN general x4v3, gentle (0.5 blend with wdn-x4v3, as Real-ESRGAN's own `-dn 0.5`) | `realesr-x4v3-gentle.onnx` | 4.9 MB | BSD-3-Clause | same release |
| Real-ESRGAN x4plus | `realesrgan-x4plus.onnx` | 67 MB | BSD-3-Clause | release v0.1.0 |
| BSRGAN | `bsrgan-x4.onnx` | 67 MB | MIT (KAIR) | <https://github.com/cszn/KAIR> release v1.0 |

Each model also has an ncnn copy for the graphics chip (`<model>.ncnn.param` and `.ncnn.bin`, beside the ONNX file),
converted from the ONNX files above by pnnx 20260704 (<https://github.com/pnnx/pnnx>, a build tool, not in the app) with
fp16 weights: 2.4 MB for each small model, 33 MB for each large one. On a model's first run the app keeps the graphics
chip only if its picture matches the processor's on a trial piece, and only if it is faster.

These are generative models: they can invent detail, and the app labels their views so. They were trained on DIV2K and
similar sets, whose "academic research only" terms may or may not reach model weights; record this before shipping
any of them. Non-commercial models (4x-UltraSharp, Remacri, CodeFormer) are left out: they conflict with AGPL-3.0.

## Hub prototype (`android/hub`, not in the app)

Besides the app's own modules (`core`, `mesh-protocol` with protobuf-javalite), one library:

| Library | Version | Licence | Use |
| --- | --- | --- | --- |
| jSerialComm (`com.fazecast:jSerialComm`, <https://github.com/Fazecast/jSerialComm>) | 2.11.4 (jar SHA-256 `b5f7ec40d622864b92dc679159cdbaa3030d37884f0af2deef45b56c16bda7a6`) | Apache-2.0, dual-licensed with LGPL-3.0; used under Apache-2.0 | Opens the radio's serial port on Linux and Windows. No dependencies; its jar carries native code for each system. |

On macOS the serial port is still driven through `stty` and the device file, as qualified with real radios. HTTPS
uses the JDK's `HttpsServer`, and the hub's TLS key is made once with the JDK's `keytool`.

## Android libraries from Maven

| Library | Where | Licence | Notes |
|---|---|---|---|
| ZXing `core` 3.5.4 (`com.google.zxing:core`, <https://github.com/zxing/zxing>) | `android/app` only | Apache-2.0 | Accounts step 11: draws the add-me QR code (`AddSomeoneActivity.qr`) and reads camera frames (`ScanActivity.decode`). Pure Java, no AndroidX; the camera is Android's own Camera2. The one new library the accounts plan allows; `core` stays dependency-free. |
