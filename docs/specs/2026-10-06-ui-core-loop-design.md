# Sub-project 3a: UI rebuild — core loop

**Date:** 2026-10-06
**Status:** design agreed with the project owner; awaiting written-spec review
**Read with:** `docs/specs/2026-10-06-phase3-decisions.md` (shared decisions; this spec records where it amends them)
**Follows:** sub-project 1 (lightweight foundation, merged). The owner moved the UI rebuild ahead of sub-project 2 (wire v2).

## Goal

Replace the Phase 2 test screen with the real interface for the core loop, so the owner can install it on a phone and use it hands-on: choose a language, set up the voice and the radio, see all chats, record, hear the draft, send, and play received messages, without reading a word.

## Split of sub-project 3

- **3a (this spec):** first launch, chats list, conversation, recording with draft, delivery states, radio connection screen, basic settings, light theme, French and English.
- **3b:** full settings, dark theme and Auto, long-press to hear a control, "stay listening" background mode, microphone hint and gesture demo, delete one / delete all messages, screenshot setting.
- **3c:** the Phase 3A smoothness and bug audit, run against the new screens.

## Amendments to the shared decisions

1. **Languages are a list, not two fixed modes.** English and Français ship now; Spanish is the likely next language after release. Adding a language means adding one registry entry, one strings file, one name clip and one voice pack, with no screen changes.
2. **Chats list instead of a single conversation.** The app opens on a list of all chats: groups and private (one-to-one) chats.
3. **Groups.** 3a has one group: the radio's primary channel ("Groupe"). Several named groups are wanted for the main release and form their own later stage. That stage must work when several people share one communal radio: messages encrypted in the app with per-person and per-group keys, an identity that belongs to the person rather than the radio, and one phone connected to a shared radio at a time. Communal radios acting only as relays already carry private traffic without being able to read it. 3a must not block this.
4. **Group membership is not knowable** on Meshtastic (anyone with the channel settings can listen). Groups show **people heard**: the number of distinct senders seen in the group, plus the user.
5. **Delivery for group messages** never shows a tick, because broadcasts have no recipient acknowledgement; they show "sent from my radio" instead.

## Screens

The owner chose each design from mockups.

### Language (first launch)

- A globe and two large buttons, French first: "🔊 Français", "🔊 English". Tapping speaks the language's own name from a clip built into the APK and continues.
- Shown once; changeable later in settings. The list comes from the language registry, so a third language adds a third button.

### Chats (home)

- Top bar: radio status (shape plus colour: grey diamond = not connected, green circle = connected), title "Discussions", gear.
- **Setup cards** above the list while something is missing:
  - **Voice:** "Voix française · 66 Mo · une seule fois" (the size counts only the packs still missing: engine plus voice, or the voice alone), ⬇ Télécharger / 📁 Fichier; progress bar and "↓ 42 %" while downloading.
  - **Radio:** "Connecter la radio · Allumez la radio, puis touchez." 🔍 Chercher.
  - A finished card turns green with ✓ and disappears. Cards return when something needs fixing (lost radio, missing voice).
- **Rows:** picture, name, last message (sender prefix in groups), time, unread badge, ✓/🕓/⚠ on the user's own last message.
  - Groups: rounded square, 👥 on yellow, with a people-heard count (👥 5).
  - Private chats: circle with 👤.
  - "Groupe" is pinned on top; the rest are newest first.
- ➕ starts a private chat: a list of radios the mesh has heard, by name ("Kofi") or "Radio 4a2f" when unnamed.
- Empty state: 💬 "Pas encore de messages".

### Conversation

- **Bubbles:** received on the left (sender name in groups), sent on the right.
  - Every bubble, including the user's own, has a play button: ▶, which becomes ❚❚ (green) while playing.
  - One message plays at a time; tapping another stops the first.
