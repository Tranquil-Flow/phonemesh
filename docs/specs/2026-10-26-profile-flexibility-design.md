# Profile flexibility (PF)

Draft for the owner's decisions, 2026-10-26. No code before the picks.

## 1. Goal and scope

Let people recognise a private contact by more than one of eight colours. Keep the spoken or written name. Add sixteen fixed colours, a letter avatar and a small, conservative emoji chooser. Help people who cannot distinguish colours, people who recognise pictures more readily than names, and anyone who wants a more personal profile. Do not turn an avatar into proof of identity or make every message larger.

**Recommendation:** ship colours, person/initial/emoji choices, and authenticated profile updates between explicitly compatible phones. Keep legacy adding available and unchanged. Keep the shared chat's names untrusted and its frames unchanged. Defer photos, pronouns, an about line and unrestricted emoji input. The hard part is mixed-version discovery and durable profile updates, not drawing a circle.

Companion: [interactive avatar mockup](2026-10-26-profile-flexibility-design-mockup.html). It compares choices on **Chats**, a private conversation header and **Contact details**, shows the old-phone fallback, and keeps safety pictures separate. The illustrated picture option is deferred, not a real person's photo.

### Evidence and boundaries

- **VERIFIED** means inspected source, an opened primary source, or a calculation executed for this draft. Calculations are not Android or RF tests. **INFERRED** marks engineering judgement. **UNKNOWN** marks evidence still needed. Everything labelled **PROPOSED** is an owner's decision, not shipped behaviour.
- Source root: the repository root. Inspection found branch `v0.1.1`, HEAD `e705adfc2e118c0d0e95a389eb057432021d5f21`, with existing demo/build/spec changes. Citations describe the inspected working copy, not a clean release reconstruction.
- Path shorthand below: `C/` = `android/core/src/main/kotlin/org/phonemesh/core/`; `A/` = `android/app/src/main/java/org/phonemesh/app/`; `H/` = `android/hub/src/main/kotlin/org/phonemesh/hub/`; `R/` = `android/app/src/main/res/`. All resolve below that source root.
- Project outputs are limited to this draft and its requested HTML companion. No source edits, Gradle, emulator, adb, devices, radios, recordings or playback were used.
- Core: recognisable choices, legacy fallback, update delivery, privacy and demo coverage. Adjacent: photographs and optional personal details. No account search, extra accounts, voice imprint, key replacement, new crypto library or cloud service.

## 2. How it works today

| VERIFIED observation | Source |
|---|---|
| **Your name** accepts speech or typing; speech uses the existing recorder and read-back. **Pick your colour** offers eight swatches. **Save** calls `saveAccount`. | `A/NameActivity.kt:23-26, 81-132, 195-212`; `R/values/strings.xml:330-338` |
| UI names are at most 24 UTF-8 bytes. The card and hello decoders allow 40. Names must be NFC, nonblank and exclude controls, format characters, surrogates, unassigned/private-use characters and line/paragraph separators. ZWJ is therefore not allowed in names. | `C/account/Names.kt:10-25`; `C/account/ContactCard.kt:22-23, 36`; `C/wire/Bodies.kt:75-79` |
| `Names.fit` cuts on code-point boundaries, not grapheme-cluster boundaries. Do not reuse it to trim an emoji or derive a complete initial. | `C/account/Names.kt:27-40` |
| The colour is an unsigned byte, 0..255. Only 0..7 have defined UI colours. An unknown colour renders grey, not a crash or a failed card. | `C/account/ContactCard.kt:23, 65`; `A/NameActivity.kt:256-268` |
| The picker uses each colour's `on` foreground, but the chat avatar always uses white for a coloured person icon. This matters for yellow and orange. | `A/NameActivity.kt:157, 253-264`; `A/Looks.kt:32-40` |
| Chats use 56 dp avatars, private headers 40 dp, Contact details 48 dp. Contact details offers the safety comparison and the listener's local voice choice, not a peer identity editor. | `A/ChatsActivity.kt:487`; `A/ConversationActivity.kt:186-198`; `A/ContactActivity.kt:14, 35-58` |
| A card contains identity/prekey/signature/name/colour. Its signature authenticates the prekey, **not** the name or colour. QR adds a 33-byte one-time public key. Link/QR text is the same 46-byte URL prefix plus unpadded base64url. | `C/account/ContactCard.kt:10-12, 26-46`; `C/account/QrCode.kt:17-25`; `C/account/AddCode.kt:20-35`; `C/account/InviteLink.kt:10-22` |
| A hello's body contains kind, name length, name, colour, protected by the session start. Its outer envelope contains an ephemeral point, encrypted identity and two GCM tags in total. | `C/session/Start.kt:27-28, 60-72, 85-103`; `C/wire/Bodies.kt:75-79` |
| SharedPost v2 carries a claimed account id and name, never a colour or avatar. ClaimedSender deliberately does not fetch a matching private contact's trusted presentation. | `C/wire/SharedPost.kt:9-21, 76-78`; `C/wire/ClaimedSender.kt:6-9` |
| Saving your profile only updates local name/colour and notifies local views. New cards, hellos and subsequent shared posts can use the new name. There is no private profile-update body. A displayed QR can remain cached for ten minutes. | `A/AppCore.kt:2143-2147`; `A/AccountStore.kt:130-133`; `A/Accounts.kt:45-59, 111-112, 194`; `C/wire/Bodies.kt:15-33`; `A/AppCore.kt:566-576` |
| Existing contact links return the existing contact without updating its name. Repeated hellos are not a profile channel. QR adding currently also installs a session. | `A/Accounts.kt:68-85, 95-98, 136-150` |
| Safety pictures depend on both identity keys, not profile presentation. The screen shows a name and six pictures, no avatar. | `A/Accounts.kt:173-176`; `A/SafetyActivity.kt:29-35, 84-101` |

**VERIFIED constraints:** `WireV2.MAX_PACKET = 228`; ordinary private headers are 9 or 44 bytes, followed by a 16-byte tag (`C/wire/WireV2.kt:18-22, 48-59`). Everything uses primary channel 0 (`A/AppCore.kt:2396`). Audio never crosses the radio (`docs/design.md:48-55, 74-75`). The 200-byte tagged-text rule applies to the legacy text port; private and shared frames have their own smaller effective text budgets (`docs/design.md:3, 37-55`). Do not interpret 200 as an extra allowance outside a 228-byte frame.

The accounts decisions allow changeable names/colours, retain P-256 and require the demo with releases. Quantum-safe keys remain shelved. Signed shared-chat names, account search and multiple accounts remain later work (`docs/specs/2026-10-14-accounts-design.md:17-25, 37-38, 64-74, 433-443`). The accounts spec's reaction decision mentions 32 bytes, while the inspected account/shared encoders still use 8. This draft does not silently resolve that separate reaction change (`C/wire/Bodies.kt:33`; `C/wire/SharedPost.kt:25`).

## 3. Options compared

**PROPOSED accounting conventions:** `N` is UTF-8 name bytes, normally at most 24; `A` is avatar payload bytes. A derived initial or person icon has `A=0`. A curated emoji has `A=3` or `4`. The proposed enhanced profile adds revision(4), avatar kind(1), avatar byte length(1), payload(A), so `E=6+A`. Existing version and signature fields are replaced, not duplicated. Detailed layouts follow.

