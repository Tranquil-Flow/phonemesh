# Accounts not tied to a radio: design

Status: draft for the owner's review, 2026-10-14. No account code is written before this is approved.

## Goal

A person, not a radio, has the account. Many people share each radio (10–20 or more at once). Someone can use several
radios in a day. A private message reaches that person on whichever phone-and-radio they use, and only they can read it.

## Decisions taken

From handover 3 and the owner on 2026-10-14:

1. Accounts live on the phone. Radios are communal, owned by an establishment.
2. The first build includes accounts over Bluetooth with stock radios, plus a hub prototype on the Mac: a Heltec on
   USB, phones on the same Wi-Fi. A travel router or a spare Android phone comes after the field test.
3. Encryption uses P-256, which is built into Android on every version, with no added library. Every message carries a
   key-type marker, so a later switch (X25519, quantum-safe) does not strand old phones.
4. Signal-level protection where the radio can afford it: forward secrecy, healing after a compromise, hidden sender
   and recipient. The costs are worked out below.
5. Quantum-safe encryption comes after release.
6. People add each other by QR code or by link. There is no username search and no account list for now.
7. A larger app is allowed. The size ceilings are raised by what this work measures.
8. Each account has a spoken name (said once, written by the app, read aloud on other phones) and a colour the person
   can change. Each listener picks a voice per contact.
9. Roaming between radios in the background is an opt-in setting; by default the app roams only while it is open.
10. The shared chat gets its own channel key, on the same frequency as public Meshtastic.
11. Old radio-addressed chats are not migrated.
12. No account recovery in the first build; recovery is required in a later build (owner, 2026-10-14).
13. The hub keeps mail for 30 days by default; the hub's admin can change it (owner, 2026-10-14).
14. "Delivered to their phone" receipts are on, batched as described (owner, 2026-10-14).
15. Links use GitHub Pages (`tranquil-flow.github.io/phonemesh`). The owner approved retaining this address for
    v0.1 on 2026-10-23, superseding the earlier requirement to buy a domain before release. A later move must address
    compatibility with existing invitations; website publication remains owner-only.
16. The router hub's language is chosen after the field test, with the hardware; the Mac prototype is Kotlin
    (owner, 2026-10-14).
17. People pick their own display name: said and written by the app, or typed, and changeable later. Full account
    names (unique, searchable) come later, once designed (owner, 2026-10-14).
18. The owner has two radios: T1 and the field test use both. The GitHub Pages link site may be published when the
    app needs it (owner, 2026-10-14).
19. Phones also reach the hub over Bluetooth, so a village needs no Wi-Fi at all; Wi-Fi stays as the faster path.
    Built with step 16 (owner, 2026-10-19).
20. The accounts feature is finished on Meshtastic as planned. Reticulum and RNode are looked at afterwards, not
    before (owner, 2026-10-19).
21. Adding someone is one screen, "Ajouter quelqu'un": this phone's code on top, then "Scanner un code" and "Envoyer
    mon lien". A person added by link can talk at once and is marked "Pas rencontré" (Not met) until the safety
    pictures are compared. The safety picture screen has no button that reads the names aloud (owner, 2026-10-19).
22. Accept, Ignore and "Ce sont les mêmes" carry a large tick or cross as well as the word, and the how-to pictures
    for them work without reading (owner, 2026-10-19).
23. The safety picture screen also has a red ✕ "Pas les mêmes" (Not the same): it warns that this may not be the
    person and keeps the contact "Pas rencontré" (owner, 2026-10-19).
24. A hub must not strand a message at "Reached the hub" because its one radio airing was missed or the receipt was
    lost (owner, 2026-10-25, before v0.1). The sender's normal retries (2, 10, 30, then every 30 minutes, up to three
    days) go to the same pinned hub as the exact stored bytes; the hub airs them again only on that request, at most
    once a minute per packet, and never past three days from first storage. Only the recipient's receipt is Delivered.
25. TalkBack spoken control is deferred to after v0.1 and listed as a known limitation (owner, 2026-10-25): with
    TalkBack on, recording captured TalkBack's own speech and the send status was not announced.
26. Hub Wi-Fi must be WPA2-Personal (or open) for v0.1; documented, no code change (owner, 2026-10-25). Android 10
    phones cannot join a WPA2/WPA3 mixed-mode network through the app's Wi-Fi request.
27. The "village hub" is renamed **local hub** (French *Relais local*), because hubs will not only serve villages
    (owner, 2026-10-25). The documentation uses the new name from v0.1.0; the app's two strings per language
    (`hub_title`, `roaming_notification`) change in the next version, so the v0.1.0 app still shows "Village hub" /
    "Relais du village" and the docs say so where they name that screen.
28. Private group chats (a group of chosen contacts, encrypted for its members only) are not in v0.1.0 and are
    planned for the next version (owner, 2026-10-25). v0.1.0 has one shared chat per radio channel and private
    one-to-one chats. Group keys and membership need their own design before building.
29. Reactions offer the phone's own emoji keyboard in the next version, not only the six fixed pictures
    (owner, 2026-10-25). A reaction stays one emoji within the 32-byte reaction limit.
30. Quantum-safe keys are shelved: not in the next version (owner, 2026-10-26). They stay in "Later" with the size
    problem written down (an ML-KEM-768 public key is 1,184 bytes and a ciphertext 1,088, against 228-byte packets).
