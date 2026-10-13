# Familiar chat features

**Date:** 2026-10-12
**Status:** waves 1, 2 and 3 built.
**Read with:** `docs/design.md` (wire contract: 200 UTF-8 bytes on TEXT_MESSAGE_APP, tag included), `docs/android-design.md`.

## Goal

Add chat features people know from WhatsApp and Telegram, as long as they fit a slow, shared radio. A village shares roughly 150 full-length messages an hour (LongFast, EU 868, 10% duty cycle). So a feature that sends nothing over the radio is cheap, and a feature that sends one radio message per action needs a reason.

## Owner decisions (2026-10-12)

1. Settings → "Show the guide again" replays the welcome story and brings every tip back; the radio and voices stay.
2. Deleting a message: long press, then a confirm. It deletes on this phone only.
3. Size: the growth allowance and both APK ceilings were raised by 100 KB.
4. Build all of: read new messages aloud, copy/share, day dividers, pin a chat, delete a whole chat, forward, reply, reactions.
5. Reactions are text messages that the app recognises and shows as reactions.
6. Scheduled messages: versions a and b now; c (the hub sends on time) comes with the hub.
7. Photos are designed in a separate track and are out of scope here.
8. The features under "Later" are kept for possible future work, not dropped.
9. Replies and reactions use Meshtastic's own `reply_id` and `emoji` fields (wave 2).
10. Scheduled version b uses `USE_EXACT_ALARM`: no prompt. The app is shared as an APK; Google Play would refuse this permission for a chat app.
11. Read new messages aloud: off by default (decided by the developer for privacy; the owner can change it).

## Wave 1: no radio use (built)

- **Long-press menu on a message:** Copy, Share, Forward (only when another chat exists), Delete. Each is a large button with a picture.
  - Delete asks once more: "Only on this phone. Others keep it."
  - TalkBack reads each bubble (sender in a group, then the words) and names the long press.
- **Forward:** the words go into the chosen chat's draft and that chat opens, so the person hears and sends them there. Nothing goes out until Send. Text too long for the draft is cut like a long recording.
- **Day dividers:** "Today", "Yesterday", or the long date in the app's language, above the first message of each day. A phone with a wrong clock shows dates, never "Today" for a future time.
- **Pin a chat:** long press on a private chat → "Pin to top" / "Unpin". Pinned chats come right after the group, which is always first.
  - The history database moves to version 2 (`chats.pinned`). Phones with version 1 keep their chats.
- **Delete a whole chat:** long press on a chat → "Delete chat", then a confirm.
  - A private chat goes, along with its draft and any playback; a new message from that radio starts it again.
  - The group only empties.
- **Read new messages aloud:** a Settings switch, off by default because phones are often used in public.
  - A message is read only while a Phonemesh screen is open, the microphone is free and nothing else is playing.

## Wave 2: replies and reactions (built; Meshtastic's fields)

- **Received messages keep their radio id** (`MeshPacket.id`), so replies and reactions can point at them. The history moves to version 3 (`messages.reply_to`, `messages.reaction`).
- **Reply:** long press → "Reply". A bar above the draft shows the words being answered, with a cross to stop replying.
  - The next Send carries `Data.reply_id` = the answered message's radio id. The quoted words are not sent, so a reply costs no extra bytes.
  - A reply bubble shows the quote from this phone's own copy, or "A message not on this phone".
- **React:** long press → "React" → six large pictures (👍 ❤️ 😂 😮 😢 🙏).
  - A reaction goes out as the emoji alone, with `Data.emoji = 1` and `Data.reply_id`, as the official apps send it. It is one radio message, is not tracked for delivery, and is kept only once the radio took it.
  - Reactions show under the bubble ("👍 2  ❤️"), one per person (a new one replaces theirs). They are never a bubble, never unread and never read aloud.
  - A received reaction over 32 bytes is not a reaction and is dropped.
- **Only a message that went over the radio can be answered or reacted to:** one that never left the phone has no id the other phones know.


## Wave 3: scheduled messages, versions a and b (built; `USE_EXACT_ALARM`)

- **Planning:** the person records as usual, then taps "Later" under the draft. Picture buttons with their times: "In 1 hour", "This evening" (19:00, offered only before 18:00), "Tomorrow morning" (08:00). There is no clock picker (`PlanTimes`, JVM-tested across midnight, month ends and the clock change).
  - The draft, and any reply being made, becomes a planned message, kept apart from messages (history version 4, table `planned`).
  - Planned messages wait above the draft, each with its time ("Sends at 19:00" / "Sends tomorrow at 08:00") and a cross ("Do not send").
- **Version a:** `AppCore.sendDue()` sends every due message when the radio becomes ready, from a timer while the process lives (again a minute later when the radio was away or sending was blocked by the region), and from the alarm.
  - A sent planned message is an ordinary message with "Planned for 19:00" under it. One the radio refuses becomes a failure the person can resend.