Airtime below is **VERIFIED calculation, INFERRED RF cost**, one transmission on one hop, no retransmission, relay, contention or duty-cycle wait. The hub's actual LongFast estimator uses SF11, 250 kHz, 4/5, preamble 16, plus 22 bytes of mesh/protobuf overhead (`H/Airtime.kt:7-22`). It predicts **2.116 seconds for 228 application bytes**, not a reliable one-second promise. `C/LoraAirtime.kt:17-18` includes a different PKI-direct overhead; do not use its picture-plus-ACK helper for these broadcasts.

| Option | Added bytes and packets | Security and privacy | Phone cost / added app bytes | Effort and v0.1.0 behaviour |
|---|---|---|---|---|
| Sixteen fixed colours only | +0 card, QR, hello, SharedPost; +0 packets; +0 airtime | Cosmetic, not identity proof | Tiny table and labels; exact compiled delta UNKNOWN | Small. Old phones accept indices 8..15 and show grey. |
| Receiver derives a letter for every name | +0 on every wire | Reveals no new information, but sender cannot choose how others display it | Grapheme handling and rendering; delta UNKNOWN | Small to medium. Old phones retain person icon. Less helpful to non-readers. |
| Sender chooses person or initial | `E=6`, only enhanced card/QR/hello; same hello packet; at N=24 enhanced hello 132 B | Authenticated private presentation after adding, same trust limits as name | No font assets; delta UNKNOWN | Medium with mixed-version paths. Legacy export strips avatar. |
| Curated emoji, recommended | `E=9..10`; N=24/A=4 hello 136 B vs 126, about +0.082 s, no extra start packet | Choice may reveal interests; not a verification badge | Native glyphs, no bundled emoji font; delta UNKNOWN | Medium, mostly protocol/storage. Old phones need legacy export and see person/colour only. |
| Any keyboard emoji, up to 32 B | `E<=38`; N=24/A=32 hello 164 B, still one packet | More spoofing, display variation and sequence-policy work | Versioned segmentation/emoji rules or dependency; delta UNKNOWN | Larger. One cluster is not one code point; current name/reaction validators cannot implement it. |
| One authenticated update after an edit | N=24/A=4: 62 B plain-header or 97 B keyed, up to 107 B with piggyback ACK; 1 packet per active contact, plus possibly a receipt | End-to-end private, observable timing/length; does not rename strangers | Small row and retry work; delta UNKNOWN | Medium to large. Only send after explicit enhanced capability evidence. |
| Profile attached to next text | Candidate bundle adds `9+N+A` B, 37 B at N=24/A=4; 0 extra packets only when there is room | Same private trust; more parser surface | Bundle, scheduling and overflow logic; delta UNKNOWN | Larger. Full messages cannot absorb it. Never truncate the message to fit a profile. |
| Small photo via QR / dedicated hub-only transfer | Recommended v0.1.1 delta 0 because deferred. Future image has hundreds to thousands of bytes; never in hello/SharedPost | Face and background disclosure; recipients can retain copies | Decode/crop/cache. Existing WebP/ThumbHash avoid a new image library; feature code delta UNKNOWN | Larger, especially hub routing. No silent LoRa fallback. Old phones get person/initial only. |
| ThumbHash as an avatar | Square opaque 24 B, with alpha 25 B, before avatar framing; 1 packet could fit, but not recommended | A blurred colour sketch is not anonymisation or reliable recognition | Existing codec; no new library, feature code delta UNKNOWN | Small codec reuse, poor identifying image. Not a tiny face photograph. |
| Pronouns / about | Hypothetical lengths plus 24/64 B text = up to 90 B more per snapshot | Potentially sensitive; never public or mandatory by default | Input, privacy UI, translations; delta UNKNOWN | Medium. Deferral avoids accidental disclosure and crowded contact details. |

**INFERRED recommendation rationale:** colour-only is the safest first slice but does not solve colour-blind recognition. Initials are cheap but not a substitute for pictures for everyone. A bounded emoji chooser gives a recognisable shape without photo collection, a font download or many radio packets. A separate, infrequent update is simpler to qualify than rewriting every text envelope. Keep the narrow first slice independently releasable if the enhanced compatibility gate fails.

## 4. Recommended presentation

### 4.1 Colours, contrast and non-colour recognition

**PROPOSED:** retain the exact meanings of indices 0..7 and append 8..15. No RGB picker and no reuse of old indices. Unknown indices remain stored unchanged and render neutral grey. Do not reduce modulo the palette size. Name, avatar shape and a visible selection tick accompany colour. None of these is unique.

**VERIFIED external guidance:** normal text uses at least 4.5:1 contrast; large text has a 3:1 exception. Do not round a failing ratio up.[3] Colour cannot be the only way to distinguish information or selected controls.[4] Use 4.5:1 for initials and for the picker tick as a simple house rule; separately check borders/focus markers against light and dark surfaces.

The following ratios are **VERIFIED sRGB calculations**, displayed to three decimals. White is `#FFFFFF`; ink is `#111111`. Proposed foregrounds are chosen with unrounded values. Red needs **black `#000000` (4.968:1)** for small initials: neither existing white nor ink reaches 4.5. Preserve the red background, not its failing small-text foreground.

| Index | Label / RGB | White ratio | Ink ratio | Proposed foreground |
|---|---|---:|---:|---|
| 0 | Red `#E53935` | 4.227 | 4.467 | Black |
| 1 | Orange `#FB8C00` | 2.372 | 7.961 | Ink |
| 2 | Yellow `#FDD835` | 1.395 | 13.533 | Ink |
| 3 | Green `#43A047` | 3.303 | 5.717 | Ink |
| 4 | Blue `#1E88E5` | 3.679 | 5.133 | Ink |
| 5 | Purple `#8E24AA` | 7.040 | 2.682 | White |
| 6 | Pink `#EC407A` | 3.763 | 5.018 | Ink |
| 7 | Brown `#6D4C41` | 7.608 | 2.482 | White |
| 8 | Teal `#00796B` | 5.320 | 3.549 | White |
| 9 | Cyan `#00ACC1` | 2.738 | 6.898 | Ink |
| 10 | Indigo `#3949AB` | 7.735 | 2.441 | White |
| 11 | Lime `#C0CA33` | 1.789 | 10.554 | Ink |
| 12 | Amber `#FFB74D` | 1.731 | 10.910 | Ink |
| 13 | Lavender `#B39DDB` | 2.397 | 7.879 | Ink |
| 14 | Slate `#546E7A` | 5.401 | 3.496 | White |
| 15 | Grey `#9E9E9E` | 2.679 | 7.048 | Ink |

**UNKNOWN:** these sixteen colours have not been validated as pairwise distinguishable with colour-vision deficiencies, in sunlight, or on pilot phones. Do not claim they are. Red/green, blue/purple and yellow/lime are obvious candidates for confusion. Test protan/deutan/tritan simulations and greyscale, then human recognition. More colours are personal choice, not sixteen reliable identity codes. The shipped all-white person icon needs fixing independently of initials (`A/Looks.kt:38-39`).

### 4.2 Avatar types

