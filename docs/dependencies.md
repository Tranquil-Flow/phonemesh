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