31. The next version's plan also covers, each with its own spec before any code (owner, 2026-10-26): local languages
    for the Togo pilot, encrypted chat backup, an airtime meter, and signed app and voice-pack updates passed on
    through the local hub. Account recovery (decision 12), private group chats (28) and more profile choices than a
    colour get specs too. A demo build (`docs/specs/2026-10-25-demo-build-design.md`) ships with every release.
    The specs, each ending with its own lettered decision batch, are
    [private group chats](2026-10-26-private-group-chats-design.md) (GC),
    [account recovery](2026-10-26-account-recovery-design.md) (RC),
    [encrypted chat backup](2026-10-26-encrypted-chat-backup-design.md) (BK),
    [profile flexibility](2026-10-26-profile-flexibility-design.md) (PF),
    [local languages for the Togo pilot](2026-10-26-togo-local-languages-design.md) (LG),
    [the airtime meter](2026-10-26-airtime-meter-design.md) (AM) and
    [updates through the local hub](2026-10-26-hub-updates-design.md) (UP). Each owner pick is recorded here as a
    numbered decision from 32 on, naming its batch item (for example "32. GC1: pairwise fan-out").
32. The local hub runs on Linux and Windows as well as macOS (owner, 2026-10-26). Bluetooth for those hubs comes as a
    Linux bridge first (BlueZ; one phone at a time, because BlueZ notifies every connected phone), then Windows.
33. Windows support is tested on a real Windows machine before the hub is called supported there (owner, 2026-10-26).
34. Release scope (owner, 2026-10-26): v0.1.1 brings profile choices, the airtime meter and account recovery with a
    contacts-only backup; v0.1.2 brings private group chats, full chat backup and hub updates. Togo languages start with
    reviewed text once the pilot village and its languages are named.
35. App size: each feature's signed growth is measured, then the ceiling is raised for that feature; the v0.1.1 total
    stays within +256 KB per ABI (owner, 2026-10-26).
36. No phone runs v0.1.0 (owner, 2026-10-26), so new versions need no compatibility with it: reactions use the full
    32 bytes of decision 29, and profiles use the new contact card and hello everywhere.
37. Releases carry an x86_64 demo APK for laptops; no browser-streamed demo (owner, 2026-10-26).
38. The `v0.1.1` branch may be pushed to the public repo, without a tag or release (owner, 2026-10-26).
39. Replies to old chats with people on the stock Meshtastic app come in v0.1.2; the hub screen says Bluetooth is not
    needed while Wi-Fi works (owner, 2026-10-26).
40. Group chats (owner, 2026-10-26): GC4 (with close-and-recreate as the way out), GC7, GC11, GC12 and GC13 as
    recommended; GC9 is moot (decision 36). GC1, GC2, GC3, GC5, GC6, GC8 and GC10 are reopened: the owner wants groups
    larger than five, membership like ordinary messaging apps, history for new members, a group photo and full pictures.
41. Account recovery and chat backup: RC1-RC8 and BK1-BK8 as recommended, plus an independent cryptography review of
    the backup format before release (owner, 2026-10-26).
42. Profiles (owner, 2026-10-26): PF1, PF5, PF6 and PF7 as recommended; PF4 uses the new card everywhere (decision 36).
    PF2, PF3 and PF8-PF10 are reopened: more colours, many more emoji and photo avatars are wanted.
43. Togo languages: LG1-LG11 as recommended, once the village and its languages are named (owner, 2026-10-26).
44. Airtime meter: AM1-AM9 as recommended (owner, 2026-10-26).
45. Hub updates: as recommended except UP3, where bulk updates go over hub Wi-Fi only (the Linux Bluetooth bridge
    serves one phone at a time), and UP6, where USB imports come first (owner, 2026-10-26).
46. Voice imprints: laboratory stages A and B only, with no real voices; VI2-VI6 wait for their results
    (owner, 2026-10-26).
47. Private groups use one shared group secret, renewed whenever someone joins or leaves, with each member's own
    sending chain derived from it and every message signed: one radio packet per group text. Membership works like
    ordinary messaging apps (admins, members need not know each other, last admin leaving promotes someone), with a
    per-group "New members see recent messages" setting (off by default), a small group photo and full pictures.
    Groups start at 20 people. A thorough review of everything sent over the radio comes first (owner, 2026-10-26).
48. Profile colour is chosen on a free colour wheel (three bytes); the app picks a readable letter colour
    (owner, 2026-10-26).