- **Version b:** an exact alarm (`setExactAndAllowWhileIdle`; `USE_EXACT_ALARM` from Android 13, `SCHEDULE_EXACT_ALARM` up to 12; an inexact alarm if Android still refuses) wakes `PlannedReceiver`. That starts `PlannedSendService`, a short foreground service (Android 14's `shortService`) with a quiet notification.
  - The service holds the radio like an open screen (the same reconnect and the same grace before letting go), sends what is due, and stops when nothing is due or after 90 s. What is left goes out when the app next opens.
- **Permissions added:** `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (Android 12 and older), `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` (declared, never asked for).
  - The `connectedDevice` service type was dropped for `shortService`: it would need the Bluetooth permission at start, and a refused one would make Android crash the app. So `FOREGROUND_SERVICE_CONNECTED_DEVICE` is not used.
- **Limits:**
  - Alarms do not survive a phone restart (no `RECEIVE_BOOT_COMPLETED`); after a restart, planned messages go out when the app next opens.
  - The radio must be within Bluetooth range at the time.
- **Version c (with the hub):** the hub keeps the message and sends it on time.

### Choosing the time, revised (owner approved 2026-10-12; to build)

The three preset choices above are replaced: the owner does not want preset times. "Later" opens a window, "Envoyer plus tard", in the app's own look (large outlined buttons, yellow when chosen):

- **Day:** "Aujourd'hui" and "Demain" buttons, and a full-width calendar button, "Choisir un jour". The calendar button opens Android's `DatePickerDialog`, limited to today through one year ahead. The chosen day then shows on the button ("jeudi 15 octobre") in yellow.
- **Time:** a large time button between − and +.
  - Tapping the time opens Android's `TimePickerDialog` (the hour, then the minutes; 24-hour or 12-hour as the phone is set).
  - − and + move the time by 15 minutes. − stops at the next quarter hour after now.
  - A sun or a moon beside the time shows day or night (moon from 19:00 to 06:00).
- **Opens on:** today, one hour from now, rounded up to the next quarter hour.
- **A time already past:** it turns red, "Cette heure est passée." shows, and "Planifier" stays off until a later time is chosen.
- **Speaker:** it reads the choice aloud ("Aujourd'hui à 17 h 45", "Demain à 8 h", "Le jeudi 15 octobre à 8 h").
- **"Planifier"** plans the message.
- **The planned row:** "Envoi à 18:15", "Envoi demain à 08:00", or for a later day "Envoi le jeudi 15 octobre à 08:00".
- **New icons:** calendar, sun, moon.
- **`PlanTimes`:** gives way to rules for the window's default time, the − floor, the past check and the one-year limit. These stay JVM-tested across midnight, month ends and the clock change.
- **Reference:** `.local/visual-guide-mockups/phonemesh-howto-chat-r10.html`, section A, a working mockup.

## How-to pictures (owner approved 2026-10-12; to build)

Eight talking pictures, approved in round 10: `.local/visual-guide-mockups/phonemesh-howto-chat-r10.html`.

- **Conventions:** the same kit as the visual guide (`HowToKit`, the finger, the wax band, 220 × 110 units). Every picture must match the real screens exactly: the app's own icons, its menus and their words.
- **Port as before:** timings into `HowToScenes`, drawings into `HowToDrawings`, sentences into `HowTo` and the strings, then screenshots for review.
- **Tips:** only "hold a message" is a one-time tip, shown once after a message has arrived and the "A message came" tip was closed. The other seven go in a new Help group, "Autres gestes" (EN "More you can do").

| Scene | Sentence (EN / FR) |
|---|---|
| `hold` | Hold a message to see your choices. / Maintenez un message pour voir les choix. |
| `reply` | Tap "Reply" to answer one message. / Touchez « Répondre » pour répondre à un message. |
| `react` | Tap a picture to answer quickly. / Touchez une image pour répondre vite. |
| `forward` | Send the same words to another chat. / Envoyez les mêmes mots à une autre discussion. |
| `delete` | Deleted here only. Others keep it. / Supprimé ici seulement. Les autres le gardent. |
| `pin` | Hold a chat to pin or delete it. / Maintenez une discussion pour l'épingler ou la supprimer. |
| `later` | Tap "Later" and choose when it goes. / Touchez « Plus tard » pour l'envoyer à l'heure choisie. |
| `later_pick` | Tap the time or the calendar to choose. / Touchez l'heure ou le calendrier pour choisir. |

Four approved sentences (hold, pin, later_pick, and English later) were longer than the app's eight-word rule (`CopyRules`). They were shortened when built; the table shows the built wording.

UI changes these pictures require:
- **"Transférer à…":** shows each chat's picture beside its name (the group's yellow square, a person's circle).
- **The new time window:** see "Choosing the time, revised" above.
- **`later_pick`:** its clock and calendar are drawn from screenshots of Android's real pickers in the app's green, taken once they are built.

## Later: considered and not built now

These were weighed against the radio's capacity and the privacy goal. They stay possible later.

- **Read receipts ("seen") and "typing…":** each costs a radio message every time someone reads or types.
- **Photos and voice notes:** do not fit a 233-byte radio message. Photos are being studied in a separate track.
- **Edit, and delete for everyone:** neither can be guaranteed over the radio, and both cost radio time.
- **Location sharing:** needs a new permission and raises privacy questions.
- **Delete messages after a while (disappearing messages):** offered, not chosen this time.
