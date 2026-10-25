# Historical Python skeleton verification

This records the earlier skeleton milestone, not the current product state. Subsequent
work added real Whisper/Kokoro inference, an Android app and successful two-way physical
text exchange with local playback. See `android-design.md` and `android-verification.md`
for Android evidence and remaining limits. The historical "not yet proven" list below
describes that earlier milestone only.

## Exercised

- `uv run --no-sync pytest -q`: **129 passed**. Tests cover all nine language profiles,
  UTF-8 budgets, invalid packets, explicit confirmation, simulation loss/latency,
  bounded inboxes, SDK/protobuf integration, speech adapter call contracts, output
  WAV validation, preserving oversized drafts for editing, and full-budget stock text.
- `uv run --no-sync ruff check src tests`: all checks passed.
- `uv run --no-sync ruff format --check src tests`: all source/test files formatted.
- `uv lock --check`: resolved lock accepted.
- `uv build`: source distribution and wheel produced.
- Wheel installed without dependencies into a separate `private-operator-record:wheel-venv`; its CLI
  round-tripped French and Mandarin text through the in-memory simulator.
- Source archive inspected: no `.local`, `.venv`, model files, recordings or WAVs included.
- Meshtastic 2.7.11's real `sendText → sendData → _sendPacket → ToRadio` path exercised,
  replacing only `_sendToRadio` with a capture. Real protobuf receive decoding and
  pubsub delivery exercised too. This is SDK proof, not hardware proof.
- Real cached Kokoro 0.9.4 English synthesis via the Phonemesh adapter produced a
  non-silent **61,200-frame, mono, 16-bit, 24 kHz WAV (2.55 seconds)**. It used
  CPU inference and pre-existing local model/voice assets; no downloads/playback.
  Initial measured RMS was approximately **−21.72 dBFS**. The peak-limited normalizer
  deliberately allows a high-crest-factor clip to remain below the nominal RMS target.
  No listening-quality score is implied. Local artifact: `private-operator-record:kokoro-en-smoke.wav`.

Test-first history: the initial core contract suite produced 44 intended
NotImplemented failures before implementation; the initial speech contract suite
produced six. The real SDK receive test initially failed due to the hardwareless
fixture lacking a populated node table; initializing that table fixed the fixture.
Later RED regressions protected long transcription drafts and full-budget stock-message
synthesis. The radio and CLI coverage also includes characterization tests, not a claim
that every individual function was written through an independent RED cycle.

## Review closure

A read-only review of the codec, service and transports found two reproducible issues:
wire-language alias normalization could enlarge a received packet beyond the budget,
and display-control/invisible-only text could bypass the text policy. Both were fixed
with test-first regressions. The new suite initially had **37 failing and 7 passing**
cases, then **44 passing** after remediation. The complete suite now has **129 passing**.

Tagged packets now require canonical language spelling. Local selectors still accept
aliases. Plain stock messages retain their full 200-byte allowance without a new header
being added for synthesis. Unsafe display controls are rejected without banning Indic
ZWJ/ZWNJ inside text. All previously passing speech/stock-message tests remain intact.

Optional review notes remain bounded follow-ups, not skeleton blockers: physical
self-echo behavior is a two-radio check; queue-full drops remain counted and deduped;
simulator loss is intentionally drawn per recipient. A malformed internal SDK `myInfo`
object is not a demonstrated case in the pinned SDK contract, so no broad exception
swallowing was added. The review's observations apply to its named files, not the later
CLI/speech modules, which intentionally perform explicit local output/file operations.

## Explicitly not yet proven

- Real Whisper inference in Phonemesh: adapter contract tested, no compatible local
  CTranslate2 model was provisioned for this build.
- Real TTS for the other profiles and native-speaker intelligibility for any language.
- Full speech → confirmed text → transport → speech user journey with real STT.
- Serial device connection, RF transmission, two-node reception, direct ACK, stock app
  rendering, reconnects or field legality/range/airtime.
- Android application, mobile model packaging, microphone capture, interactive editing
  and audio playback. CLI composition is the current user surface.

## Privacy and publication boundary

No radio was opened, no microphone used, no cloud speech called, no model training,
no existing recordings overwritten, and no audio played. The only generated audio is
local synthesis of an innocuous test sentence. No commits, pushes, PRs or staging.
The folder is a local project, not a published or licensed release.

The spec and implementation are candidates for future version control. Planning,
receipts, virtual environments and generated audio remain under ignored local paths.
The original handover now points to this v1 spec while retaining its historical v2 notes.