**PROPOSED kinds:** 0 person, 1 initial derived from name, 2 curated emoji. All include the colour byte. For a colour emoji, use a light neutral inner disc and colour as an outer ring so the emoji does not disappear into a matching background. Show the same glyph/fallback on every private surface.

- **Person:** default on upgrade. `A=0`. Do not silently replace existing users' avatars.
- **Initial:** `A=0`. Derive the first non-whitespace extended grapheme cluster containing a letter or number. Keep its original case and marks. No first-UTF-16-unit slicing, forced ASCII, or locale-sensitive uppercase expansion. If no suitable cluster, it does not fit, or a glyph is absent, use the person icon. The full name remains visible and speakable. Use Android's framework ICU boundary iterator for display only, with JVM fixtures around an adapter. Different platform Unicode versions must not affect identity or wire acceptance.
- **Two initials:** alternative, not first release. Derive from first and last nonempty words, two complete graphemes. No Western surname assumption for a single-word name. User-entered initials would need their own bounded payload, validation and privacy copy; they are not the same zero-byte option.
- **Emoji:** proposed twelve-item chooser: 🐟 fish, 🐦 bird, 🌳 tree, 🌻 flower, ⭐ star, ⚽ ball, 🐱 cat, 🐶 dog, 🐢 turtle, 🍃 leaf, ⛵ boat, ☔ umbrella. Wire values are those exact UTF-8 strings, not picker positions. Each is a single scalar and a single extended grapheme cluster, 3 or 4 bytes. All twelve exact fully-qualified forms are present in Unicode Emoji 5.0's test data, inspected for this draft.[6] This is a compatibility shortlist, not proof that every OEM renders them well.
- **Old Android:** do not bundle EmojiCompat, AndroidX or a font. Check `Paint.hasGlyph` on the actual renderer. It checks the fallback chain and whether a multi-character string has a single corresponding glyph.[2] Hide unsupported chooser entries on the sender; on a recipient, retain the received value but draw person/colour if unsupported. Require API 26 and Android 10 font screenshots before promising the shortlist. A fallback must never show tofu boxes or several separate symbols. The glyph may look different on different phones.
- **Validation:** exact whitelist membership, strict UTF-8, bounded `A`, exact field consumption. This proves one allowed grapheme without shipping all Unicode segmentation data. Do not strip ZWJ or variation selectors into another choice. Reject an unlisted sequence and explain the available choices. Never use `Names.clean`, `Names.fit`, string length, or `Body.MAX_EMOJI` to validate it. Unrestricted keyboard input needs a separate one-EGC plus emoji-sequence policy and versioned tests; UAX #29 defines extended grapheme boundaries, not an emoji allowlist.[1]

### 4.3 Screens and proposed words

Existing labels are from `R/values/strings.xml:19-20, 330-350, 367-372, 437-440`. New labels below are **PROPOSED**, not claimed to exist. Translate and run the existing short-copy checks before approval; wording here is short by design.

| Surface | Design |
|---|---|
| Settings → **My name** → **Your name** | Keep speech, typing, Listen and **Save**. Add **Pick your picture** with Person / Letter / Emoji. Add **More colours** after the original eight rather than making first setup a long wall. Live preview and an explicit **Save**. No network operation on Save. |
| Explanation beside Save | EN: “Newer contacts see changes when you next message.” FR: “Les contacts récents verront vos prochains changements.” An info row explains that older phones keep their saved name until re-added. Avoid suggesting Save reached everyone. |
| **Chats** | 56 dp private avatar plus full readable name. Keep unread and delivery indicators separate from avatar colour. No pronouns/about in the row. Rebind visible rows after profile updates. |
| Private conversation header | 40 dp avatar and contact name. Name/avatar tap opens **Contact details**, with a 48 dp minimum hit area. Rebind avatar as well as title on update, not only in onCreate. |
| **Contact details** | 48 dp avatar, name, existing **Met in person.** or **Not met yet**, unchanged safety and listening-voice controls. Optional status: “Profile received.” / “Profil reçu.” For an old peer: “Their phone keeps your old picture.” / “Son téléphone garde votre ancienne image.” Show update failure here, not a phantom chat bubble. |
| Adding someone | Default **My code** and **Send my link** stay legacy during mixed rollout. New explicit **Share full profile** / “Partager mon profil complet” exports the enhanced form, labelled “For newer phones” / “Pour les téléphones récents”. Both are whole, standalone codes, never two codes stacked in one scan target. |
| Shared chat | Keep claimed names and neutral presentation. No contact-colour lookup, contact emoji, photo, met badge or private about line for an account-id match. A neutral derived initial may be considered later, but not a trusted avatar. |
| Safety picture screen | Keep **Same six pictures on both phones?**, **They are the same**, **Not the same**, and exactly six key-derived pictures. Do not add the profile avatar to this screen. Add a short explanation if needed: “Compare these six, not their profile picture.” No name-playback button, preserving accounts decision 21. |

TalkBack names each option and its selected state. A tick and outline show selection without colour. Descriptions say “Ama, fish picture” where useful, not “verified fish”. Never automatically speak an emoji's Unicode name in a message. The HTML shows interaction/layout intent only, not qualified Android accessibility or translated production UI.

## 5. Wire contract and exact byte ledger

All changes in this section are **PROPOSED**, conditional on PF1/PF4/PF5. Do not implement before the owner picks. Integers are unsigned, big-endian unless a codec explicitly says otherwise. No trailing bytes, unknown avatar kinds or malformed UTF-8 accepted. New body-kind values 8 and 9 are provisional allocations: coordinate with the other v0.1.1 specifications before coding; do not collide with group/reaction work.

### 5.1 Legacy bytes remain legacy

**VERIFIED derived sizes:**

```text
ContactCard v1:
  version 1 | keyType 1 | identity 33 | prekey 33 | signature 64 |
  nameLength 1 | name N | colour 1                       = 134 + N
QrCode v1: card + oneTimePoint 33                       = 167 + N
Hello v1:
  marker 1 | ephemeral 33 | sealedIdentity (33+16) |
  sealedBody (kind 1 | nameLength 1 | name N | colour 1) + tag 16
                                                        = 102 + N
```

At `N=24`: card 158 B, QR binary 191 B, hello 126 B. At the existing decoder ceiling `N=40`: 174, 207, 142 B. New colours alone add **zero bytes**, including on old phones. `ContactCard.decode`, `QrCode.decode` and the hello body reader require exact lengths, so appending an emoji to a v1 payload is **not** backward compatible (`C/account/ContactCard.kt:56-68`; `C/account/QrCode.kt:20-25`; `C/wire/Bodies.kt:95-99, 149`). Adding `&avatar=` after the URL fragment also fails the legacy base64 alphabet check (`C/account/AddCode.kt:24-31`).

### 5.2 Enhanced ContactCard v2 and QR

Preserve offsets through the signature and name. Version 2 means the enhanced profile contract and ability to receive ProfileUpdate. It is not a new WireV2 packet version or a new key type.

```text
version=2        1
keyType=1        1
identity        33
prekey          33
signature       64
nameLength       1
name             N   (decode 1..40; new UI emits 1..24)
colour           1   (0..255, current picker emits 0..15)
profileRevision  4   (1..0xffffffff)
avatarKind       1   (0 person, 1 initial, 2 curated emoji)
avatarLength     1   (0 for kind 0/1; 3 or 4 for kind 2)
avatar           A
TOTAL                140 + N + A
```

