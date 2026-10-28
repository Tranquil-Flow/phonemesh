# Demo coverage

What a designer or tester can do in the demo build ("Phonemesh Demo"), screen by screen, and how. The demo runs the
real app. Only the radio, the other people, their phones and the local hub are simulated (see
`docs/specs/2026-10-25-demo-build-design.md`).

**Verified** means walked on an Android 15 emulator on 2026-10-26, with no crash reported in the system log during the
walk. The laptop launcher was run once from a clean folder on a Mac with Apple silicon: first start 11 minutes,
6.2 GB on disk; a second clean Mac run took 3.5 minutes on a faster connection. **Not yet** means the screen is reachable but was not walked.

Demo controls open from the notification "Phonemesh Demo · Tap for the demo controls" (pull down from the top of the
screen) or from the "Demo controls" icon.

## First start and setup

| Flow | How, in the demo | Real or simulated | Verified |
| --- | --- | --- | --- |
| Welcome story, choose language | Open the app | Real | Yes |
| Download the voice (step 1) | **Download** | Real (downloads 74 MB from the published catalog) | Yes |
| Voice "From a friend" (step 1) | **From a friend** shows the guide; receiving needs files from another phone with Phonemesh | Real guide; the file transfer needs a second device | Guide only |
| Say your name (step 2) | **Say my name**: speak, or type in the name box; pick a colour; **Save** | Real; speaking needs a microphone (phone, or laptop mic) | Verified: spoken on the Mac (host microphone access on) and typed |
| Find and connect the radio (step 3) | **Find radio** lists "Demo radio"; tap it | Simulated radio | Yes |
| Radio region by flag | Shown when the phone's country has radio rules | Real check; simulated radio write and restart | Verified with **Pretend country: Togo** in the demo controls: flag, **Yes**, the radio restarts, step done |
| Region "For helpers" | **For helpers**, pick a region, tick the box, **Set** | Real; simulated radio write and restart | Yes |
| Add someone (step 4) | **Add someone**, then **Scan a code**, then pick "Kofi's code" | Real accounts; the camera is replaced by a picker | Yes |
| Say hello (step 5) | **Try it** opens Everyone nearby | Real | Yes (typed) |

## Chats and messages

| Flow | How, in the demo | Real or simulated | Verified |
| --- | --- | --- | --- |
| Shared chat: send | Everyone nearby, keyboard, **Send** | Real; Sent | Yes |
| Shared chat: someone posts | Controls: "Someone writes in the shared chat" (Kofi and Yaw also post once after the radio first connects) | Real wire; simulated person | Yes |
| A Meshtastic app user writes to everyone | Controls: "Sena (Meshtastic app) writes to everyone" | Real; simulated person | Yes |
| A Meshtastic app user writes to you | Controls: "Sena (Meshtastic app) writes to you"; it lands in **Old chats**, which are read-only in v0.1.0 | Real | Yes |
| Private message: Sent, then Delivered, and a reply | Open Kofi or Yaw, type or speak, **Send** | Real encryption, receipts and retries; simulated people reply | Yes |
| Reached the hub, then Delivered through the hub | Join the hub, then Controls: "Kofi goes out of range", then send to Kofi | Real hub engine; simulated radios | Yes |
| Waiting for a radio | Controls: "Switch the radio off" (and the hub off), then send | Real | Yes |
| Radio comes back, waiting messages go | Controls: "Switch the radio on" | Real | Yes |
| Receive a picture | Controls: "Kofi sends a picture" | Real picture wire; a drawn picture | Yes |
| A contact changes their picture | Controls: "Kofi changes his picture and writes" | Real profile update behind his message | Yes: 🐟 became 🐠 with his next message, and only the message counted as unread |
| Send a picture | **Send a picture**: **Take a photo** or **Choose a photo** | Real; the emulator's camera shows a virtual room, a phone uses its own | Verified: **Take a photo** asks for the camera first, then sizes, colour, **Send**, Delivered |
| Message actions: Reply, React, Copy, Share, Forward, Delete | Long-press a message | Real | Menu and React: yes |
| React with any one emoji | Long-press, **React**, **Other emoji**, then the keyboard's emoji panel | Real | Dialog and refusal of text: yes. An emoji itself: by the app's own emulator test (adb cannot type emoji) |
| Send later (planned) | Speak a message, then **Later** | Real; needs a spoken draft | Not yet on the Mac (**Later** appears only for a spoken draft) |
| Read aloud and Listen | The speaker buttons | Real (needs sound) | Verified: **Listen** plays through the Mac's speakers |
| Talk (speech to text) | Hold **Talk** | Real; needs a microphone | Verified on the Mac: speech becomes the draft (the launcher keeps host microphone access on) |