- **Bottom bar:** large microphone (about 80 dp, label "Parler") and a small ⌨️ button.
- **Recording:**
  - Hold the microphone to talk; slide up to lock (🔒); slide left to cancel ("◀ Annuler").
  - A red ring fills toward the limit of one radio part (200 bytes, about 18 s of speech) and recording stops by itself when it is full.
  - The ring will span about one minute once sub-project 2 lands.
- **Draft:**
  - The transcription (or typed text) appears as a dashed bubble.
  - The bottom bar swaps the microphone for three large buttons: ✕ Effacer · ▶ (the same play icon as sent messages) · ➤ Envoyer.
  - Nothing plays or sends by itself.
  - Tapping the draft text opens the keyboard to fix a word.
  - If the encoded text exceeds one part: red outline, "Trop long. Raccourcissez.", and ➤ stays disabled.
- **Not ready:** while the voice or radio is missing, the microphone is greyed. Tapping it returns to the Chats screen with the relevant setup card.

### Delivery states

Status is always shape plus colour.

| Radio reports (`DeliveryState`) | Private chat | Group |
|---|---|---|
| SUBMITTING | 🕓 grey clock | 🕓 |
| QUEUED (the user's radio accepted it for sending) | 🕓 | 📡 "parti" (final state for a group message) |
| ACKNOWLEDGED (the destination radio confirmed) | ✓ green tick | never occurs |
| UNCONFIRMED after QUEUED | ⚠ "Pas confirmé" + "↻ Renvoyer" | stays 📡; broadcasts always expire this way (`DeliveryTracker`) |
| UNCONFIRMED before QUEUED (connection lost first) | ⚠ "Pas confirmé" + "↻ Renvoyer" | ⚠ "Pas confirmé" + "↻ Renvoyer" |
| FAILED | ⚠ "Échec" + "↻ Renvoyer" | ⚠ "Échec" + "↻ Renvoyer" |

Both UNCONFIRMED and FAILED use the same red triangle shape, but their spoken and visible labels differ, so the UI never calls an unconfirmed message a failure. Resending an unconfirmed private message may duplicate it at the recipient; this is accepted.

- Resend reuses the same bubble with a new packet.
- Nothing is resent automatically. On reopen, a message still showing 🕓 when the app closed becomes ⚠ "Pas confirmé" with "Renvoyer"; a group message already at 📡 stays 📡.

### Radio

- "🔍 Recherche…" and nearby radios as large rows with signal bars. Tap to connect; Android's pairing prompt may appear.
- The app remembers the radio and reconnects by itself on later launches.
- Help line: "Pas trouvée ? Allumez la radio. Approchez le téléphone."

### Settings (basic, 3a)

- Langue (opens the language list).
- Langues (packs): the existing pack list, plus the Tiny/Base engine choice.
- Partager l'application.
- Licences.

3b adds the rest.

## Architecture

- **Framework-only Android**, as before: no AndroidX, AppCompat or Compose. Lists use `ListView` with adapters.
- **One Activity per job:**
  - `LanguageActivity`
  - `ChatsActivity` (launcher; also receives the pack VIEW/SEND intents)
  - `ConversationActivity`
  - `RadioActivity`
  - `SettingsActivity`

  Back and rotation use the platform's behaviour. Every Activity sets `FLAG_SECURE`, as today.
- **`AppCore`, a process-wide object** created on first use from `Application`. It owns:
  - the radio transport
  - the speech engine
  - `PackStore` and `PackDownloads`
  - the history database

  Activities subscribe to change notifications (new message, delivery change, radio state, pack change) and unsubscribe in `onStop`. Because the transport no longer lives in an Activity, rotation no longer drops the radio connection.
- **Swappable parts for testing:** `AppCore` depends on a `MeshRadio` interface, implemented by `BleMeshTransport`, and a `Speech` interface, implemented by `LocalSpeech`. Tests inject fakes.
- **Radio layer additions (`radio-ble`):**
  - `onText` reports whether the message was addressed to this radio (private) or broadcast (group).
  - A peers-changed callback, so names replace "Radio 4a2f".
  - Messages on channels other than the primary one are ignored in 3a.
- **No background service in 3a.** The radio is connected while the app is open. 3b's "stay listening" adds a foreground service holding the same `AppCore`.
- **Unchanged and reused:** `LocalSpeech`, `MicrophoneCapture`, `PackStore`, `PackDownloads`, `PackSharing`, `ShareProvider` and `MessageCodec` (200-byte limit, `[pm1:xx]` tag).

## History

- SQLite through the framework `android.database.sqlite`. Text only; never audio; never synced. `allowBackup=false` already keeps it off backups.
- **Tables:**
  - `chats` (id, kind GROUP or DIRECT, channel index or peer id, display name, last activity, unread count)
  - `messages` (id, chat id, direction, sender id, text, language, time, packet id, delivery state)
- Kept until the user deletes it (deletion UI in 3b).
- **Unread** clears when a chat is opened.
- **Language of received messages:** played with the voice for the message's language tag. When that voice is missing, the current voice is used and a small note is shown.

## Look, languages, size

- **Strings:** every user-facing string moves to `res/values/strings.xml` (English) and `res/values-fr/strings.xml`. The chosen app language, not the phone's, is applied to each Activity.
- **Language registry:** for each language, the tag, endonym, name clip and required voice pack.
- **Name clips:** generated once with the project's Piper voices into a format every supported Android version plays (minSdk 26). A few KB each, inside the APK.
- **Icons:** vector drawables, about 15–20, each with a spoken label in the chosen language. No emoji in the shipped UI; the emoji in this spec stand for the drawn icons.
- **Theme:** light, high contrast (white background, dark outlines, bold text). Green and red are never used without a shape. Colours are theme attributes so that 3b's dark theme is a swap.
- **Sizes:** body text at least 18 sp; touch targets at least 48 dp; microphone about 80 dp.
- **Words:** sentences of eight words or fewer; no technical terms ("radio", "message"; never "node", "packet", "ACK").
- **Size budget:** the APK ceilings stay (arm64 12,700,000 B; armeabi-v7a 11,700,000 B; currently 12,277,058 B arm64). The new UI is budgeted at about 150 KB in total. The existing build gate enforces the ceilings.

## Testing

- **Emulator first,** with fake radio and fake speech injected into `AppCore`. No microphone, playback or radio transmission in automated tests.
- **New instrumentation tests:**
  - first launch and language persistence
  - setup cards enable the microphone
  - record → draft → ▶ → send; ✕ discards
  - "Trop long" disables ➤
  - private vs group routing
  - 📡 for group and ✓ only for private messages
  - failure → Renvoyer
  - unread counts
  - one playback at a time
  - rotation keeps draft and radio connection
  - history survives restart
  - the pack import intents still work through `ChatsActivity`
- **Unit tests:** delivery-state mapping, history database, language registry, people-heard count.
- **Checks on every screen:**
  - every clickable view is at least 48 dp and has a content description
  - no visible string longer than eight words
  - no banned technical word in either strings file
- **Existing tests** that depend on the old screen are rewritten against the new screens so that the behaviours they protect remain covered: the permission set, honest delivery wording, the pack manager, import confirmation, and the engine choice.
- **Screenshots:** a test-only build without `FLAG_SECURE` captures each screen state into `docs/screenshots-phase3/` for the owner.
- **Real radio:** after the emulator suite is green, an owner-assisted check with the owner's radios. The owner performs any transmission, or explicitly authorises it.

## Done when

- `scripts/check.sh` ends `CHECK_OK`, including the size gate.
- The emulator suite passes.
- Screenshots are saved.
- The app is installed on the owner's Pixel with packs pushed to Download.
- The owner has walked the loop: language → setup → chats → record → ▶ → send → play a received message.

## Non-goals

- Everything listed for 3b and 3c.
- Named groups and in-app encryption.
- Multi-part and long messages (sub-project 2).
- Any change to the wire format or radio configuration.