**Every added byte:** revision +4, avatar kind +1, avatar length +1, avatar payload +A. Existing version and signature still consume 1 and 64 bytes. Delta against v1 is `6+A`: +6 person/initial, +9 or +10 emoji. No hidden capability byte, new public key, hash, nonce or extra signature.

The v2 signature is the existing raw 64-byte P-256 signature, now signing:

```text
ASCII("Phonemesh card v2") || encodedCard[0..67] || encodedCard[132..end]
```

Ranges are inclusive. This signs both keys, version, key type and all profile fields, omitting exactly the signature itself. It replaces the v1 prekey-only signing transcript; retain v1 verification separately. One signature binds the presentation to the key, not to a civil identity. Use existing JCA/P-256; get a focused crypto/protocol review of this transcript before integration.

QR remains `card || oneTimePoint(33)`, now `173+N+A`. Its one-time point and start rules are not changed or silently reused. `lengthAt` must dispatch by version, bound reads before indexing and derive v2 length using both length fields. Do not hard-code v1's `134+N` when decoding v2.

| Export at N=24 | Card binary | QR binary | Full link text | Full QR text | Delta card / QR vs v1 |
|---|---:|---:|---:|---:|---:|
| Legacy | 158 | 191 | 257 | 301 | 0 / 0 |
| Enhanced person or initial | 164 | 197 | 265 | 309 | +6 / +6 |
| Enhanced 4-byte emoji | 168 | 201 | 270 | 314 | +10 / +10 |
| Alternative arbitrary emoji A=32 | 196 | 229 | 308 | 352 | +38 / +38 |

**VERIFIED calculation:** full ASCII text size is `46 + ceil(4*binaryBytes/3)` with unpadded base64url. QR optical capacity is not the 228-byte radio limit. The 40-byte-name/4-byte-emoji decoder maximum is card 184, QR 217, full link 292 and full QR text 336 bytes. Do not claim a QR version or scan distance before choosing error correction and measuring the rendered code.

### 5.3 Enhanced hello, still one packet

Keep the outer hello marker and cryptographic envelope unchanged, so the hub still classifies it without decrypting. Introduce **HelloProfile kind 8**:

```text
kind=8 1 | nameLength 1 | name N | colour 1 |
profileRevision 4 | avatarKind 1 | avatarLength 1 | avatar A
body = 9 + N + A
whole hello = 83 outer bytes + body + 16 body tag = 108 + N + A
```

Delta versus legacy hello is exactly `6+A`. At N=24/A=4 it is **136 B**, leaving 92 B of the 228-byte ceiling. At N=40/A=4 it is **152 B**. Even the deferred A=32 choice would be 164 B at N=24 or 180 B at N=40. Hellos never carry an ACK. No fragmentation.

Send kind 8 only after validating a v2 owner card. The recipient's decrypted kind 8 proves the opener implements this contract, bound to that session identity. If the owner card is v1, emit the byte-for-byte legacy kind 7 body, even when this phone has an emoji. New decoders accept both kinds. Old decoders reject kind 8; they do not gracefully ignore its extension (`C/wire/Bodies.kt:83-101`).

Add an explicit `<= WireV2.MAX_PACKET` check at the enhanced hello encoder boundary. The inspected `Start.link` builds bytes directly rather than using ordinary ratchet packet-size enforcement (`C/session/Start.kt:60-72`), so a future optional field must not bypass the ceiling.

### 5.4 ProfileUpdate on an established private session

**Proposed body kind 9:**

```text
kind=9 1 | profileRevision 4 | nameLength 1 | name N |
colour 1 | avatarKind 1 | avatarLength 1 | avatar A
body U = 9 + N + A
packet = (9 or 44 header) + U + 16 GCM tag [+ 10 optional ACK]
       = 34 + N + A, or 69 + N + A [+10]
```

At N=24/A=4: **37 B body; 62 B normal, 97 B keyed; 72/107 B with ACK**. At N=40/A=4: **53 B body; 78/113 B; 88/123 B with ACK**. All fit even when a fresh ratchet step is mandatory. No detached signature is added: the established session authenticates the peer.

| Event at N=24/A=4 | Extra packets / airtime |
|---|---|
| Save locally, or send an ordinary message with unchanged profile | 0 packets; 0 B added; 0 s |
| Enhanced link hello versus legacy | 0 extra packets; +10 B in the one hello; +0.082 s estimated |
| One changed-profile update | 1 packet, 62..107 B depending on ratchet/ACK; 0.887..1.214 s estimated |
| Separate receipt if it cannot ride on a reply | Up to 1 further packet for this event: 36 B normal or 71 B keyed, 0.682 or 0.928 s. Receipt batching can cover both text and update. No receipt-of-receipt. |
| QR-only profile refresh | 0 radio packets and 0 radio airtime |
| K eligible active contacts after one edit | Up to K first-send updates, not one broadcast; costs multiply by K, then by actual retries/relays |

These are first-delivery figures, not total retry bounds. Existing Postman non-receipt retries and duty-cycle accounting remain in force. Do not mark an update delivered on radio acceptance or hub custody.

**Piggyback alternative, not recommended first:** a new bundle kind plus profile-without-kind plus the complete old body adds `9+N+A` B to the old body, 37 B in the example. A normal `fr` text's no-ACK allowance falls from 199 to 162 B, or from 164 to 127 B with a ratchet key. An ACK costs ten more. The scheduler must defer the profile or send it separately when full, never shorten the user's message. Legacy decoders cannot decode this new bundle either. Avoid an always-present version/revision byte on every message merely to detect occasional changes.

### 5.5 SharedPost frames: zero change

Recommended delta: **0 bytes, 0 new packets, 0 new airtime** for colours, initials, emoji, pictures, pronouns and about. Keep SharedPost v2 as shipped.

**VERIFIED layout arithmetic:** version(1), kind(1), sender id(8), seconds(4), name length(1), name(N), language length(1), language(L), optional reference(4), text(T).

- Text: `16+N+L+T`.
- Reply: `20+N+L+T`.
- Reaction: `20+N+T`, language length is zero; currently `T<=8`.
- At N=24/L=2, text room is 186 B and reply room 182 B. A name edit changes these budgets by its byte-length delta, which existing sizing must re-evaluate. Historical claims stay historical.

If later approved, adding colour(1), avatar kind(1), avatar length(1), avatar(A) requires **3+A more bytes** per shared frame and a new SharedPost version. A 4-byte emoji costs 7 B, reducing those rooms to 179/175 B. Adding a revision costs a further 4 B. Signing each claim costs a further 64 B. A custom initials payload costs its actual UTF-8 bytes, not its displayed character count. Old phones and old hubs reject unknown SharedPost versions (`C/wire/SharedPost.kt:123-126`; `H/Mailbox.kt:45-49`). Appending bytes to v2 instead would become part of the text, not an ignored extension. None of this is part of PF's recommendation.

## 6. Learning later changes, persistence and migration

### 6.1 Capability is explicit, not guessed

**PROPOSED:** maintain a per-contact `profile_capable` fact, established only by a validated v2 card or authenticated HelloProfile. Colour 8..255, a claimed account id, an app-looking shared name, a hub version, or a delivery receipt does not prove support.

