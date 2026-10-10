# Phonemesh

Local speech → text → Meshtastic → local speech. **Audio never crosses the radio.**

Laptop-first v1 skeleton with all upstream Whisper/Kokoro overlapping languages.
Phonetic transmission for under-resourced languages is v2. No translation or voice cloning.

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

## Connect Meshtastic later

Use an explicitly selected serial device; there is no discovery/automatic connection.
No hardware was used to validate this skeleton. SDK/protobuf tests replace the hardware
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

## Development and current boundary

```sh
uv run --extra radio pytest -q
uv run ruff check src tests
uv run ruff format --check src tests
uv build
```

See [design](docs/design.md), [dependency notes](docs/dependencies.md), and
[verification](docs/verification.md). Implementation plan: `.local/plan.md` (local-only).

Remaining: real ASR/all-language speech validation, two-node RF/stock-client tests,
ACK/reconnect UX, microphone/interactive UI, and Android integration. Under-resourced
phoneme research and its recording/transcription permissions belong to v2.

Licensed under the GNU Affero General Public License v3.0 (see `LICENSE`). Third-party components and model licences are listed in the app's notices and in each pack's `NOTICE.txt`.
The Android app downloads its speech packs over HTTPS from `https://github.com/Tranquil-Flow/phonemesh-packs/releases/download/packs-1/` and, in release builds, installs a ZIP only if it has the hash in the app's built-in catalog; the published packs and the checks are described in [the Android design](docs/android-design.md#model-packs-and-multilingual-behavior).
No commits, pushes or staging are performed by this scaffold workflow.