49. Avatar emoji: any single emoji that every supported phone can draw (Android 8.0's emoji); emoji newer than that,
    with the initial as a fallback, are considered later (owner, 2026-10-26).
50. Photo avatars: about 64 by 64 pixels, end-to-end encrypted to contacts, never in the shared chat; over hub Wi-Fi
    when possible, otherwise slowly over radio with the next message to each contact, at most one change a day
    (owner, 2026-10-26).
51. No pronouns or "about" line (owner, 2026-10-26).
52. The designer demo targets the Mac first; an iPhone version is skipped for now, since iPhones cannot run the
    Android app (owner, 2026-10-26).

## What an outsider can and cannot learn

Anyone in range with a LoRa receiver hears every packet. With this design:

| Visible to an outsider | Hidden from an outsider |
|---|---|
| A packet was sent, when, and its size | Every word, voice transcript, picture, reaction and reply |
| Which radio transmitted it (Meshtastic header) | Who sent it and who it is for: the recipient code changes every message (see "Recipient codes") |
| The channel hash | That two messages belong to the same conversation |

The radio a person uses, and the hub, see the same as an outsider. The hub also learns which recipient codes a phone
asks for (see "Hub").

Limits:
- The shared chat is protected by one channel key that every member's radio holds. A leaked key exposes the shared chat,
  never private messages.
- Names in the shared chat are claimed, not proven. A signature would cost 64 bytes per message; it is a later choice.
- A phone's own history sits in the app's private storage, protected by Android's file encryption while the phone is
  locked. History is not encrypted separately in the first build.

## Accounts and keys

- **Account:** created on first launch.
  - An identity key pair (P-256).
  - The spoken name, as text written by speech recognition and corrected by the person if needed.
  - A colour.
  - An account id: the first 8 bytes of SHA-256 of the identity public key.
- **Where the private key lives:**
  - Android 12 and later (the Pixel): in the hardware Keystore, never readable by the app. Keystore ECDH needs
    Android 12.
  - Android 10 and 11 (the Fairphone): a software key, stored encrypted under a Keystore AES key.
  - Backup is already off (`allowBackup="false"`); keys never leave the phone.
- **Safety picture:** each pair of contacts sees the same six icons out of 64, derived from both identity keys with a
  slow hash (PBKDF2-HMAC-SHA256, 65,536 rounds). Comparing them side by side replaces Signal's 60-digit safety number
  for people who do not read. Faking a match takes about 2^52 hashes (owner pick after review F4).
- **Lost phone:** the first build has no recovery. A new phone makes a new account, and contacts add it again.
  Recovery is a required feature of a later build (decision 12).

## Adding someone

### QR code, in person

Each phone shows a QR code holding a contact card:

| Field | Bytes |
|---|---|
| Version, key type | 1 |
| Identity public key, compressed | 33 |
| Signed prekey, compressed | 33 |
| Prekey signature (ECDSA P-256, raw) | 64 |
| Name, UTF-8 | up to 40 |
| Colour | 1 |

After the card comes a one-time key (33 bytes) made for this showing, so a second scan starts fresh keys. That is
about 205 bytes, a small QR code. Both people scan each other. The contact is then marked "met in person", and
the safety pictures already match.

### Link, at a distance

- **Form:** `https://<site>/add#<card in base64url>`, about 270 characters. The card sits after `#`, so it is never
  sent to the website. An Android App Link opens Phonemesh; without the app, the page explains how to get it. The site
  is open question O2.
- **Why https:** WhatsApp and SMS make only `http(s)` links tappable.
- **The person who opens the link** sends a hello over the radio:
  - a marker (1)
  - an ephemeral key (33)
  - their identity key, encrypted to the owner's prekey (33 + 16 tag)
  - their name and colour, encrypted (up to 43 + 16 tag)

  At most 142 bytes: one packet. It names neither person and carries no date; the owner's phone recognises hellos to
  itself by trying to open them (one key agreement per live prekey).
- **The link's owner** hears "<name> wants to talk to you", with the spoken name, and accepts or ignores it.
- **Trust marker:** link contacts show "not met in person" until the two compare safety pictures or scan QR codes.

## Encryption: the Signal pieces, priced for the radio

### Starting a conversation (X3DH)

Signal's X3DH key agreement, with the prekey taken from the card: three ECDH operations give a shared root key. Signal
also uses one-time prekeys. They need a server to hand them out, so the first build leaves them out. Signal does the
same when it runs out of them, and it costs only a little protection for the very first message.

### Starting after a QR scan

When both people scanned each other, either may write first, so neither waits for a hello. Both phones derive the same
root key from three ECDH results between the two identity keys and the two signed prekeys, taken in account-id order.
From the root key, HKDF gives both directions' first chains and header keys at once. The first new ratchet key goes
with whoever sends first.

### Double Ratchet

- **Every message has its own key.** Old keys are deleted, so a phone stolen later cannot open messages recorded earlier.
- **Fresh ECDH keys** on each turn of the conversation let a stolen session heal.
- **The new ratchet key (33 bytes) travels only when the turn changes.** It goes in the first message or receipt after
  the other person spoke. The sender repeats it until the other side's next message shows it arrived. Signal sends the
  key in every message; this rule saves 33 bytes on all other messages.
- **Message counters are not sent.** The recipient learns the counter from the recipient code (below).
- **Late and out-of-order messages:** up to 50 skipped message keys are kept per contact, then dropped. The radio
  already delivers out of order.

### Recipient codes (sealed sender)

Each message starts with an 8-byte code: HMAC-SHA-256 of the sending chain's header key and the message counter,
truncated. The recipient precomputes the next 20 codes for each contact and its own receipts, and recognises a match
at a glance. Codes never repeat, so an outsider cannot link two messages or tell who they are for. Nothing else names
the sender or recipient.

A packet that starts a new chain (it carries a new ratchet key) takes its code from the "next header key", which both
sides already hold. This is how Signal's header-encryption variant of the Double Ratchet works. The recipient therefore
recognises the turn change before it knows the new key.

### Departures from Signal, and why

Each departure saves radio time; each must pass the crypto review in the build plan.

| Signal | Here | Why | What it costs |
|---|---|---|---|
| One-time prekeys from a server | none | no server | the very first message of a link contact is protected only by the signed prekey until the first reply |
| Ratchet key in every message | only on turn changes, repeated until acknowledged | 33 bytes on most packets | none, if the repeat rule holds |
| Encrypted header with counters | 8-byte recipient code, counter implicit | 4–40 bytes per packet | a 1-in-2^64 chance of a false match, caught by the GCM tag |
| Random nonce | implicit nonce (each key used once) | 12 bytes per packet | a key used twice would be fatal, so the code forbids it and tests check it |
| Sender's first chain only, after X3DH | both directions' first chains from the root key (QR start) | lets either person write first | both first chains rest on the same root until the first ratchet step |
| Signed group messages (sender keys) | shared chat protected by the channel key only; names claimed | a signature is 64 bytes per message | anyone with the channel key can post under any name |

### Ports

| Port | Carries |
|---|---|
| 300 | Today's picture format; read, not sent once wire v2 lands |
| 301 | Wire v2: everything private between two people |
| 302 | Hub acknowledgements ("reached the hub") |
| 303 | Shared chat with names (below) |

### Message format (wire v2)

Port 301. Every packet goes to the
broadcast address on the shared chat's channel (see "Addressing"). The layout:

| Bytes | Field |
|---|---|
| 1 | Version (4 bits), key type (2 bits), "ratchet key included" (1 bit), spare (1 bit) |
| 8 | Recipient code |
| 0 or 35 | New ratchet key (33) and the previous chain's length (2) |
| n | Body, AES-256-GCM. The nonce is implicit: every key is used once |
| 16 | GCM tag |

The body begins with one byte for the kind, then:

| Kind | Body |
|---|---|
| Text | language (1–2 bytes) and UTF-8 text |
| Reply | the replied message's reference (4 bytes), then the text |
| Reaction | reference (4) and emoji (1–8) |
| Picture head / part | today's `PictureWire` head and part, unchanged |
| Receipt | the highest counter received |
| Hello | see "Link, at a distance" |

A message's reference is the first 4 bytes of a hash of its recipient code. Both sides know it, so replies and
reactions no longer depend on radio packet ids.

**Cost per packet:** 25 bytes normally, 60 when the ratchet key rides along. Meshtastic's data limit on air is 233
bytes, but a phone hands each packet to its radio in one Bluetooth write, and the radios grant 255-byte writes (252
of data). Wrapped for the radio, a packet carries at most **228 bytes** (T1, 2026-10-14: 233 refused, 228 sent on both
phones). A hub on USB has no such limit, but packets must fit any sender, so wire v2 packets are at most 228 bytes,
against 200 for today's text port. So:

| | Today | Wire v2 |
|---|---|---|
| Longest text | 191 bytes (200 less its `[pm1:fr] ` tag) | 199 bytes with a 2-letter language (25 + 1 kind + 3 language + 199 = 228); 164 when the ratchet key rides along, in which case the key moves to the next packet instead |
| Picture part | 200 bytes of picture | 198 bytes of picture (25 + 1 kind + 4 part header + 198 = 228); a picture needs about 1 % more parts |
| Receipt | none | 30 bytes |
| Radio time, full packet (LongFast) | about 1.95 s | about 1.95 to 2.0 s |

### How the code settles the ratchet (steps 3-6, 2026-10-14)

These follow from the binding contracts in the build plan; the crypto review checks them.

- **Root steps alternate, L first.** A phone steps only when it is its turn and the other phone spoke since it last
  sent. L may step from the start; H waits for L's first step. This keeps both phones' root keys in one sequence
  even when both write at once.
- **Before its first step, a phone's ratchet key is its signed prekey.** So L's first step is DH(new key, H's
  signed prekey). On a link start the owner never needs the opener's prekey.
- **One next header key (NHK) at a time.** Each root step gives the header key of the *other* phone's next new chain.
  The initial NHK is for L's first step. The receiver looks for codes 0-19 under NHK only while waiting for the
  other phone's step.
- **A packet of a new chain without the key** (its body was too long to carry it) is recognised but held until a
  packet with the key arrives. A step that is due waits for a packet short enough to carry the key.
- **The hello** is sealed once, under chain LH's first key, and resent as the same bytes, never sealed again. The
  name and colour in a card or a link are not signed; only the prekey is. The safety picture is the check.
- **Language** is a length-prefixed canonical tag (`fr`, `pt-BR`), as today's texts use.
- **The shared chat's text limit** is what is left of 228 bytes after the header: 192 bytes for a 6-letter name in
  `fr`.

### Shared chat with names

The shared chat moves from Meshtastic's text port to port 303, so it can show the person, not the radio:

| Bytes | Field |
|---|---|
| 1 | Version |
| 8 | Sender's account id |
| 1 + up to 24 | Name, UTF-8 |
| 1–2 | Language |
| up to 228 less the rest | Text, UTF-8 |

The radio's channel key protects it, as today. The name is claimed, not proven: a contact's name and colour show only
when the account id matches a contact. Replies and reactions name a message by the first 4 bytes of a hash of its
sender, time and text.

### Delivery receipts

Two levels:

1. **"Reached their radio or hub":** the receiving radio's or hub's acknowledgement (below).
2. **"Delivered to their phone":** an encrypted receipt of about 26 bytes, about 0.65 s of radio time.
   - It covers every message received so far from that contact.
   - It is sent 20 s after the last message. If the person replies first, the reply serves as the receipt and no
     receipt is sent.
   - It carries the ratchet key when the turn changes, so forward secrecy rides on traffic that already exists.

In a back-and-forth conversation, receipts cost nothing. A one-way burst costs one receipt.

### Phone cost

| Work | Cost |
|---|---|
| One ECDH | expected about 1 ms on these phones; measured in step 1 |
| AES-GCM and HMAC | microseconds |
| Stored per contact | a few hundred bytes of session state, plus up to 50 skipped keys of 32 bytes |

The work runs on the app's existing background thread, never on the screen's thread.

## Addressing and delivery

### Addressing

Every account packet goes to the broadcast address on the shared chat's channel, with hop limit 3, the same as today.

- **No location tracking.** Senders do not need to know which radio the recipient is near.
- **Every radio and hub hears it.** Meshtastic floods direct messages too, so the radio time is the same.
- **No radio acknowledgement.** Meshtastic sends none for broadcasts, so delivery confirmation is the app's job: the
  hub acknowledgement and the receipts above.
- **The channel key wraps it again.** Outsiders on the public key cannot even see the port.
- **Relays must keep rebroadcast mode ALL** (the default). Nodes set to "core ports only" drop our ports, and nodes
  set to "local only" or "known only" drop channels they cannot read (`private-operator-record:accounts-t1.md`, F3). The setup script
  and the hub check the village radios' mode.

### Stage 1: stock radio over Bluetooth

- **One phone at a time.** A stock radio serves one connected phone and hands its 32-packet queue to whoever connects
  next. That phone keeps packets carrying its own codes and drops the rest. Content is never exposed, but packets for
  absent people are lost.
- **The sender retries.** It resends after 2, 10 and 30 minutes until "delivered to their phone" arrives. A packet the
  recipient already has is recognised and dropped.
- **Use:** this works for "we are both here now", and as the fallback when no hub is in reach.
- **Firmware question.** The current app ignores broadcasts on private ports.
  - **Desk check (2026-10-14, firmware 2.7.26):** stock firmware does pass private-port broadcasts to the connected
    phone (`private-operator-record:accounts-t1.md`, finding F1).
  - **Hardware T1 (2026-10-14, both phones, both radios):** confirmed. 15 of 15 full-size broadcasts on port 301
    arrived intact in both directions; a radio with no phone kept packets and handed them over on reconnect at about
    5 a second.
  - **Queue limit (F2, confirmed on hardware):** the radio's to-phone queue holds 32 packets. When it is full, new
    text-port packets push out the oldest, but packets on our ports are dropped. On T1, a phone-less radio kept 31 of
    ours and dropped the next 11 in a row. A radio with no phone connected therefore keeps the first 32 of
    ours and loses the rest. Stage 1 relies on the sender's retries; the hub, always connected over USB, does not hit
    this.

### Stage 2: hub (prototype on the Mac)

- **Radio:** a Heltec on USB, through Meshtastic's serial API. It uses the same protobuf messages as Bluetooth; the
  frames start with `0x94 0xC3`.
- **Mailbox:** the hub stores every port-301 packet it hears for 30 days by default (the hub admin's setting),
  without decrypting anything. At the expected
  village load (about 150 packets an hour, at most 233 bytes each), 30 days is about 25 MB. On a router with little
  storage this needs a USB stick or a router with more flash; it is part of choosing the router.
- **"Reached hub" acknowledgement:** the hub broadcasts a small acknowledgement of about 12 bytes: the packet's
  recipient code and a marker. To keep radio use down, several are sent in one packet.
- **Phones:** on the hub's Wi-Fi, a phone sends packets to the hub and asks for any packets carrying its expected
  codes (a long-poll HTTP API on the local network).
  - The hub learns which codes a phone asks for. It is the village's own machine and is trusted not to log this; the
    design does not depend on that trust for content.
  - The API is transport-independent, so the same calls can later run over Bluetooth to a hub phone.
- **Admin settings:** a small config file on the hub, read at start: mailbox days (default 30), Wi-Fi name, the
  radio's serial port. A screen for it comes with the router or hub phone.
- **Radio fairness:** the hub queues outgoing packets and keeps within the EU 868 duty cycle (10%), sharing radio time
  among phones. Today each phone guesses its own budget.
- **Finding the hub:** the hub's own QR code joins its Wi-Fi.
  - Android 10 and later can join from a Wi-Fi QR code.
  - The app then reaches the hub through Android's network request, `WifiNetworkSpecifier`. This keeps the phone on
    mobile data for the internet, and avoids the "no internet" prompt.
  - How both phones handle this is the first thing to test (test T2), since it decides whether people who cannot read
    the prompts can use a hub.
- **Prototype language:** Kotlin on the JVM, so the hub shares the app's `core` code and the Meshtastic protocol code
  with the phone, and is tested the same way. The router version's language is chosen after the field test
  (decision 16).

### Which way a phone sends

| Situation | Path |
|---|---|
| Hub Wi-Fi in reach | hub |
| Otherwise | the nearest stock radio over Bluetooth, as today |
| Roaming setting on | the app also looks for a hub or radio in the background |

## On the screen

New screens follow the existing rules: at most 8 words per sentence, French and English, no technical words, pictures
first.

- **First launch:** say your name, which is written down and read back. Then pick a colour. No passwords.
- **"Ajouter quelqu'un" (Add someone):**
  - "Montrer mon code" (Show my code)
  - "Scanner un code" (Scan a code)
  - "Envoyer mon lien" (Send my link), which uses Android's share sheet
- **Contacts:** people, not radios. Each shows a colour dot, a spoken name, and "met in person" or "not met in person".
  The safety picture is under the contact's details.
- **Message states:**
  - "En attente d'une radio" (Waiting for a radio)
  - "Envoyé" (Sent)
  - "Arrivé au relais" (Reached the hub)
  - "Reçu" (Delivered to their phone)
- **The guide's deferred step,** "go back, add a person", becomes "add someone with a code".

## What this changes in current rules

| Rule | Change |
|---|---|
| Size ceilings | Raised. Expected growth: QR scanning and drawing (ZXing core, Apache-2.0, the one new library; the camera through Android's own Camera2, since the app's main code uses no AndroidX) a few hundred KB; account code about 150 KB. Measured, not guessed, before the ceilings change. |
| No new permissions | `CAMERA` (QR scanning); `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` (hub); `NEARBY_WIFI_DEVICES` on Android 13+. Roaming in the background, when opted in, uses a foreground service already declared. |
| `docs/design.md` (v1 wire contract) | "No new cryptography in v1" and "one TEXT_MESSAGE_APP per message" no longer hold for private chats or the shared chat; `design.md` gets a pointer to this spec. |
| Radio-addressed private chats | Replaced by person-addressed chats; the old format is read but never sent again. |
| Picture port 300 | Pictures move inside wire v2 (port 301). |
| History schema v5 | v6 adds accounts, contacts, sessions, skipped keys and outgoing retries. |

## Build order

1. **Crypto core** (pure Kotlin in `core`, JVM tests):
   - cards
   - X3DH
   - Double Ratchet
   - recipient codes
   - wire v2

   Test vectors checked against an independent implementation (Python `cryptography`) before any phone work.
2. **Tests T1 and T2, on the hardware:**
   - T1: do private-port broadcasts reach the phone?
   - T2: joining a no-internet Wi-Fi on both phones.

   Both may change the design, so they run before the screens.
3. **Account and contacts on the phone:** first launch, QR show and scan, link share and open, safety picture, schema
   v6.
4. **Person-to-person chats over Bluetooth (stage 1):** text, replies, reactions, pictures, receipts and retries.
5. **Hub prototype on the Mac:** serial radio, mailbox, phone API, Wi-Fi join, airtime sharing.
6. **Field test:** both phones, one radio, the Mac hub. Then pick the router or hub phone.

Each step is gated as now: the full suite, screenshots, and the verification notes.

## Later, not in this build

- Username search or an account list (by the hub)
- Account recovery (required; decision 12)
- Quantum-safe keys at contact setup (shelved; decision 30)
- Signed names in the shared chat
- Several accounts on one phone
- Planned messages sent by the hub (scheduled version c)
- Private group chats (next version; decision 28)
- Any emoji as a reaction, from the phone's emoji keyboard (next version; decision 29)
- One-time prekeys held by the hub

## Open questions for the owner

None blocking. Still open from earlier handovers and not part of this design: the project name, which also decides
the domain.

## Changes after the crypto review (step 7)

An independent review (`private-operator-record:accounts-crypto-review.md`, 2026-10-14) found 4 high, 8 medium, 10 low and 4 info
issues. Fixed in the core, each with a test that runs the attack:

| Finding | Fix |
|---|---|
| F1 a second QR scan reused every key | each QR code carries a one-time key made for that showing |
| F2 a replayed hello rebuilt the owner's state | the owner steps with a fresh key before its first packet; hellos have an id to remember |
| F3 sealing changed state in place | seal and open return the next state; store it with the packet, resend stored bytes, never reseal |
| F5 20 lost packets could silence a direction | flow control: at most 20 packets past the last ack, then resend |
| F7 the ratchet key linked a turn's packets | the 35 key bytes are masked per packet with the chain's header key |
| F8, F10, F18 the hello showed who and needed today's date | the identity key is encrypted; no hello code, no date |
| F11 prekey rotation broke links | phones keep older prekeys; hellos and starts name the one they used |
| F12 receipts were ambiguous | an ack names its chain (2 bytes), highest counter and the 32 below it (10 bytes); any message can carry one |
| F14, F24 kept keys | old and new chains keep their keys fairly; kept keys are dated and can be forgotten |
| F15 bad stored keys crashed later | a stored state with bad keys is refused |
| F20 a global key hook | test-only |
| F21 names hid characters | names must be NFC, no control, format or direction characters |
| F22, F23 | QR starts get forward secrecy from the one-time keys; signatures and secrets bind labels and both identity keys |

Sizes now: receipt 36 bytes; text 199 bytes, or 164 with the ratchet key; an ack riding on a message costs 10 bytes.

Rules for the screens and outbox (steps 9 and later):

- **F6:** keep each session's highest `serial` outside the session bytes and refuse an older state. If a contact's
  packets stop opening after several resends, offer "start again" (a new QR scan or hello), never silently.
- **F9:** the shared chat shows the claimed name uncoloured, never a saved contact's colour or name, until posts are
  signed.
- **F13:** two sessions for one contact: keep the newer QR session; between link sessions, keep the one whose opener
  has the smaller account id.
- **F16:** held packets are not authenticated: at most 10 per contact, dropped after a day.
- **F17:** a hub ack never shows "Reçu". Superseded in part by decision 24 (2026-10-25): it no longer stops the
  sender's scheduled resends to the same hub, because the hub airs each packet once.
- **F19:** receipts wait 15-60 s at random, and ride on a reply when there is one.
- **F25:** the phone-to-hub link uses TLS pinned to the hub key from the hub QR (before step 15).
- **F4, decided (owner, 2026-10-14):** six icons out of 64 over a slow hash, replacing four out of 16. The screens
  need 64 icons people can tell apart and name aloud.

## Hub prototype as built (step 15)

- **Modules:** `mesh-protocol` (Meshtastic protobufs and `MeshtasticProtocol`, plain JVM) is shared by `radio-ble` and
  the new `hub` module. The app's classes are unchanged apart from one file name.
- **Serial:** Meshtastic's stream framing (`0x94 0xC3`, 2-byte length) through `stty` and the device file; no
  serial library. Not yet run against the radio.
- **Mailbox:** ports 301 (messages and hellos) and 303 (shared chat), one append-only file per UTC day, duplicates
  (a relay's copy) stored once, expiry by whole days.
- **API:** HTTPS with the hub's own P-256 key; phones pin SHA-256 of it from the hub QR (review F25). Mail is asked
  for with `POST /mail` and the codes in the body, so code lists never sit in a URL or a log.
- **Radio:** phones' packets go in turn, one sender after another, within the duty cycle, with half a second after
  each; the hub acknowledges what it hears on port 302, 25 codes at once or after 30 s. Hellos and shared-chat posts
  are kept but not acknowledged (they carry no code).
- **Retries (decision 24):** a phone resubmitting port-301 bytes the mailbox already holds asks for one more airing.
  The hub answers "already" (custody unchanged), keeps one durable intent per packet, forwards it at most once a
  minute per packet across all phones and restarts (digest and time only, no sender address), and never later than
  three days after the packet was first stored. The mailbox row, its time and retention never change. Nothing is
  re-aired without a request; shared-chat posts keep single forwarding. The outbox journal is version 2 and still
  reads version 1, whose already-forwarded mail gets one requested retry.

## Safety picture icons (owner approved 2026-10-18; drop and onion replaced the same day)

`SafetyPicture.icons` returns six numbers from 0 to 63; this table says which icon each is. The order is fixed once
the app ships, since every pair's picture depends on it. Drawings: `docs/specs/2026-10-18-safety-icons.py` (24-unit, the app's
icon style), ported to drawables with the contact screens. Left out on purpose: lookalikes of app buttons, weapons,
pork and alcohol, religious symbols, French homophones (ancre/encre), and a crescent moon (looks like the banana).

| # | English | Français |
|---|---|---|
| 0 | fish | poisson |
| 1 | bird | oiseau |
| 2 | chicken | poule |
| 3 | cow | vache |
| 4 | goat | chèvre |
| 5 | elephant | éléphant |
| 6 | turtle | tortue |
| 7 | snail | escargot |
| 8 | butterfly | papillon |
| 9 | bee | abeille |
| 10 | snake | serpent |
| 11 | frog | grenouille |
| 12 | cat | chat |
| 13 | dog | chien |
| 14 | horse | cheval |
| 15 | rabbit | lapin |
| 16 | tree | arbre |
| 17 | palm tree | palmier |
| 18 | leaf | feuille |
| 19 | flower | fleur |
| 20 | mushroom | champignon |
| 21 | mountain | montagne |
| 22 | cloud | nuage |
| 23 | heart | cœur |
| 24 | star | étoile |
| 25 | fire | feu |
| 26 | rainbow | arc-en-ciel |
| 27 | giraffe | girafe |
| 28 | tomato | tomate |
| 29 | banana | banane |
| 30 | pineapple | ananas |
| 31 | carrot | carotte |
| 32 | corn | maïs |
| 33 | egg | œuf |
| 34 | bread | pain |
| 35 | chilli | piment |
| 36 | watermelon | pastèque |
| 37 | peanut | arachide |
| 38 | coconut | coco |
| 39 | teapot | théière |
| 40 | house | maison |
| 41 | key | clé |
| 42 | bell | cloche |
| 43 | drum | tambour |
| 44 | guitar | guitare |
| 45 | hat | chapeau |
| 46 | shoe | chaussure |
| 47 | umbrella | parapluie |
| 48 | cup | tasse |
| 49 | cooking pot | marmite |
| 50 | ball | ballon |
| 51 | boat | bateau |
| 52 | bicycle | vélo |
| 53 | car | voiture |
| 54 | plane | avion |
| 55 | scissors | ciseaux |
| 56 | hammer | marteau |
| 57 | broom | balai |
| 58 | candle | bougie |
| 59 | glasses | lunettes |
| 60 | crown | couronne |
| 61 | chair | chaise |
| 62 | sun | soleil |
| 63 | basket | panier |

## Name and colour as built (step 10)

- **Setup guide, four steps:** voice, then **name**, then radio, then hello. The name needs the voice to hear it, and
  every message in the shared chat will carry it. A phone that finished setup before names existed shows one slim
  "Dites votre nom" card, without a number, until a name is saved.
- **Name screen:** hold the microphone (or tap, then tap again) and say the name; it is written in a large field to
  correct by hand and read back with the app's voice. Without a voice yet, the name is typed. At most 24 UTF-8 bytes,
  so the same name fits a card (40) and a shared-chat post (24); hidden and control characters are refused.
- **Colours:** eight, picked as large circles with a tick: red, orange, yellow, green, blue, purple, pink, brown. The
  card carries the number 0-7; an unknown number from another phone shows grey.
- **Settings:** a "Mon nom" row with the colour dot opens the same screen. Saving the first time makes the account's
  keys; changing the name later keeps them.
- **Moved to step 11, where the QR code exists:** "Montrer mon code" in Settings and the guide's "add someone" step.
- **Picture:** the name step reuses the hello picture (hold the microphone and speak); its own picture goes to the
  how-to review (gate G4).

## Adding someone as built (step 11)

- **One screen, "Ajouter quelqu'un"** (Settings → "Montrer mon code"): this phone's QR code, then "Scanner un code"
  and "Envoyer mon lien" (Android's share list). Requests from people who opened this phone's link show on top with
  ✓ Accepter and ✕ Ignorer.
- **Codes are links.** A QR code holds `https://tranquil-flow.github.io/phonemesh/add#` + the card and the one-time
  key (`AddCode`), so a phone's own camera app also opens Phonemesh. A shown code keeps its one-time key for 10
  minutes; scanning a code before showing your own says "show your code first", since a mutual start needs both.
- **Scanning** (`ScanActivity`): Camera2 frames to ZXing; the camera permission is asked on the tap, with its own
  sentence when refused (send your link instead). A mutual scan adds the person as met in person and sends nothing
  over the air.
- **Links** (`LinkActivity`): an App Link for `/phonemesh/add` (`autoVerify`), or the link page's button handing the
  part after `#` as the extra `card`. Opening one adds the person (not met), sends one hello, and opens the safety
  picture. A link that cannot be read adds nobody and says so.
- **Hellos** arrive on port 301 as requests, once: a replay or a hello from someone already added is dropped. Requests
  live in memory until answered or the app closes (the opener's phone repeats nothing yet; step 13's outbox does).
- **Safety picture** (`SafetyActivity`): six icons computed off the main thread, ✓ "Ce sont les mêmes" (met) and
  ✕ "Pas les mêmes" (a warning; stays or becomes not met). No button reads the names aloud (decision 21).
- **Person chats are listed** with their saved name and colour; link contacts show "Not met yet". After a successful
  scan, a note names the person: a mutual QR scan returns to Chats, while a scanned link opens the safety picture.
  A person chat can never send through the old radio-address path. Contact details remain step 12 work.
- **The guide's "add someone" step** waits for step 12, where the contacts list it points to exists.
- **Tests:** `AccountsFlowTest` (two phones: QR both ways, link → hello → request once → accept, ignore, refused
  scans, met/not met, a hello the radio cannot take), `AddSomeoneScreensTest` (code shown, the drawn QR read back by
  the scanner's decoder, request → accept → safety picture, the two buttons, an App Link, a damaged link, Settings),
  `AddCodeTest` in `core`, and screen checks for the new screens. The camera itself, and refusing its permission, are checked by hand on the phones.

## No-internet Wi-Fi probe, T2 (2026-10-19)

- **Verified direction:** Pixel (Android 16/API 36) joined the Fairphone (Android 10/API 29) hotspot; the Fairphone
  served the local ping. This is not evidence that the Fairphone's app can join a hub as a client.
- The Pixel log records a successful join in 3,079 ms, a local ping in 95 ms, and HTTP 204 over the still-default
  cellular internet connection. A second join took 26,037 ms, with a 43 ms local ping and cellular internet intact.
  Do not promise every join takes three seconds. Receipts: `private-receipt:t2/t2-<pixel-serial>.log` and
  `t2-<fairphone-serial>.log`.
- The phone-test handover reports one Connect tap, a local-only second interface (`wlan1`, absent from Settings),
  and background requests refused; foreground joins are the supported path. It also reports that Data Saver made
  the Fairphone hotspot switch unavailable. These UI/system observations were not re-exercised during resumption.

## Person messaging as built so far (steps 12–13)

- `Postman` seals private text, replies and reactions; `AppMailStore` persists session/outbox updates in History v9.
  References use the code hash, stored above the radio packet-id range. Broadcast ports 301–303 reach the app.
- Incoming content, peer acknowledgement effects, session advancement and exact authenticated-packet replay records
  commit together. Outgoing held bodies are consumed with their replacement ciphertext and reply reference.
- Receipt deadlines survive restart, use randomized 15–60-second quiet periods, and are discharged only into a
  durable outgoing packet. Unsent receipts retain their exact bytes; receipt duplicates never request receipts.
  Replay records are bounded at 2,000, retained through the automatic retry horizon, and new receives fail closed
  when unexpired capacity is exhausted. Old v7 receipts are conservatively reoffered once during migration.
- Stored session format v2 retains skipped-key acknowledgement metadata, including old-chain identity. It still
  reads v1. A v1 skipped key with no provable current-chain metadata cannot be acknowledged safely and is refused;
  migration cannot recreate metadata the previous version never retained.
- Receipts update message status and notify delivery observers. Durable queued messages retain WAITING on restart.
  Manual person-message retry uses stored ciphertext, not resealing; person plans promote atomically into their
  message plus outbox/held work before transmission. Actual Bluetooth acceptance and shared pacing remain unqualified.
- Explicit fresh mutual-QR scans atomically replace a session. A persisted start digest makes repeated scans of
  the same showing idempotent. Old retry packets are marked superseded, held/receipt work is retired, and pending
  bubbles remain as failed rather than being silently sent into the new session. An explicit manual retry can
  reseal a superseded bubble under the new keys. Ordinary state advancement names its exact predecessor, so a
  stale prepared state from the previous session cannot win merely by carrying a higher serial.
- **Not complete:** pictures over v2, persisted incoming requests, reliable hello retries, the distinct Reached hub
  display, and complete Bluetooth write pacing. Old DIRECT chats remain sendable until pictures move to v2.