- New/new mutual enhanced QR: both learn capability from the other's v2 card.
- New opener of a v2 link: opener knows owner capability; owner learns opener capability from kind 8.
- New opener of a v1 link: legacy hello; neither direction infers enhanced support. Use an explicit enhanced exchange later.
- Existing contacts upgrading in place: no probing packets or broadcast version announcements. **Share full profile** exchange enables updates. Until then they remain legacy-compatible, even if both apps happen to be new. This is a real usability cost and the reason for PF4.
- Provide a local **Use older profile format** fallback for a peer who downgraded. Stop creating enhanced updates for it. Never automatically replace a session after an unsupported packet.

**VERIFIED hazard:** current Postman rejects unknown decoded body kinds without committing the receive state or acknowledging them (`C/mail/Postman.kt:111-125`). Repeatedly sending new kinds to an old peer can consume the sender's outstanding window. “Old phones ignore it” is not a safe compatibility strategy.

### 6.2 Revision and send policy

**PROPOSED:** one durable unsigned 32-bit revision per identity. New enhanced profiles start at 1; migrated legacy peer rows have revision unknown, not a fake signed zero. Saving a changed name, colour or avatar increments the revision in the same transaction as its fields. Saving identical values does not. No timestamp ordering and no wraparound. At exhaustion, refuse another revision and request an app-level migration, not a new identity.

On Save, update local previews and mark eligible contacts dirty; **do not fan out immediately**. The next explicitly sent private message to that contact schedules one current profile update behind that message, subject to a one-hour minimum between newly created updates for that contact. Coalesce unsealed edits to the latest snapshot. This costs one extra small packet on an edited-profile event, not on every message. A contact never messaged again may never learn the change; say so.

Keep at most one sealed profile update in flight per contact. Its bytes, revision and receipt association persist. Resend exactly those bytes through the existing Postman path; never rewrite ciphertext after another edit. When it is acknowledged, queue the newer dirty revision only on eligible user activity. A failed old update must not suppress normal text or make a newly saved name look delivered. Retain a retry action in Contact details after the normal bounded retry policy expires. A throttle prevents edit floods but does not delay receipt/retry maintenance for already sealed work.

On receive, authenticate the session, strictly decode, then atomically commit receive state, exact packet digest, receipt obligation and profile state. Higher revision applies; lower revision is acknowledged but does not overwrite. Equal revision plus identical fields is idempotent. Equal revision plus different fields is a conflict: consume/ack the authenticated packet to avoid retry loops, keep the existing profile and show a bounded details warning. Do not log its contents. Do not turn profile updates into unread messages, sounds or automatic TTS.

Record a local name-change notice with old and new name, no extra radio bytes. Rebind Chats, the open private header and Contact details without requiring activity restart. Keep the same account id, keys, sessions, met flag, chosen listening voice, last ACK, retry queues and message history. An avatar edit never marks a contact met.

### 6.3 Cards and storage

**PROPOSED storage additions:** own profile revision, avatar kind and payload; peer revision, kind/payload, capability provenance, last acknowledged local revision, last update-created time, pending/in-flight linkage. Keep exact original cards separately from the current mutable display snapshot. A peer added by hello currently has only its identity in `contacts.card`, not a full card (`A/Accounts.kt:197-198`); a profile update must not assume a signed prekey/card exists.

Use one coordinated History migration above the current source version 15 (`A/History.kt:800-801`), agreed with sibling v0.1.1 work. Do not hard-code a competing version 16 in two features. Default old rows to person and existing colour/name, unknown capability and no pending sends. Retain unknown colour bytes. Reuse/update rows in place; preserve `last_ack` and related state. `History.ContactRow` does not expose all transport columns (`A/History.kt:191-198, 244-276`).

Invalidate the displayed QR snapshot on a profile revision change. If a new showing is generated, retire the old one-time key and its showing-start map as the existing anti-reuse rules require. Do not change metadata inside an already shown session transcript while leaving its key lifetime ambiguous. A link already copied elsewhere remains an old signed snapshot, not a live profile URL.

For an existing same-identity contact, importing a newer enhanced card updates profile/capability only after signature/revision checks. It must not call the current QR session-install branch or reset a healthy ratchet. Use a distinct profile-refresh branch. The existing **Start again** flow remains explicit. Legacy v1 metadata must never silently downgrade a newer authenticated profile. For a legacy-only contact, a deliberate re-add/refresh can update its name, but do not invent a remote update path that old code lacks.

Account recovery/backup is a sibling design: preserve revisions and acknowledged state in any approved restore. A stale restored revision can lose to contacts' newer snapshots. PF does not assume simultaneous writers for one identity or solve recovery by resetting the revision to 1. This is a release integration gate with the recovery owner.

### 6.4 Compatibility matrix

| Sender/export | Receiver | Result |
|---|---|---|
| Legacy card/QR/hello with colour 0..7 | v0.1.0 or v0.1.1 | Existing name and colour; no transmitted avatar |
| Legacy card/QR/hello with colour 8..15 | v0.1.0 | Accepted, grey person avatar; exact colour byte remains in stored metadata |
| Enhanced card/QR | v0.1.0 | Refused, not partly accepted. Sender chooses the separate legacy export. |
| Enhanced card/QR/hello | v0.1.1 with this feature | Profile plus capability; byte bounds and signatures checked |
| ProfileUpdate | Known compatible peer | In-place authenticated refresh; ordinary receipts/retries |
| ProfileUpdate | Unknown/legacy peer | Must never be emitted; no capability probe |
| Any normal private text or SharedPost | Mixed fleet | Same wire bytes as before; profile fields do not reduce ordinary text capacity |
| New hub with old phone, or old hub with new phone | Either | Compact private profile packets remain opaque and <=228 B; no hub version requirement |

Do not add a second radio hello as a fallback. For adding, legacy mode is available before transmission. For a mistaken enhanced link, the sender must send the legacy link explicitly; there is no transparent downgrade hidden inside the original fragment.

## 7. Optional photos and personal details, priced but deferred

### 7.1 A small picture is not a fixed byte count

**VERIFIED calculations and measurements, not a photo-quality study:** 48×48 RGBA is 9,216 raw bytes; 64×64 is 16,384. WebP is compressed and content-dependent. A desktop Pillow 11.3.0/libwebp 1.5.0 probe encoded generated fixtures, RGB, method 6, no metadata. These are real encoder outputs for synthetic fixtures, **not** camera portraits or Android encoder results.

| Synthetic fixture | 48×48 q60 | 48×48 q80 | 64×64 q60 | 64×64 q80 |
|---|---:|---:|---:|---:|
| Flat teal | 82 B | 78 B | 90 B | 82 B |
| Drawn two-colour symbol | 318 B | 392 B | 366 B | 446 B |
| Seeded RGB noise | 1,524 B | 1,866 B | 2,570 B | 3,150 B |

The lowest-quality setting is not guaranteed to be the smallest for every tiny image. Choose a maximum encoded length and verify the final bytes. If photos are later approved, start with opaque 48×48, at most 512 B, user-approved crop; compare against 64×64 at most 1,024 B on consented portraits before choosing. If the budget cannot be met, ask for another crop or use the non-photo avatar. Never silently send a recognisably different blur.