## People and safety

| Flow | How, in the demo | Real or simulated | Verified |
| --- | --- | --- | --- |
| Meet in person (QR codes) | Add someone, Scan a code, "Kofi's code" | Real accounts | Yes |
| Open someone's link | Scan a code, "Yaw's link"; Yaw accepts and writes back | Real | Yes |
| Someone sends you a request | Ama sends one 90 seconds after the radio first connects, or Controls: "Ama adds you by link"; **Accept** or **Ignore** | Real | Yes (Accept) |
| Safety pictures: same six on both phones? | Shown after a link or request; compare with Controls: "Their phones" | Real on both sides | Yes |
| Contact details, voice for this person | Tap the name at the top of a chat | Real | Yes |
| Start again with a contact | Contact details | Real | Not yet |
| Not a Phonemesh code | Scan a code, "Something that is not a Phonemesh code" | Real | Verified: "This code does not work." |

## Hub

| Flow | How, in the demo | Real or simulated | Verified |
| --- | --- | --- | --- |
| Local hub row in Settings | Settings | Real | Yes |
| Save the hub | Settings, Local hub, **Scan hub code**, "The local hub's code" | Real hub engine; no Wi-Fi join, no TLS | Yes |
| Sending by Hub Wi-Fi | Default after joining | Simulated way | Yes |
| Sending by Hub Bluetooth | Controls: "Switch the hub's Wi-Fi off" | Simulated way | Yes |
| Hub off, back to the radio | Controls: "Switch the hub off" | Real fallback | Yes |
| Keep hub when app is closed | Local hub screen switch | Real service and notification | Verified: "Running in the background." and a silent notification |
| Forget this hub | Local hub screen | Real | Verified: confirm, then "No hub saved." |
| Pack update from the hub | Settings, Local hub, **Updates**, **Download** (the hub offers "Demo voice") | Real screens, verifier, trust state and downloader; demo-only keys the real app rejects; the hub's files are served in-process; no Wi-Fi join, no TLS | Verified: "An update is ready.", progress, Pause, Resume, "Installed." |

When Wi-Fi works, the hub screen says "Bluetooth: Looking", because the app releases Bluetooth once Wi-Fi has answered
through the hub's pinned key. This is the shipped behaviour, worth a design look.

## Settings and help

| Flow | How, in the demo | Verified |
| --- | --- | --- |
| Language (French and English) | Settings, Language | Yes |
| Appearance (Auto, Light, Dark) | Settings | Yes (Dark) |
| Languages (packs): download, remove | Settings | Screen: yes |
| Understanding speech (Fast or Accurate) | Settings | Screen: yes |
| Show my code, My name | Settings | Verified (**My name**, spoken and typed) |
| Read new messages aloud | Settings | Not yet |
| Show the guide again, Licences, For helpers | Settings | **Licences** and **For helpers** verified; the licence notices are in French only |
| Share the app | Settings, **Share the app**: the share sheet offers the app and its voice files | Yes |
| Help | **?** on Chats | Yes |
| Notifications | New messages while the app is closed | Not in the app: there are no notifications for new messages, only for planned sends and the hub in the background |

## Not in the demo

- **Real radio and range behaviour.** Timings follow LongFast airtime but are simulated.
- **The Wi-Fi join and the hub's TLS key check.** The demo's hub links answer in-process.
- **Pairing a Bluetooth radio, and Android's Bluetooth permission and "turn on Bluetooth" screens.** The demo radio
  needs neither.
- **Receiving voice packs from a friend's phone.** This needs a second device. Two phones with the demo can do it.
