# Phonemesh v1 design

> **Superseded in part (2026-10-14):** private chats and the shared chat follow `docs/specs/2026-10-14-accounts-design.md` (app-level encryption, private ports 301–303). The text-port contract below still holds for messages from radios without the accounts version.

## Scope and supersession

The current owner decision supersedes the Balanka-first handover: v1 reuses Whisper and
Kokoro for every matching language, with a simulated mesh and a Meshtastic integration
prepared before physical nodes are connected. Phonetic encoding, Anii/Balanka training,
recording permissions, transcription partners, tone research and voice conversion move
to v2. No translation, live calls, cloud inference or new cryptography in v1.

## User-visible path

Local audio file → multilingual Whisper transcription (not translation) → editable text
preview → explicit confirmation → one UTF-8 Meshtastic TEXT_MESSAGE_APP → receiver text
preview → explicit local Kokoro synthesis to WAV. The skeleton neither records the
microphone nor plays audio automatically. Text-only operation needs no speech weights.

The first artifact is a Python CLI/library, not an Android app. After laptop proof,
Android gets a matching text contract, local mobile speech runtimes and integration
through the Meshtastic app's service, subject to verification of the current Android API.

## Languages and capability claims

Use an explicit registry of the Whisper/Kokoro intersection: en-US, en-GB, es, fr, hi,
it, ja, pt-BR, zh. That is eight base languages, nine voice profiles. Language names
refer to upstream advertised capability, not local native-speaker quality validation.
No silent switch to English for unsupported languages. English-only Whisper weights
cannot serve non-English requests. ASR language is explicit per message; no guess is
needed from ambiguous short received texts. Code-switching is not qualified in v1.

Kokoro 0.9.4 maps these profiles to a/b/e/f/h/i/j/p/z respectively; each has a matching
preset voice. All advertised profiles remain visible even when assets are not installed,
but missing assets must produce an actionable error, not a network download.

## Wire contract

Application payload is readable UTF-8: `[pm1:fr] Bonjour.`. The tag holds the canonical
language/voice profile only; the body is the confirmed original-language transcript.
It is sent on TEXT_MESSAGE_APP, not PRIVATE_APP. Stock clients display tag and body.
A plain untagged stock message requires an explicit receiver language to synthesize.
Unknown/malformed version or language tags are rejected, not interpreted as instructions.
Wire tags must use the exact canonical spelling from the registry (`en-US`, not `en`).
Aliases remain accepted by local language selectors, but never normalized on receive:
canonicalization must not grow a tagged packet beyond its validated byte budget.

Conservative application limit: 200 UTF-8 bytes INCLUDING the tag. This is a product
budget below the SDK maximum, not a universal radio payload guarantee. Count bytes,
not characters. Reject empty/control-character text, invalid UTF-8 and oversized data.
The text policy blocks Cc/Cs controls, line/paragraph separators, soft hyphens, zero-width
spaces, BOMs, explicit bidi controls and Unicode tag characters. Format/combining-only
bodies are rejected. ZWJ/ZWNJ remain legal inside meaningful text (including Hindi).
This is a bounded plain-text preview policy, not general protection against homoglyphs.
Never truncate, compress audio, hide audio in text, or auto-fragment. Single-packet cap
also bounds receiver synthesis work. Longer-message sequencing is a later v1 increment.

## Transport and delivery

A common send/receive/close interface accepts only validated text payloads. The in-memory
mesh has seeded loss and latency, bounded queues, explicit receive timeouts and no real
radio access. It proves composition, not RF reliability.

The laptop Meshtastic adapter opens only an explicitly selected serial device. It uses
SDK sendText and the text receive pubsub topic, filters by the exact interface and
configured channel, validates payloads, suppresses duplicates and bounds its inbox.
Do not auto-discover/connect to devices, modify channels, keys, region or firmware.
Sending requires explicit confirmation; recipients and channel are explicit on the CLI.
Request ACK only for direct messages. The return receipt means submitted/queued, NEVER
peer-delivered, read or spoken. ACK tracking and reconnect UX are future increments.

## Privacy and failure behavior

Only text and its language tag cross the transport. Audio paths/bytes and voice
embeddings are not part of the wire schema. Text is still sensitive: security depends
on the user's Meshtastic configuration; default/public channel keys are not private.
No promises of authenticated senders or confidential channels from this app alone.
Do not log content in background handlers; CLI previews print only on explicit commands.
No silent retries that can send duplicate messages. Receiver does not auto-play content.

Runtime speech adapters use explicit existing local model/config/voice paths and offline
library flags. Provisioning downloads are separate from messaging and are not performed
implicitly. G2P assets must be provisioned too. Heavy imports are lazy; CPU-only adapters
avoid taking over GPU jobs. Generated audio is local and not automatically retained beyond
the output WAV requested by the user.

## Acceptance and proof boundary

- Every registry profile round-trips without text mutation through the simulator.
- UTF-8 byte cap, unsupported languages, malformed packets, missing assets, no-consent,
  loss/timeouts, foreign-interface traffic and duplicates have executable tests.
- Installed Meshtastic SDK/protobuf verifies sendText construction without a device.
- A built wheel and installed CLI run language listing and simulated send/receive.
- Speech adapter contracts can be tested without model weights; those tests are NOT
  real synthesis/transcription proof. Real-assets and native-speaker quality gates
  stay open until separately exercised.
- Two physical nodes must later prove configured channel/region, reception, direct ACK,
  loss behavior and stock-client compatibility. Benin/Togo regulatory verification is
  a deployment gate, not a reason to block software construction.

## Release boundaries

No commit, push, PR, staging, radio transmission, microphone capture or model training
is part of this skeleton build. Proposed AGPL-3.0 needs confirmation before public release.
The design belongs in eventual version control; `.local/plan.md` stays local.
