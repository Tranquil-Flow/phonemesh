# Legacy Python CLI

The laptop CLI is a separate Whisper/Kokoro text-protocol tool, not the Android
accounts/hub client. Its language set, recording limits and delivery semantics below
apply only to this CLI. For the Android app, see the [README](../README.md) and
[how Phonemesh works](how-it-works.md). Phonetic transmission for under-resourced languages is later
research, not a v0.1 feature.

## Run without radios or models

Python 3.11–3.13 and [uv](https://docs.astral.sh/uv/) are required.

```sh
uv sync --extra radio
uv run phonemesh languages
uv run phonemesh simulate --language fr --text 'Bonjour à tous.'
uv run phonemesh simulate --language ja --text '図書館で会いましょう。'
uv run phonemesh simulate --language fr --text 'Bonjour.' --loss 1 --timeout 0
```

The last command exits 3 and reports no received message. A submitted receipt never
means delivered. Simulation is in-memory, not a LoRa range/airtime model.

Supported profiles: **en-US, en-GB, es, fr, hi, it, ja, pt-BR, zh**. Eight base languages,
nine profiles. These are upstream capabilities, not a claim that every model/voice is
installed or every language has passed human quality review. German and Anii are not
supported by this Kokoro pairing; unsupported language selection fails explicitly.

## Local speech commands

Install optional speech dependencies separately (this may download substantial packages):

```sh
uv sync --extra radio --extra speech
```

Provision model files **before** offline use. Messaging commands never fetch weights:

- A multilingual CTranslate2/faster-whisper model directory containing `model.bin`,
  `config.json`, `tokenizer.json` and the export's vocabulary assets. A
  `preprocessor_config.json`, when provided, is used by the runtime; it is optional
  in standard faster-whisper exports. Not an MLX or original OpenAI checkpoint.
  English-only weights explicitly reject other languages.
- Kokoro-82M v1.0 directory containing `config.json`, `kokoro-v1_0.pth` and
  `voices/<voice>.pt` for each selected profile. `languages` lists default voice IDs.
- English needs the local spaCy `en_core_web_sm` package. Install it explicitly during
  provisioning, not while messaging. Japanese needs Misaki's Japanese dependencies
  and a local working fugashi/UniDic dictionary; Mandarin needs Misaki's Chinese extras.
  Misaki 0.9.4 includes an espeak-ng loader dependency for the other language frontends.

Examples (replace the model paths with your local provisioned directories):

```sh
uv run --extra speech phonemesh transcribe --audio input.wav --model models/whisper --language fr
uv run phonemesh encode --language fr --text 'Bonjour à tous.'
uv run --extra speech phonemesh synthesize --payload '[pm1:fr] Bonjour à tous.' \
  --model models/kokoro --output preview.wav
```

Transcription accepts mono/stereo 16-bit PCM WAV, 8–48 kHz, up to 60 seconds. It returns
an editable draft and **does not send it**. Synthesis writes a new local WAV and never
plays it automatically or overwrites an existing file. CPU-only backends, two compute
threads. English synthesis has had a real cached-model smoke; ASR and the other voices
remain real-asset validation gates. Unit-test doubles are not speech-quality evidence.

## Connect the CLI to Meshtastic

Use an explicitly selected serial device; there is no discovery/automatic connection.
No hardware validation is claimed for this CLI. SDK/protobuf tests replace the hardware
write boundary only. Install the `radio` extra for actual use.

```sh
# Preview first. Entire payload must fit 200 UTF-8 bytes, including the language tag.
uv run phonemesh encode --language fr --text 'Bonjour à tous.'

# These commands access hardware; run only after choosing the node/channel/recipient.
uv run --extra radio phonemesh send --device /dev/your-radio --channel 0 --to '!12345678' \
  --language fr --text 'Bonjour à tous.' --confirm
uv run --extra radio phonemesh receive --device /dev/your-radio --channel 0 --timeout 30
```

Use `--to '^all'` explicitly for broadcast. Direct sends request an ACK but this skeleton
reports only **submitted**; it does not yet track peer ACKs. Received messages do not
play automatically. Untagged stock-client text requires `--language fr` (or another
supported profile). Tagged messages carry their own profile.

Normal Meshtastic clients see readable text such as `[pm1:fr] Bonjour à tous.` on
TEXT_MESSAGE_APP. No private binary protocol, no fragmentation, and no silent truncation.
The 200-byte budget is conservative, not a physical airtime or delivery guarantee.

Channel confidentiality depends on Meshtastic configuration. Public/default channel
keys are not private. Verify the legal regional radio settings before transmitting.
This software does not configure keys, channels, region or firmware.

## CLI development and boundary

```sh
uv run --extra radio pytest -q
uv run ruff check src tests
uv run ruff format --check src tests
uv build
```

See the [legacy CLI design](design.md), [CLI verification](verification.md),
and [dependency notes](dependencies.md). CLI speech-quality and radio validation
are separate from Android qualification; the CLI does not implement Android accounts.