**VERIFIED code-derived ThumbHash sizes:** for a square opaque input, the existing coefficient counts give `5 + ceil((27+5+5)/2) = 24 B`; with alpha, `6 + ceil((14+5+5+14)/2) = 25 B`. Both 48×48 and 64×64 have these same output lengths because aspect/alpha determine the coefficients. This is a calculation from `C/ThumbHash.kt:39-42, 58-74, 177-183`, not execution of Kotlin. Its decoder produces at most 32 px per side (`C/ThumbHash.kt:86, 114-117`). The old picture spec's 21-byte sketch example is not the square-avatar size (`docs/specs/2026-10-12-picture-messages-design.md:35-38`). A ThumbHash cannot reconstruct facial detail; do not present it as a compressed portrait or a security picture.

**Exact hypothetical field costs, not accepted v2 bytes:** extending the proposed profile with a picture kind requires a two-byte length for W>255, so replace the one-byte avatar length. Delta from v1 is `7+W` instead of `6+A`.

| Hypothetical embedding at N=24 | Card | QR binary | Hello | Shared frame addition (colour + kind + u16 length + W) |
|---|---:|---:|---:|---:|
| 512 B WebP | 677 B | 710 B | 645 B, impossible | 516 B, impossible |
| 1,024 B WebP | 1,189 B | 1,222 B | 1,157 B, impossible | 1,028 B, impossible |
| 24 B opaque ThumbHash, retaining u8 length | 188 B | 221 B | 156 B, fits but not recommended | 27 B |
| 25 B alpha ThumbHash, retaining u8 length | 189 B | 222 B | 157 B, fits but not recommended | 28 B |

Do not put a photo in ContactCard, QrCode's ordinary add payload, hello or SharedPost. In the recommended release every photo delta there is **zero**. A future dedicated **picture QR** can be opt-in after contacts are established, without replacing the small add-me QR.

For a concrete future QR cost, an unencrypted, signed in-person object could be:

```text
magic "PFA1" 4 | version 1 | accountId 8 | revision 4 |
codec 1 | byteLength 2 | image W | raw P-256 signature 64
= 84 + W bytes (596 B for W=512; 1,108 B for W=1,024)
```

The receiver would use the already saved full identity key, not trust the eight-byte account id alone, verify a domain-separated signature over the whole object except signature, and enforce dimensions/length/revision. This is a **deferred sketch**, not an approved codec. A binary QR avoids base64 growth; a URI QR adds its prefix plus `ceil(4*(84+W)/3)` characters. Measure scanning on old cameras with quiet zone and error correction. QR sharing is visible to anyone able to scan or photograph the code, even though it uses no radio.

### 7.2 Hub picture sharing must really be hub-only

**VERIFIED:** today's `Hub.fromPhone` stores and queues eligible private packets for the radio; Mailbox rejects application packets above 228 B (`H/Hub.kt:56-68`; `H/Mailbox.kt:45-49`). Existing private picture messages intentionally support multiple radio packets. Merely sending an avatar through the current hub endpoint is **not** a no-LoRa guarantee.

**PROPOSED deferred boundary:** a separate, authenticated local-transfer API and storage class, outside `Outbox`, radio mail and fallback. E2E-encrypt each recipient's picture using a reviewed attachment-key design; pinned TLS alone still lets the hub read plaintext. No public avatar URL, global lookup by account id or background fetch on shared claims. Separate body-size cap, expiry, quota, digest validation, recipient capability and deletion policy. Both phones need access to a hub holding the object, or a later explicit in-person transfer. No hub available means “Picture waits for a hub”, with the initial/person fallback, not radio fragmentation.

**UNKNOWN:** complete encrypted blob/control-envelope bytes, key distribution and HTTP/GATT framing are not specified or priced as implementation-ready here. Approving hub pictures requires an attachment protocol appendix and a deny-LoRa end-to-end test before code. The exact image and signed-QR object costs above are not passed off as encrypted hub-transfer totals. Current recommendation adds no such endpoint or packets.

Even an optimistic picture-only split of 512 B into 198-byte chunks needs at least three part packets; 1,024 B needs at least six, before heads, ratchet keys, receipts, retries or relays. That is already several seconds per contact. This is why the proposed policy is **no profile-image or ThumbHash bytes over LoRa**, even though a small hash alone would fit. It does not prohibit the existing explicit picture-message feature.

For any future photo implementation: explicit opt-in, system picker/camera flow, square crop preview, decode size bounds before allocation, one still image, re-encode without EXIF/XMP/ICC metadata or animation, bounded cache and cancellable worker. WebP containers can carry metadata, colour profiles and animation; changing the filename does not remove them.[5] Do not retain the source image after export. Consent to sharing with a contact is not consent to publish in the shared chat. Deletion cannot erase a recipient's copy.

### 7.3 Pronouns and an about line

**PROPOSED deferral:** neither field is collected in v0.1.1. No empty placeholders, required pronoun choices or inferred gender. Neither field determines TTS voice; that stays the listener's local choice.

If later chosen, use separate optional fields, pronouns at most 24 UTF-8 bytes and about at most 64, NFC, strict UTF-8, no control/bidi manipulation or links rendered as active content. Names.valid is not a substitute for separate field requirements. Keep them in Contact details only, hidden in notifications, shared posts and default invitation exports. Default audience is no one until explicitly shared with chosen contacts. Editing or clearing may reveal a change, and old copies remain.

**Hypothetical exact cost:** with one length byte per field, `2+P+B`, up to **90 B**. That adds 90 B to each enhanced card, QR binary or profile snapshot that carries both. At N=24/A=4, the proposed hello would rise from 136 to 226 B; at N=40/A=4 it would be 242 B and fail the cap. With A=32 and N=24 it would be 254 B. A keyed update with ACK at N=24/A=4 would rise from 107 to 197 B. Shared frames would lose another 90 B of text capacity if exposed there. These are reasons to keep details out of hellos and the shared chat, not permission to use the last two bytes of a typical hello. Any future optional-field mask/version must also be counted.

## 8. Hub changes, threat model and failures

### Hub

**PROPOSED recommended path:** no hub API, database, encryption or routing change. Old Mailbox accepts the unchanged hello outer shape and WireV2 outer header while treating bodies as opaque (`H/Mailbox.kt:44-49`). Compact updates use the same encrypted mailbox/retry path, on primary channel 0. Prove this against the old hub implementation in JVM interoperability tests. Do not make compatibility depend on a new hub merely to draw an emoji.

### Threat model

| Actor | Can learn or do | Cannot legitimately do / remaining limits |
|---|---|---|
| Outsider listening to radio | Observe transmitting radios, timing, length and traffic bursts. Read shared posts if it has the channel key, including public/default-key channels. | Cannot decrypt private profile updates without endpoint/session secrets. Profile size is not hidden. Same-looking names/avatars can be copied. |
| Local hub | See requests, packet sizes/timing and route metadata; read shared-channel content; drop, delay or replay packets. | Cannot read recommended private profile bodies. Ratchet authentication and revision rules stop an old replay overwriting a newer profile. The hub can still deny service. |
| Someone with an unlocked/lost phone | Read locally stored names, avatar choices and history; potentially send from that account while usable keys remain. | PF adds no remote lock, revocation or recovery guarantee. Do not claim profile data has new at-rest encryption. |
| A contact | Read and copy the profile you share; keep screenshots/old names, impersonate the appearance in another account, choose misleading own fields. | Cannot update another identity's authenticated private profile merely by using its account id in SharedPost. A name, emoji or picture is not evidence of the person's real identity. |
| Anyone holding a link/QR | Read the exported name, colour and, in enhanced form, avatar and revision. Forward it. | A fragment avoids ordinary HTTP transmission to the site, but holders, screenshots, clipboard and page code can see it. Enhanced signing does not make the card secret. |

