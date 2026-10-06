# Phase 3 product decisions

**Date:** 2026-10-06
**Status:** agreed with the project owner
**Applies to:** sub-projects 1 (lightweight foundation), 2 (wire v2), 3 (UI rebuild)

This records the product decisions that shape Phase 3. Amended by `docs/specs/2026-10-06-ui-core-loop-design.md` (sub-project 3a): the UI rebuild now comes before wire v2, languages are an extensible list, the app opens on a chats list with groups and private chats, and named groups form a later stage. Each sub-project has its own design spec; this document is the shared starting point.

## Context

- **Target region:** Togo first. French-first, English second.
- **Users:** people with low or no literacy who speak French or English, in areas with poor connectivity, often on low-end Android phones.
- **Licence:** AGPL-3.0. Phonemesh is a public good. The sherpa-onnx native libraries already include GPL-3.0 espeak-ng code, so a GPL-family licence is required in any case; AGPL-3.0 is compatible.

## Lightweight first

Every change is judged against three costs, in priority order:

1. **Download and sideload size**: APK and voice packs.
2. **Low-end phone cost**: RAM, CPU, cold start, battery.
3. **Radio airtime**: bytes and packets on LoRa.

Quality trades (smaller but lossy models or voices) are adopted only when objective checks pass **and** the owner signs off: for voices, the owner cannot hear a difference in a blind A/B listening test of the comparison clips; for the recognizer, transcripts of every comparison clip are identical, or the owner reviews a side-by-side diff of every changed transcript and accepts it.

The app stays framework-only Android (no AndroidX, AppCompat or Compose), as it is today. This is a constraint that keeps size and cold start down, not a new saving; it means the conversation list uses `ListView` and screen state is retained by hand.

## Network

- The app uses the network **only to download language packs during setup**, through Android's `DownloadManager`, which requires the `INTERNET` permission. This replaces the earlier "no Internet permission" constraint.
- Because that permission is all-or-nothing, the boundary is enforced by convention plus a build check on the compiled app: one class owns all download URLs, and the build fails if networking APIs appear anywhere else. It is a guardrail, not an operating-system sandbox.
- Packs are hosted in a public Hugging Face model repository, with URLs pinned to a commit. Until publication the base URL is configurable.
- Backup route: offline sharing of the APK and installed packs from phone to phone through the Android share sheet. Received packs are verified against the same pinned manifests.

## Messages

- Speech is transcribed locally; text only crosses the radio; audio never does.
- **Target** maximum message length is about **one minute of speech**, through lossless text compression plus up to about three radio parts; sub-project 2 measures and validates it. One radio packet holds roughly 16–18 seconds of French speech as text. The receiver plays a message only when all parts have arrived.
- A ring fills while recording so the length limit is visible without reading.
- **History is kept on the device indefinitely**, as text only, and never synced. The user can delete one message or all messages.

## Background behaviour

- Default: the radio connection is active while the app is open; messages queued on the radio are collected when the app reopens.
- A "stay listening" setting (default off) keeps the connection in the background with a persistent notification, for users who need alerts.

## Interface

- **Layout:** a WhatsApp-style conversation. Received messages on the left with a large play button, sent messages on the right, one large microphone in the bottom bar.
- **Recording:** hold the microphone to talk, or tap / slide up to lock hands-free recording, as in WhatsApp. Slide left to cancel.
- **Confirm before send:** recording produces a draft bubble with the transcribed text and a read-aloud button, so a sender who cannot read can hear exactly what will be sent. ✗ discards; ➤ sends. Nothing is sent without that explicit tap.
- **Status:** always shape plus colour, never colour alone. Clock = waiting; tick = delivered to the other radio; triangle = failed, tap to resend; part-ring = long message arriving ("2 / 3").
- **Style:** WhatsApp-like shapes in a high-contrast theme (white background, dark outlines, bold text) for sunlight and dim screens, plus a matching dark theme. Theme setting: Auto / Light / Dark, default Auto.
- **Languages:** two modes, English and Français. There is no picture-only mode. One choice sets both the interface language and the speech language. Every word on screen sits with an icon; icons lead, words are short and secondary.
- **Long-press to hear:** long-pressing any control speaks its name in the chosen language and never performs the action. The **microphone is the exception**: holding it always records. The microphone instead gets a one-time spoken hint and an animated gesture demo when it first becomes available, replayable from settings, never played while recording.
- **First launch:** a single screen asks English or Français, each choice speaking its own name from tiny built-in clips. The app then opens directly into the conversation, with setup cards at the top (download the voice, connect the radio). The microphone is disabled until both are done. The same cards return later when something needs fixing, such as a lost radio connection.
- **Settings** (behind a gear): WhatsApp-style list with language, theme, stay listening, radio, languages (packs), share the app, replay the microphone hint, delete all messages.
- **Accessibility:** touch targets at least 48dp, full TalkBack labels, sentences of eight words or fewer, no technical terms ("radio", "message", "sent"; never "node", "packet", "ACK").

## Sub-project order

1. **Lightweight foundation**: APK slimming, shared engine plus per-language voice packs, in-app downloader, sharing.
2. **Wire v2**: compression and multi-part messages, with Python reference parity.
3. **UI rebuild**: the interface above, plus the Phase 3A smoothness and bug audit.

Radio-dependent evidence (live ACK and reconnect behaviour, RF soak, battery) remains Phase 4.
