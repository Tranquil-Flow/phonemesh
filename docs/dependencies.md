# Dependency intake

## Exact pins

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
The real Kokoro smoke reuses an existing, unmodified local Kokoro-82M snapshot
`f3ff3571791e39611d31c381e3a41a3af07b4987` and `af_heart.pt`, using the existing
language-tutor Python environment with Kokoro/Misaki 0.9.4. Only Phonemesh source runs;
no sibling source/config changes, no model downloads and no audio playback.

The SDK spike captures real ToRadio protobufs instead of writing to a serial device.
It is not proof of device handshake, RF delivery, peer ACK or stock-client display.
See `tests/test_radio.py` for the rerunnable spike. Android service integration and
mobile inference are deferred rather than presumed compatible.

## Licences

Original Whisper/faster-whisper code is MIT; Kokoro weights/code are Apache-2.0.
Meshtastic's SDK and eSpeak bring GPL-family components. Voice/model/dictionary assets
can carry additional attribution or distribution requirements. Confirm the proposed
AGPL-3.0 project licence and audit the exact redistributed assets before release.
No XTTS or non-commercial-only model dependency is included in v1.

## Code ported into the Android app

| Code | Where | Licence | Notes |
|---|---|---|---|
| ThumbHash (Evan Wallace, <https://github.com/evanw/thumbhash>) | `android/core/src/main/kotlin/org/phonemesh/core/ThumbHash.kt` | MIT | Line-for-line Kotlin port; the MIT notice is kept in the file. The reference Java is test-only (`android/core/src/test/java`) and checks bytes and pixels against the port. |
| ONNX Runtime C API headers 1.28.2 (Microsoft) | `android/app/src/main/cpp/include/` | MIT | Headers only, unchanged. The picture upscaling bridge (`upscale_jni.c`, about 7 KB per ABI) uses the `libonnxruntime.so` that sherpa-onnx already ships, found at run time; nothing new is linked. |

Pictures otherwise use only what Android already has (BitmapFactory, ExifInterface, the platform WebP encoder), so the app
gains no runtime library.

## Picture upscaling models (not in the app)

Test phones get these files copied into the app's own folder (`Android/data/org.phonemesh.app/files/upscalers/`); the
app works without them and shows the AI views as unavailable. Exported to ONNX by `.local/upscalers/export.py`
(the export matches PyTorch to within 3e-6).

| Model | File | Size | Licence | Source |
|---|---|---|---|---|
| Real-ESRGAN general x4v3 | `realesr-x4v3-strong.onnx` | 4.9 MB | BSD-3-Clause | <https://github.com/xinntao/Real-ESRGAN> release v0.2.5.0 |
| Real-ESRGAN general x4v3, gentle (0.5 blend with wdn-x4v3, as Real-ESRGAN's own `-dn 0.5`) | `realesr-x4v3-gentle.onnx` | 4.9 MB | BSD-3-Clause | same release |
| Real-ESRGAN x4plus | `realesrgan-x4plus.onnx` | 67 MB | BSD-3-Clause | release v0.1.0 |
| BSRGAN | `bsrgan-x4.onnx` | 67 MB | MIT (KAIR) | <https://github.com/cszn/KAIR> release v1.0 |

These are generative models: they can invent detail, and the app labels their views so. They were trained on DIV2K and
similar sets, whose "academic research only" terms may or may not reach model weights; record this before shipping
any of them. Non-commercial models (4x-UltraSharp, Remacri, CodeFormer) are left out: they conflict with AGPL-3.0.