Keep local voice preferences, safety flags and identity keys out of profile exports. Use existing P-256 only. Do not reuse the six safety icons as a selectable “verification badge”. Do not change the safety picture after an ordinary profile edit.

### Required failure behaviour

- Bad UTF-8, oversized names, invalid kind/payload combination, unexpected trailing bytes or bad signature: refuse the enhanced object without partial profile/session mutation; actionable message beside the scan/save action.
- Unknown colour: retain byte and show neutral fallback; no invalid palette indexing.
- Unsupported glyph: show person/colour, keep original metadata; do not fetch a font or render multiple fragments.
- No radio or blocked region: Save succeeds locally; pending update stays durable. No false “sent to everyone”.
- Reordered older update: acknowledge its authenticated delivery, keep newest fields. Duplicate: no second notice or unread count.
- Storage failure: roll back profile and receive/receipt changes together. The visible header must not get ahead of durable state.
- App restart: retain the newest local revision, capability evidence, exact in-flight ciphertext, dirty marker and retry deadline. Resume through the existing single-flight queue.
- Enhanced card on an old phone: choose the legacy code/link. Never silently send a second hello or downgrade a current signed profile.
- Profile edit while the old QR is displayed: refresh or close the showing, retire its key lifetime correctly, and tell the person to scan the new code.
- Deleting a photo or optional detail in a later feature: local/remote best effort only; no promise that another person forgot it.

## 9. Verification and implementation slices

This draft is not permission to run the following commands now. **NOT RUN:** Kotlin/JVM product tests, Gradle, emulator, device fonts/camera, RF, APK measurement and demo app. The desktop byte/contrast/image calculations and the HTML inspection are separate evidence.

### JVM-first acceptance

1. Characterise v0.1.0 golden card/QR/hello and SharedPost bytes before changing encoders. For palette-only export all bytes remain identical except the deliberately selected colour value.
2. New v2 encode/decode tests for N=1/24/40, every colour 0..255, every curated emoji, both zero-payload modes, all truncation points, overlong lengths, bad signatures, wrong signing domain/version/key type, malformed UTF-8 and extra bytes. Flip each signed metadata byte. Exact size assertions from section 5, including full URL/base64 lengths.
3. Hello kind 7/8 round trips through real Start and pending requests. Max enhanced input fits 228 and the real Meshtastic ToRadio Bluetooth write envelope. Legacy decoder rejects kind 8. New code never emits it for v1 capability.
4. Old/new endpoint matrix and old hub Mailbox classify/serve tests. Unknown body must not be used as discovery. Demonstrate enhanced QR same-identity refresh does not reinstall or rewind sessions.
5. ProfileUpdate through real Postman/MailStore, not only direct decoder calls: loss, duplicate, reorder, stale/equal/conflicting revision, ratchet turn, max outstanding window, ACK piggyback, quiet receipt, failed custody, retry expiry and restart. Full normal text must retain its original capacity. No profile on SharedPost.
6. History migration fresh/upgrade and late SQL-failure rollback: preserve met, voice, original card/identity, `last_ack`, session/outbox/held rows. Separate old-format fixtures from merely lowering a new database's version number. Coordinate schema with recovery/backup and demo migrations.
7. One pending snapshot per contact, coalesced offline edits, one sealed immutable update, one-hour creation throttle, no immediate all-contact fanout, no retry/receipt starvation. Authenticated stale updates still advance receipt state without changing the profile.
8. Emoji whitelist has exactly twelve distinct strings, each one EGC under a pinned test reference and 3/4 bytes. Multi-emoji, ZWJ family, modifiers, flags, keycaps, lone selectors, tags, controls and oversized inputs are rejected unchanged. Initial tests include combining marks, Ewe/Kabiyè name forms, surrogate pairs, non-Latin scripts, whitespace-only candidates and no suitable letter. Do not strip accents to fit.
9. Contrast tests use exact colour constants and unrounded thresholds. Every selectable control has selected state and a text/shape cue. Unrecognised indices never crash.

When separately authorised, use the checkout's current build environment and run the relevant core/mail/account tests first, then `:app:compileDebugAndroidTestKotlin` and `:app:testDemoUnitTest --tests 'org.phonemesh.app.demo.*'`. Exact new test class names belong to implementation, not this research receipt.

### Emulator and demo acceptance, later

- API 26 minimum, Android 10 baseline and a current Android image: every emoji rendered or intentionally unavailable/fallback; no Internet/font download. Desktop browser emoji does not qualify Android fonts.
- Light/dark, large fonts, narrow screens, rotation, process restart, no voice pack, keyboard-only input, permission-denied speech, scroll reachability, TalkBack descriptions and selected state. No live microphone/TTS test without separate consent.
- Chats, private header and Contact details refresh while already open. Avatar views are currently built in onCreate, so title-only re-render tests are insufficient (`A/ConversationActivity.kt:186-198, 591-595`; `A/ContactActivity.kt:36, 75-82`). Preserve old-name notice and safety state across restart.
- Shared-chat spoof fixture claims a real private contact's id and name. It must still show no trusted profile, photo, colour or met marker. Safety screenshot still contains exactly six key-derived pictures and no avatar.
- Demo uses real core encoding and real Postman, not a UI-only profile setter. Kofi demonstrates enhanced QR and a later emoji/name change; Ama demonstrates a legacy hello and grey fallback for a new colour; Yaw demonstrates enhanced-link start and a delayed update/retry. Add controls for unsupported glyph, duplicate/reordered profile and pending Save. Keep demo changes in `src/demo` and `src/testDemo`; feature code is ordinary production code, with no main-code reference to demo classes.
- Persist demo people's revisions and capabilities. Reset only demo state. Update demo schema alongside app schema. Include English/French review and synthetic avatars only. The demo remains no-hardware, not evidence about font behaviour on a different handset or RF delivery (`docs/specs/2026-10-25-demo-build-design.md:14-40, 42-46`).

### Slices and effort

**INFERRED relative effort, not an elapsed-time promise:** the critical path is capability/migration and transactional updates. No measured task-duration baseline was collected, so day estimates would be speculative.

