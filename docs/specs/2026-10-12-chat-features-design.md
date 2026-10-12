# Familiar chat features

**Date:** 2026-10-12
**Status:** wave 1 built; waves 2 and 3 decided, to build.
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


## Wave 3: scheduled messages, versions a and b (decided: `USE_EXACT_ALARM`)

- The person records a message as usual and chooses "Later" with picture buttons: in 1 hour, this evening, tomorrow morning. There is no clock picker.
- **Version a:** the message waits on the phone and goes out when its time comes while Phonemesh is open and connected. If the app was closed, it goes out the next time it opens, marked late. No new permissions.
- **Version b:** the phone wakes at the time and sends even with the app closed.
  - This needs an exact-alarm permission and a short foreground service with a notification while it connects and sends, plus the radio within Bluetooth range.
  - It shares this machinery with the opt-in background roaming setting in the accounts design.
  - Permission: `USE_EXACT_ALARM` (no prompt), with `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`. The service shows a notification while it sends; on Android 13 and later that notification needs `POST_NOTIFICATIONS`, and the service still runs if the person refuses it.
- **Version c (with the hub):** the hub keeps the message and sends it on time.

## How-to pictures still to make

Each new gesture needs a talking picture (tip and Help screen), as the existing ones have:

- long press on a message (the menu, deleting)
- forward
- long press on a chat (pin, delete)
- reply and reactions (wave 2)
- scheduling (wave 3)

## Later: considered and not built now

These were weighed against the radio's capacity and the privacy goal. They stay possible later.

- **Read receipts ("seen") and "typing…":** each costs a radio message every time someone reads or types.
- **Photos and voice notes:** do not fit a 233-byte radio message. Photos are being studied in a separate track.
- **Edit, and delete for everyone:** neither can be guaranteed over the radio, and both cost radio time.
- **Location sharing:** needs a new permission and raises privacy questions.
- **Delete messages after a while (disappearing messages):** offered, not chosen this time.