| Slice | Likely files / scope | Acceptance / dependency |
|---|---|---|
| 1. Palette and renderer | `NameActivity`, `Looks`, strings, private avatar call sites | Sixteen stable indices, foreground ratios, unknown-colour fallback, no wire change. Independently releasable. Small. |
| 2. Avatar model and enhanced codecs | core account/Start/Bodies, `AccountStore`, tests | Exact byte/signature vectors, old/new parsing, emoji/initial policy. Depends on PF1/3/4 and body-kind allocation. Medium. |
| 3. Save, exchange and migration | `History`, `Accounts`, `AppCore`, adding screens | Atomic revision save, legacy/full export choice, safe same-identity refresh, capability evidence. Medium. |
| 4. Update lifecycle | Postman/MailStore/AppMailStore plus private view rebinding | Real loss/reorder/rollback/restart gates, unchanged text budget, no legacy probes or broadcast fanout. Largest slice. |
| 5. Demo, accessibility and release | demo fixtures, instrumentation/screenshots, copy, size gates | Real demo flow, phone-font qualification, signed size measurement. Medium; all earlier acceptance gates required. |
| Deferred | Photo transfers, freeform emoji, pronouns/about | Separate owner picks and missing security/transport appendix; must not delay the core. |

**APK size gate:** existing limits are **16,575,000 B arm64-v8a** and **14,715,000 B armeabi-v7a** (`android/gradle.properties:5-9`). The brief reports about 1.6 KB signed arm64 headroom; this draft did not independently rebuild that release. **UNKNOWN:** exact added code/resources and remaining current headroom. New bundled font/model/library bytes are **0 by design**, not “zero APK growth”. Measure both signed ABI deltas after each slice and request an owner-approved ceiling increase by the measured required amount. No guessed 1 KB allowance, automatic raise or budget bypass. A palette table's source byte count is not its APK delta.

## 10. Decisions for the owner

### Blocking batch

**PF1. Scope of profile flexibility**
- **A (recommended):** sixteen colours plus person/initial/curated emoji and compatible-peer updates; recognisable without collecting photos.
- **B:** colours and local initial rendering only this release; smallest risk, but no sender-chosen picture or remote profile refresh.
- **C:** include photographs and personal details now; requires the deferred transport/privacy design and materially more verification.

**PF2. Colour policy and accessibility**
- **A (recommended):** preserve 0..7, append the proposed eight, fix foregrounds and keep non-colour cues; zero wire growth.
- **B:** keep eight colours and fix foregrounds only; smaller chooser, less personal choice.
- **C:** arbitrary RGB; needs three-byte colour representation or palette negotiation plus stronger contrast controls.

**PF3. Avatar text and emoji input**
- **A (recommended):** one derived initial or one of twelve old emoji forms; bounded 0/3/4 B payload, no new font dependency.
- **B:** allow any single emoji grapheme up to 32 UTF-8 bytes; more choice, versioned Unicode/old-font work and a larger compatibility surface.
- **C:** two derived initials, no emoji; no image-like recognition for people who do not read.

**PF4. Mixed-version exchange**
- **A (recommended):** legacy code/link remains default, explicit full-profile export uses signed card v2 and hello kind 8; no guesses about old phones, but existing/new contacts need an enhanced exchange for updates.
- **B:** enhanced export by default with a clearly labelled legacy switch; better new/new experience, old phones initially reject the code/link.
- **C:** keep all cards/hellos v1 and make avatars local-only; simplest wire compatibility, no portable chosen avatar or negotiated updates.

**PF5. How contacts learn later edits**
- **A (recommended):** one encrypted ProfileUpdate behind the next explicitly sent private message, coalesced/throttled, capable peers only; adds a small packet only after changes.
- **B:** opportunistic profile+text bundle; saves a packet when there is room, costs up to 37 B of text in the curated example and needs overflow handling.
- **C:** in-person signed QR refresh only; zero radio airtime, remote contacts stay stale until another exchange.

**PF6. Shared chat and safety presentation**
- **A (recommended):** keep shared claimed names neutral, no profile bytes or trusted lookup; keep six safety pictures separate and unchanged.
- **B:** add claimed avatars in a new shared version; consumes bytes on every post and needs old-hub/old-phone migration, while still not proving identity.
- **C:** authenticate shared profiles first; separate signature/privacy design and at least the signature's 64-byte cost before avatar bytes.

**PF7. App-size and ship gate**
- **A (recommended):** no new font/library, measure signed per-ABI deltas, owner raises ceilings only by approved measured need; palette-only slice may ship if later gates fail.
- **B:** preserve current ceilings absolutely; cut/defer features if measured growth exceeds them.
- **C:** approve a larger size budget in advance; requires an explicit numeric budget from the owner, not this draft's invented estimate.

### Deferred picks, not blockers for PF1-A

**PF8. Small picture avatars**
- **A (recommended):** defer; if pursued, compare 48×48/512 B and 64×64/1,024 B, QR or explicitly hub-only, never LoRa and never the shared chat.
- **B:** QR-only signed picture object first; lower routing risk, more in-person steps and larger camera codes.
- **C:** include hub-only E2E pictures now; approve the missing attachment envelope/routing/retention appendix before implementation.

**PF9. Pronouns and about**
- **A (recommended):** collect neither in v0.1.1; protect privacy and keep the first profile simple.
- **B:** optional pronouns only in chosen contacts' details; bounded 24 B plus length and explicit audience consent.
- **C:** pronouns plus about, 24/64 B; up to 90 B plus any new option/version framing, never in default invitations, hellos or shared posts.

**PF10. Richer emoji and initials later**
- **A (recommended):** revisit after actual pilot recognition and API 26/Android 10 glyph evidence; do not mistake browser rendering for phone compatibility.
- **B:** fund unrestricted one-EGC input and explicit user-chosen initials next; broader Unicode policy and app-size work.
- **C:** keep the fixed chooser permanently; predictable but limited expression.

## 11. Sources and verification boundary

Repository sources are cited inline with path:line, resolved by the shorthand above. Additional context read: `docs/how-it-works.md:9-25, 35-62`, `docs/android-design.md:5-15, 27-40`, `android/hub/README.md:6-13, 15-31`, and the accounts, chat-features, picture and demo specifications cited above. Source comments and old design tables can lag the encoder; the byte ledger follows the inspected Kotlin.

Reproducible light calculations: [`calc/2026-10-26-profile-flexibility-calc.py`](calc/2026-10-26-profile-flexibility-calc.py) and its output [`calc/2026-10-26-profile-flexibility-calc.json`](calc/2026-10-26-profile-flexibility-calc.json). All decision-critical results are also reproduced in this draft. The photo-embedding table corrects the one-byte length model to a two-byte length for W>255; it is not a legal enhanced-card encoding. No portrait data was acquired. Web sources below were opened, not cited from search snippets.

**VERIFIED artifact checks:** the HTML was exercised in isolated headless Chrome at 1200 px and 375 px widths, then both screenshots were visually reviewed. Selection, three live private previews, UTF-8 name budget, combining-mark initial, deferred-picture notice, keyboard Save, reset/reload, reduced-motion mode, no horizontal overflow and no external requests passed. No browser console errors were observed. The managed-browser helper could not launch because of its default-browser/profile setting; isolated Chrome provided the alternative without changing that setting.

Product verification remains **NOT RUN** as required by the brief. The deliverable is a design and reviewed browser mockup, not a claim that Phonemesh now supports these profiles.

## Sources

[1] https://www.unicode.org/reports/tr29
[2] https://developer.android.com/reference/android/graphics/Paint
[3] https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html
[4] https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html
[5] https://developers.google.com/speed/webp/docs/riff_container
[6] https://www.unicode.org/Public/emoji/5.0/emoji-test.txt
