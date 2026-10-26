# Private group chats

Draft for the owner's decisions, 2026-10-26. No code before the picks.

## 1. Goal and who it helps

Let a person choose a few contacts and speak or type to them together. Only those members' phones can decrypt the messages. This helps families, teams and neighbours who share radios but do not want to use the shared channel chat. It works without internet and without a hub. Speaking, hearing a draft before Send, member pictures and short labels include people who find reading difficult. This is owner decision 28, not a change to the meaning of the existing shared chat.

**Recommendation, INFERRED:** ship small private groups using pairwise fan-out through the existing Double Ratchet sessions. Start with at most five people including the sender. Do not introduce group encryption keys in v0.1.1. Use one organiser, explicit joining, no old-history transfer, and a membership-change barrier. Offer text and sketch-only pictures; defer full group photos. Sender keys are the better candidate if larger groups become necessary, but require a separate cryptographic and hub protocol review.

**Evidence labels:** VERIFIED means source code or an opened external source was read. INFERRED means a proposed design, calculation or engineering estimate, not implemented behaviour. UNKNOWN means measurement or review remains outstanding. All unimplemented requirements below are INFERRED. No implementation, Gradle, emulator, adb, phone or radio work was performed for this draft. The small Python calculation was executed.

## 2. How it works today

All repository paths in this document are relative to the repository root.

- **VERIFIED:** decision 28 defines a private group as chosen contacts, encrypted only for members. Decisions 3 and 30 require P-256 without a new crypto library and shelve quantum-safe keys. See `docs/specs/2026-10-14-accounts-design.md:17-20,64-74`; the Later list is at `:433-443`.
- **VERIFIED:** `ChatKind` is `GROUP, DIRECT, PERSON`. `GROUP` is the channel chat, not a private group: `History.kt:12,442-447` under `android/app/src/main/java/org/phonemesh/app/`. Its address is `channel:<number>`. Add a new kind, do not reinterpret this stored value.
- **VERIFIED:** private messages use `Postman`, port 301 and `WireV2`. The payload cap is 228 bytes. The header is one format byte, an eight-byte recipient code, optionally 35 masked ratchet bytes, then ciphertext with a 16-byte tag: `android/core/src/main/kotlin/org/phonemesh/core/wire/WireV2.kt:7-22,48-60`.
- **VERIFIED:** the wire's name is WireV2, but its current encoded version nibble is **1**, not 2. Reusing the name must not accidentally change that nibble (`WireV2.kt:16`).
- **VERIFIED:** current body kinds are text, reply, reaction, picture head, picture part, receipt and hello, numbered 1 through 7. An optional ten-byte acknowledgement follows the kind byte: `core/wire/Bodies.kt:10-41,153-181`, with `core/` here and below meaning `android/core/src/main/kotlin/org/phonemesh/core/`.
- **VERIFIED:** Postman persists ciphertext with its session, retries exact bytes, delays receipts by 15 to 60 seconds, and uses a 20-packet per-contact flow-control window. Its retry horizon is three days. See `core/mail/Postman.kt:15-20,58-74,138-165,201-207`, `core/session/Ratchet.kt:20-24,156-161`. Unsupported bodies are not consumed or acknowledged (`Postman.kt:112-116`).
- **VERIFIED:** a long body can defer a DH step; a link owner's first long message may need a later key-bearing packet before it can open. See `core/session/Ratchet.kt:52-74`. Group sizes must include the keyed header, not rely on the smallest possible header.
- **VERIFIED:** everything uses primary channel zero (`AppCore.kt:2396`, broadcast sends at `:573,876`). Private group encryption must not depend on changing the channel key. Audio remains local; text-port messages still have a 200-byte total cap and may not auto-fragment (`docs/design.md:37-56,74-75`). The new group body has a smaller text allowance below.
- **VERIFIED:** `Mailbox.classify` recognises port-301 hellos or a valid WireV2 packet and indexes the latter by its eight-byte code. It cannot classify arbitrary new broadcast headers. It serves the same stored record to every caller whose requested codes match; records are not consumed on fetch. See `android/hub/src/main/kotlin/org/phonemesh/hub/Mailbox.kt:44-67,85-98`.
- **VERIFIED:** pictures currently send only to `PERSON` chats with a session, and only in picture-enabled builds (`AppCore.kt:1758-1773`). `PictureWire` allows 3,200 image bytes, a 32-byte sketch, and 198-byte v2 parts, up to 17 parts plus a head (`core/PictureWire.kt:25-42,67-84`). A group wrapper does not fit around an already full part.
- **VERIFIED:** existing English labels include **Chats**, **New private chat**, **Everyone nearby**, **Waiting**, **Sent**, **Reached the hub**, **Delivered**, **Waiting for a radio**, and **Send a picture** (`android/app/src/main/res/values/strings.xml:19,42-45,68-73,276,323`). The shared chat can instead show the primary channel name (`AppCore.kt:487`). The shipped hub screen still says **Village hub** (`strings.xml:464`); decision 27 separately changes that to local hub.

## 3. Options and costs

### Calculation contract

**INFERRED, reproducibly calculated:** [`calc/2026-10-26-private-group-chats-calc.py`](calc/2026-10-26-private-group-chats-calc.py), executed with Python 3. Tables use total group size **N**, including the sender, and **R = N - 1** recipients. There is no send-to-self packet.

One-hop aggregate radio airtime, not delivery latency: LongFast SF11, 250 kHz, coding rate 4/5, preamble 16, explicit header and CRC. Each application payload P is charged P + 22 bytes, following the hub's broadcast estimate in `android/hub/src/main/kotlin/org/phonemesh/hub/Airtime.kt:7-22`:

```python
symbol = 2**11 / 250000
symbols = 8 + max(ceil((8*(P+22) - 4*11 + 28 + 16)/(4*11))*5, 0)
seconds = (16 + 4.25 + symbols) * symbol
```

**Important correction:** the brief's rough “~1 s for a full packet” is too low for this source's LongFast estimator. A 228-byte application packet is **2.115584 s**. This is not an RF measurement. `core/LoraAirtime.kt:17-21,38-49` includes PKI-direct overhead and an acknowledgement per picture packet; do not use that direct-picture helper unchanged for accounts broadcasts. These tables exclude relays, losses, retries, channel contention, hub custody acknowledgements, protocol setup and regulatory waiting. Broadcasts do not earn a stock direct-message radio acknowledgement. Traffic served wholly by the hub LAN need not cross LoRa, but a hub cannot be assumed for budgeting.

For a comparable **80-byte UTF-8 text with a two-byte language tag**:

| Candidate | Per-message application layout for comparison | Data packets | Security and phone work | Added app bytes / effort | v0.1.0 |
|---|---|---:|---|---|---|
| A. Pairwise fan-out, recommended | Existing 25-byte unkeyed WireV2 overhead + 31-byte group envelope + 1 language length + 2 language + 80 text = **139 B per recipient**; keyed = 174 B | R | R encryptions; reuse sessions and receipts; pairwise FS and conditional healing; R durable ciphertext copies | **UNKNOWN measured size**. No new dependency or model bytes. Planning allowance 40,000 to 100,000 compressed APK bytes for group engine, database, strings and UI; medium effort | Old phones cannot join; old hubs can carry unchanged outer packets |
| B. Sender keys | New group-broadcast outer header/tag budget 25 B + same 114-byte group plaintext + 64-byte P-256 signature = **203 B** | 1 after distribution | Each member has a sending chain, signature key and receives others' chains; forward secrecy by erasing old chain keys, no automatic DH healing | **UNKNOWN measured size**. No library required in principle; more protocol, state, tests and review than A. Budget not approved | Old phones and unmodified hubs cannot process the proposed new outer format |
| C. Shared epoch key, rotated on membership changes | B's signed message budget + 12-byte explicit nonce = **215 B** | 1 after distribution | One epoch key, sender signatures, durable nonce/replay rules. No within-epoch FS or automatic healing | **UNKNOWN measured size**. Less chain machinery than B, but still new crypto composition and hub format; not a cheap drop-in | Same restriction as B |

Candidate B is **Signal-style**, not a claim of Signal wire compatibility. The opened WhatsApp Signal-protocol whitepaper, “Group Messages”, pp. 17-18, describes distributing each sender's chain key and signature public key using pairwise sessions, then signing a single ciphertext, and restarting sender keys when someone leaves. Our candidate would use P-256 and an independently reviewed AES-GCM construction, not copy its Curve25519/AES-CBC byte layout.

Candidate C must include a signature or explicitly abandon authenticated senders: anyone holding the same symmetric key can otherwise forge anyone else's messages. The cheap unsigned variant would be 151 B for this example, but is not the compared safe option. All numeric B/C layouts are budget candidates, **not approved cryptographic specifications**.

### Text data only: packets / application bytes / seconds

| N | A: fan-out | B: sender keys | C: signed shared key |
|---:|---:|---:|---:|
| 3 | 2 / 278 / 2.920 | 1 / 203 / 1.911 | 1 / 215 / 2.034 |
| 5 | 4 / 556 / 5.841 | 1 / 203 / 1.911 | 1 / 215 / 2.034 |
| 10 | 9 / 1,251 / 13.142 | 1 / 203 / 1.911 | 1 / 215 / 2.034 |
| 20 | 19 / 2,641 / 27.744 | 1 / 203 / 1.911 | 1 / 215 / 2.034 |

A's corresponding all-keyed data times are **3.412, 6.824, 15.354 and 32.414 s**. Optional pairwise acknowledgements add ten bytes to an eligible body. A's 120-byte text cap below deliberately leaves space for both a key and an acknowledgement. B/C have only 105/93 text bytes available with the illustrated envelope and two-byte tag; larger text would require a smaller budget or another design, not a free second packet.

### Receipts are not free

For one isolated text, A sends up to R ordinary receipts, each 36 B, or 71 B with a ratchet key. A receipt means durable acceptance by that recipient's group handler, not just successful AES decryption. Rejecting the group envelope must not produce Delivered.

B/C still need R individually authenticated confirmations if the sender must know who received the broadcast. Budget a pairwise group-receipt body with the 31-byte envelope and an eight-byte target message id: 64 B unkeyed or 99 B keyed. Ordinary pairwise chain acknowledgements cannot acknowledge a separate broadcast chain. This baseline is for one target, not a claim that existing Ack can batch sender-key messages.

| N | A receipt packets / bytes / s | B/C receipt packets / bytes / s | A data + receipts, s | B total, s | C total, s |
|---:|---:|---:|---:|---:|---:|
| 3 | 2 / 72 / 1.364 | 2 / 128 / 1.774 | 4.284 | 3.684 | 3.807 |
| 5 | 4 / 144 / 2.728 | 4 / 256 / 3.547 | 8.569 | 5.458 | 5.581 |
| 10 | 9 / 324 / 6.138 | 9 / 576 / 7.981 | 19.280 | 9.892 | 10.015 |
| 20 | 19 / 684 / 12.958 | 19 / 1,216 / 16.849 | 40.702 | 18.760 | 18.883 |

These totals use unkeyed packets. A's keyed receipt totals alone are 1.855, 3.711, 8.350 and 17.627 s; B/C's are 2.265, 4.530, 10.193 and 21.518 s. Quiet-period batching and replies can reduce separate receipts. Receipt storms, missing receipts and duplicate-message-triggered receipts still require tests. Never send acknowledgements of receipt bodies. Preserve the existing jitter rather than having every member reply at once.

### Setup and member removal

Existing pairwise contacts are assumed. Contact establishment, capability checking, roster consent and their receipts are **additional**, not hidden in the figures.

- **A:** no group-key distribution. Adding a group reuses sessions. Every member must already have a session with every other member. Removal needs no rekey of the remaining pairwise sessions, but needs an authenticated roster update and cancellation of pending work to the removed person.
- **B:** one sender initially distributes its key to R members. If everyone speaks, N × R distributions. Budget a 132-byte distribution body: 31-byte envelope, chain key 32, routing secret 32, compressed P-256 signature public key 33, counter 4. With a keyed WireV2 wrapper this is 192 B, 1.829 s. Removing one person requires every remaining sender to replace its keys and distribute to the other remaining members: (N - 1) × (N - 2) packets. Lazy distribution postpones cost until a sender speaks, it does not remove it.
- **C:** an organiser distributes a fresh random 32-byte group key and 32-byte routing secret with an epoch/counter field, budget 99-byte body, 159 B keyed packet, 1.583 s. Initial distribution costs R packets. After removing one person, the organiser sends a new key separately to N - 2 other remaining members. Do not encrypt the new key with the old shared key. Rotation is required on addition too if new members must not read recorded old ciphertext.

| N before removal | B all-sender setup packets / s | C setup packets / s | B removal key packets / s | C removal key packets / s |
|---:|---:|---:|---:|---:|
| 3 | 6 / 10.973 | 2 / 3.166 | 2 / 3.658 | 1 / 1.583 |
| 5 | 20 / 36.577 | 4 / 6.332 | 12 / 21.946 | 3 / 4.749 |
| 10 | 90 / 164.598 | 9 / 14.248 | 72 / 131.678 | 8 / 12.665 |
| 20 | 380 / 694.968 | 19 / 30.079 | 342 / 625.471 | 18 / 28.496 |

A's proposed removal barrier sends a roster proposal, READY, then COMMIT per remaining remote member: 3 × (N - 2) control packets, before transport receipts or an optional notice to the removed member. With maximum-length names and keyed packets the calculated costs are **3 packets / 3.971 s** for N=3 and **9 / 12.282 s** for N=5. For comparison N=10 would be 24 / 35.045 s. At N=20 the full roster body is 227 B and does not fit; chunking or another membership format would be required. These larger cases are not supported by the recommended five-person cap. B/C also need an authenticated membership protocol; their table is key traffic only.

**Removal is not instant across an offline mesh.** Before a remaining phone learns the change, it may send old-epoch content to the removed member. No candidate retracts ciphertext already on a radio or hub, or history already read. The proposed barrier stops new-epoch messages until all remaining members have acknowledged freezing the old epoch. The UI must say removal is pending until then. A silent/offline remaining member can stall it.

### Hub storage, metadata and phone storage

**VERIFIED:** mailbox disk records add 20 bytes per packet: sequence 8, time 8, port 2, length 2 (`Mailbox.kt:131-144`). Duplicate exact packets do not create another record or refresh retention (`:53-79`). This excludes the outbox, filesystem overhead and in-memory indexing.

| N | A hub data record bytes per example text | B hub bytes | C hub bytes | A sender pending ciphertext bytes |
|---:|---:|---:|---:|---:|
| 3 | 318 | 223 | 235 | 278 |
| 5 | 636 | 223 | 235 | 556 |
| 10 | 1,431 | 223 | 235 | 1,251 |
| 20 | 3,021 | 223 | 235 | 2,641 |

Add 56 bytes per unkeyed A receipt or 84 per B/C receipt to mailbox disk storage. Keyed variants add 35 bytes per record. At 30-day retention, multiply actual per-day packet counts by retained days, not by the number of phones that download a record. An old hub need not understand A's body; its existing classifier and code filter work unchanged. New B/C broadcast formats require classifier/version changes in both phone and hub. Do not falsely mark a sender-key packet as a current pairwise packet merely to make `WireV2.parse` accept it.

A keeps all group ids, rosters, names, colours and logical ids **inside pairwise ciphertext**. An outsider sees radio/node headers, channel, timing, lengths, private-port traffic when able to decode the outer channel, and retransmissions. Fan-out bursts can reveal likely group activity and approximate recipient count. The hub sees submitted ciphertext, transport clients and requested code sets; it can correlate these, but cannot read names, roster or content. Do not promise traffic-analysis anonymity.

For B/C, avoid a static clear group id. A reviewed candidate could use changing eight-byte codes derived from an epoch routing secret and a sender/counter, with bounded look-ahead. All recipients request the **same code** for the same broadcast packet, so the hub can correlate group readers even if the group id is hidden. A stable group mailbox token would be simpler but directly link every message. Retaining routing secrets has its own metadata exposure; deleting message keys does not hide old routing metadata. A malicious member can share keys or plaintext in all designs.

**Phone bytes, INFERRED from verified encoding:** an established current pairwise state with both keys present and no skipped messages encodes to 311 B, or 339 B with Vault's nonce/tag. At 50 skipped keys add up to 2,950 B. See `core/session/SessionState.kt:59-84`, `core/session/Ratchet.kt:20-24`, and `AccountStore.kt:25-28`. If the contacts already exist, A adds **zero new ratchet states per group**. If those contacts were newly made, per-phone baseline session storage for N=3/5/10/20 is 678/1,356/3,051/6,441 B, before skipped keys, cards, database pages and row labels. Sessions are shared with 1:1 traffic, not copied for each group.

A stores one user-visible message, R recipient-status rows and R outbound ciphertext rows, not R plaintext history copies. A receiver stores one history copy. Roster ids alone cost 8N bytes per epoch, plus fixed metadata. B adds O(N) chains, route keys, counters and signature public keys per group, roughly 101 B per sender state before serialization, local private signing key, Vault wrappers and bounded skipped keys. C adds at least 64 B of epoch/routing secrets plus per-sender nonce/replay state and signing-key bindings. Both retain per-recipient delivery status and usually still need pairwise sessions. Pictures and retained history will dominate these small cryptographic-state totals. **UNKNOWN:** measured database, heap, battery and low-end-phone costs.

### Security comparison

| Property | A. Fan-out | B. Sender keys | C. Shared epoch key |
|---|---|---|---|
| Earlier ciphertext after current-state theft | Pairwise forward secrecy after old keys are erased, except retained skipped keys and saved plaintext | Hash-chain forward secrecy after erasure; any member's retained old chain state can expose its covered history | Theft of an epoch key opens all recorded messages in that epoch; no within-epoch FS |
| Post-compromise recovery | Conditional pairwise DH recovery after fresh uncompromised exchanges; not while malware remains | Hash advancement alone cannot heal. Redistribute fresh sender keys over healed pairwise channels; replace compromised signing keys too | No automatic healing. Fresh random epoch key over healed pairwise channels, excluding the attacker |
| Sender authentication | Pairwise authenticated contact is the sender, not a name inside the body | Signature required because recipients know sender chain keys | Signature required because everyone knows the epoch key |
| One member's lost/unlocked phone | Exposes that member's saved group history and current sessions; not the other members' private 1:1 conversations | Exposes saved history and every group sender chain held there | Exposes saved history and entire retained group epochs |
| Malicious member | Can leak or send different texts to different recipients; cannot impersonate another pairwise contact | Can leak; cannot sign as another sender without their signing key | Can decrypt all epoch traffic; cannot impersonate another sender if signatures are enforced |

The Signal Double Ratchet specification, sections 2.1-2.3, explicitly distinguishes one-way chain forward security from recovery requiring fresh secret input. This is why “rotating a hash” is not post-compromise recovery. None of these designs makes a compromised endpoint safe, remotely deletes history, or supplies post-quantum protection.

## 4. Recommendation and boundaries

Choose A for v0.1.1. Its costs are visible and bounded at five people. It preserves the deployed ratchet, code-based hub retrieval, per-contact authentication and retry engine. It avoids a new group cipher and a forced hub upgrade. It also exposes an important product cost: everyone must already know everyone as a contact. Do not hide that behind automatic contact import.

Do not ship twenty-person fan-out under a “small groups” label. Even the 80-byte example costs 40.702 seconds for one text with one round of unkeyed receipts before relays or retries. If the owner needs ten or twenty people now, choose B and budget its review and new transport, rather than expanding A's cap silently. C gives up too much forward secrecy for a relatively small saving in control machinery.

## 5. Recommended design

### 5.1 Creation, authority and joining

1. From **Chats**, add a new action **New private group**. Keep **New private chat** unchanged. Select two to four existing `PERSON` contacts; creator included makes three to five. Stock `DIRECT` users and shared-channel entries are never eligible.
2. The creator is the sole organiser. Only the organiser may propose roster changes, rename the group or change its appearance. A member may request that someone be added through an ordinary conversation, but cannot modify the roster. No public join links or searchable group list.
3. Every proposed member must already have a current authenticated pairwise session with every other member. The organiser's contact list alone does not establish this. On invitation, each phone validates all roster identities against its own contacts and confirms readiness. Missing contacts must be added using the existing QR/link flow, with explicit human consent. Do not distribute cards or silently create sessions through the group.
4. An invitation shows organiser, full member list, group name and appearance before **Join** / **Ignore**. Joining reveals the member list to all members. A not-met contact remains marked as such; group membership does not upgrade its safety state.
5. Creation uses PREPARE, READY and COMMIT below. The organiser cannot send until everyone accepts and the group commits. If someone refuses, propose a new smaller group roster, never silently omit them from an already composed message.
6. Only one membership change is pending per group. No admin succession in v0.1.1. If the organiser leaves, closes the group or loses the account, the group becomes read-only when that state is known. Members can create a new group. This availability trade-off is an owner pick, not hidden “recovery”.

**Capability gate:** add a bounded authenticated capability exchange over the existing session. Proposed new body kind 9 contains schema 1, request/reply byte, random challenge 8 and feature mask 4, total 15 B including kind. Request a response once on explicit eligibility checking, not on every app start. Cache a positive response only for that exact session and challenge. Do not use an app-version string or radio identity as proof of support.

A v0.1.0 decoder rejects the new kind without advancing its receive state or issuing a receipt. Send at most one unanswered capability probe per session, with no automatic retry, so old peers cannot fill the 20-packet window. Ordinary subsequent 1:1 traffic must still work with that skipped counter. Absence of a response means **unknown or offline**, not proven old software. Show **Update or reconnect to join** and exclude from group creation until positive support is confirmed. A known v0.1.0 fixture is explicitly ineligible. Session replacement invalidates capabilities and pauses affected group sends.

### 5.2 Wire layout

No new port, clear group address or WireV2 header version for A. Proposed kind numbers must be checked again before implementation because the source tree is shared with other v0.1.1 work. Reserve kind **8 = GROUP**, kind **9 = CAPABILITY**, with body schema 1. Kind 8 remains compatible with the existing high-bit acknowledgement prefix. Unknown operations fail closed.

Common group body, all integers unsigned big-endian:

| Offset, without optional Ack | Bytes | Field |
|---:|---:|---|
| 0 | 1 | Body kind 8; high bit may indicate existing ten-byte Ack immediately after it |
| 1 | 1 | Group schema 1 |
| 2 | 1 | Operation |
| 3 | 16 | Random group id, generated once with SecureRandom |
| 19 | 4 | Membership epoch, starting at 1; no wrap |
| 23 | 8 | Logical event id, monotonic per authenticated sender and group; no wrap |
| 31 | variable | Operation payload |

The offsets above are shifted by ten after the kind when Ack is present. The actual sender is the authenticated `Postman.Received.contact`, not an arbitrary payload field. Every recipient copy has the same group id, epoch and logical event id but different pairwise ciphertext and recipient code. Deduplicate by `(group id, sender account id, event id)`. A duplicate with different authenticated content is a conflict, not an overwrite. Persist ids and payload hashes; never generate new ids on retry.

Proposed operations: 1 TEXT, 2 PREPARE, 3 READY, 4 COMMIT, 5 LEAVE, 6 CLOSE, 7 SKETCH, 8 REPLY, 9 REACTION. Full-picture HEAD/PART operations remain reserved until GC8's deferred option is approved. They are not repurposed ordinary picture kinds.

- TEXT payload: language length 1, canonical language 2 to 6 bytes, UTF-8 text. **Cap text at 120 bytes**. This fits a keyed packet and optional Ack even with a six-byte tag: 31 + 1 + 6 + 120 + 10 = 168-byte body, plus 60-byte keyed overhead = 228. Reject at composition; never truncate or split after Send. Explain that group messages must be shorter, including spoken drafts.
- REPLY: referenced sender account id 8 and referenced event id 8, then TEXT payload. Reduce its text cap by those 16 bytes. Ratchet's existing four-byte reference is different for each recipient copy and cannot identify a group reply globally.
- REACTION: referenced sender 8, event id 8, emoji length 1 and emoji bytes. Coordinate with decision 29's separate reaction spec; do not quietly treat the existing eight-byte private-body emoji limit as the owner's future 32-byte limit. Validate the final encoded group packet.
- PREPARE: parent roster digest 16 (zero for first epoch), member count 1, sorted member account ids 8 each, name length 1, name up to 24 UTF-8 bytes, colour 1, icon 1. The body is at most **75 + 8N** bytes. The organiser identity is fixed from the authenticated initial invitation and stored permanently; it must be in every active roster. Existing contacts supply the full identity-key bindings. Reject duplicate ids, missing organiser, bad names and unknown icons.
- READY and COMMIT: candidate roster digest 16 after the common envelope. Digest = first 16 bytes of SHA-256 over a fixed domain label, group id, organiser account id, epoch and the canonical PREPARE payload. READY is an authenticated member's consent and frozen-state assertion; COMMIT is accepted only directly from the organiser and only for a saved matching candidate. These are not unauthenticated hub receipts.
- LEAVE: no additional payload; authenticated sender is leaving. CLOSE: no additional payload, organiser only. Both persist a local terminal state immediately and propagate through normal durable outbox handling.
- SKETCH: PictureWire-style 16-byte head and up to 32 sketch bytes, SKETCH codec and zero image bytes/parts. No invisible image-part download follows. Namespace the picture by group, sender and event, not its short legacy picture id alone.

The proposed cap is more conservative than current unkeyed text capacity on purpose. Do not put a full-size body plus Ack through Postman's present `maxBody(false)` eligibility test and accidentally defer the key. Add group-specific keyed-size validation and regressions at the serialization boundary.

### 5.3 Membership changes and ordering

PREPARE for epoch E+1 names the digest of E. Receiving it from the organiser freezes old-epoch sending, stops unsent and retry work to removed members, and stores the complete candidate. The organiser does the same before sending PREPARE. Existing in-flight bytes may already have left and cannot be recalled. Recheck membership immediately before every future seal, hub submission and radio submission.

A READY member has accepted the roster, has sessions and positive capabilities for all peers, and has durably frozen old-epoch sending. The organiser waits for every remaining member's READY, persists COMMIT, then fans it out. A member may send in E+1 only after its own matching COMMIT. Store incoming E+1 content pending COMMIT, bounded and without a delivery receipt; reprocess after COMMIT. Once all remaining READYs exist, no honest remaining sender will emit old-epoch traffic. Delivery of COMMIT can still lag.

If a remaining member is offline, show **Group change waiting** with the missing people. Do not time out into a weaker roster. The organiser may propose a subsequent candidate excluding an unavailable person, explicitly, or close the group. Never reuse an epoch number for a different candidate. Here “E+1” means the next allocated proposal epoch: after an abandoned candidate, allocate a larger number and keep the parent digest bound to the last committed roster. Numeric gaps are allowed; an unknown committed parent is not. A newer organiser proposal supersedes an uncommitted older proposal, preserves the freeze and invalidates its READYs. COMMIT for a superseded candidate is refused. This rule requires the organiser to persist the proposal counter and commitment decision before any network output. Conflicting digests for one epoch freeze the group and show an error. Replayed, skipped or reordered changes cannot roll the roster backwards. A phone missing predecessor state asks the organiser for authenticated roster state before accepting content; it does not trust another member's forwarded control packet.

Creation needs at least three people. A removal may leave two, retaining the group and its history; a group with only its organiser left becomes read-only. Limits proposed for the first implementation: 16 active private groups per phone, 16 pending invitations, one pending candidate per group, and at most 64 authenticated future-epoch packets across all groups with a 24-hour pending-content deadline. Hitting a cap produces a visible refusal, not silent acceptance. Durable terminal epoch metadata is retained with the group history, not removed merely because a pending-content timer expires. Existing three-day outbox retries remain bounded; expiry of a control send shows failure but never authorises an unsafe unfreeze. A manual retry reuses the saved candidate and bytes where applicable. These resource limits must be reconciled with the shared incoming/outbox limits during implementation review.

This central organiser is trusted not to equivocate. The design is not distributed consensus against a malicious organiser. Pairwise authentication prevents an ordinary member from forging organiser controls, but cannot prevent an organiser from telling members different things. Digest conflict detection and explicit member lists make this detectable when views meet, not impossible.

A leaving member stops locally at once, cancels its outgoing group work and sends LEAVE to the organiser. Others stop sending to it when their roster change completes. The leaving screen says that already-sent messages may still arrive. A removed member cannot decrypt newly created remaining-member fan-out copies, but still owns its old copies and can read traffic sent by stale members before the freeze completes. Do not erase its ordinary 1:1 contact or session automatically.

Joining later gives **no earlier history**. Only messages committed in the new epoch are eligible; do not reseal old queued messages for a new member. New members see a local “Joined the group” boundary. Existing members can explicitly forward selected content later, which is a new message with a new audience. Old-epoch incoming content after a completed removal is not displayed as new live group content; it may be marked obsolete while the transport safely consumes authenticated stale packets without claiming group delivery.

### 5.4 Durable storage and sending

Add `ChatKind.PRIVATE_GROUP`, keep `GROUP`, `PERSON` and `DIRECT` stable. Add a group-id address namespace, never convert it to a radio destination. The group engine fans out to contacts before reaching transport.

Proposed logical tables, integrated with the current History migration rather than choosing an already occupied version number:

- `private_groups`: group id BLOB(16), chat id, organiser account id, committed epoch/digest, name, colour, icon, local membership state.
- `group_epochs`: group id + epoch, canonical roster, parent digest, candidate digest, state; bounded unresolved candidates and replay/tombstone metadata.
- `group_members`: group id + epoch + account id, consent/READY/COMMIT state, capability/session binding.
- `group_events`: group id + sender id + event id unique, epoch, content hash, linked visible message id; persistent next local event counter.
- `group_deliveries`: visible message id + recipient id + epoch, frozen intended audience, transport/outbox references, status and error. Picture delivery requires all parts for that recipient.
- `peer_capabilities`: contact/session identity, challenge and feature mask; one unanswered probe maximum.

Use one canonical message and immutable audience snapshot. A single durable transaction creates the logical message and per-recipient unsealed jobs. Each later sealing transaction atomically advances that contact's session and stores its exact ciphertext, through the existing expected-state discipline. A crash between recipients resumes only unfinished jobs. Do not call `Postman.send` R times without durable intent and then pretend the whole Send was atomic. Interleave ready recipient queues so one full pairwise window does not block all others; preserve order per contact and the transport's single in-flight acceptance rule.

Incoming transaction: validate group membership/epoch/sender and operation, persist content and dedupe identity, advance ratchet state, record peer-ack effects and schedule an appropriate receipt together. Group persistence needs a real MailStore handler. `AppMailStore` currently routes incoming pictures/person messages and reconciles one-contact delivery (`AppMailStore.kt:60-69,135-151`); its terminal single-message shortcuts must not mark an entire group delivered after one recipient.

For semantically rejected but cryptographically authenticated stale traffic, separate transport consumption from Delivered. **This is a required change, not behaviour obtained by wrapping Postman.** `Ratchet.ack` currently infers missing counters from retained skipped keys (`core/session/Ratchet.kt:141-151`); consuming a rejected group packet, or later forgetting its skipped key, can otherwise make a cumulative receipt falsely cover it. Add a durable per-session/per-chain acceptance ledger. Every standalone or piggybacked Ack on a group-capable session must be built from durably accepted bodies, not simply `Ratchet.ack(state)`. Choose the highest accepted counter and set missing bits for every preceding counter not known accepted. The highest field must never name a rejected or pending packet. `Ack.covers` already refuses counters more than 32 below highest (`core/wire/Bodies.kt:166-171`), so older missing counters need not become implicitly delivered. Persist pending receipt obligations for accepted older packets and issue a separate covering receipt when needed. Keep acceptance metadata through the corresponding receipt/replay obligation; pruning must never turn unknown into accepted. Ordinary 1:1 content on the same session uses the same ledger. Canceled group jobs are terminal and cannot be revived by a late Ack.

This preserves the ten-byte Ack layout and the isolated-message receipt costs above, but adds storage and receipt-builder work. Batching can require multiple receipts across chains or widely separated counters. Test rejection, later higher counters, skipped-key expiry, restart, piggybacking and delayed old-chain receipts together. Unknown future epoch traffic can be transport-consumed into durable pending storage without a delivery acknowledgement, then marked accepted only when COMMIT permits its durable group delivery. A pending/rejected group body must not stall unrelated 1:1 traffic. A failed or expired membership operation remains visibly unresolved; it never silently becomes Delivered.

Use Vault for new sensitive group state and pending plaintext, with row-specific associated-data labels. Existing Vault only establishes protection of secrets/session state; this draft does not assert that all current chat history is encrypted at rest. Do not log rosters, bodies, codes or names. Keystore protection does not stop an unlocked compromised app. Account-recovery/backup work must separately decide what group membership can be restored; restoring stale counters or pre-removal state must not silently resume sends.

### 5.5 Screens and words

New wording below is **proposed**, not a claim about shipped strings. Provide English and French resources and use the normal read-back flow. No colour-only status. Review new how-to pictures and short spoken instructions before implementation.

| Place | Proposed English | Proposed French | Behaviour |
|---|---|---|---|
| Chats action | New private group | Nouveau groupe privé | Separate from New private chat |
| Contact picker | Choose people | Choisir les personnes | Existing people only; visible count includes you |
| Group details | People in this group | Membres du groupe | Names, pictures/colours, organiser marker, safety state |
| Invitation | Join / Ignore | Rejoindre / Ignorer | Large tick/cross; full roster before consent |
| Capability failure | Update or reconnect to join | Mettez à jour ou reconnectez-vous | Do not call silence a version check |
| Pending change | Group change waiting | Modification du groupe en attente | List who has not confirmed |
| Receipt detail | Delivered to 3 of 5 | Reçu par 3 personnes sur 5 | Counts recipient phones, excludes the sender |
| Leave | Leave group | Quitter le groupe | Explain delayed removal and retained history |
| Name/appearance | Group name / Colour / Picture | Nom du groupe / Couleur / Image | Organiser edits, all members receive change |

The requested example **Delivered to 3 of 5** means five intended recipient phones, hence six people including the sender. That example belongs in formatting tests and a larger-group prototype, not the recommended cap-five production screen. A five-person group can say **Delivered to 3 of 4**. Do not count the sender as a delivered recipient merely to make “of 5” fit.

Show a small progress indicator and an adjacent short status while invitation/changes are pending. For a message, retain **Waiting**, **Sent**, **Reached the hub** and **Delivered** meanings per recipient. Main bubble: delivered count if any; otherwise waiting/sending state. Details list each intended recipient, including Failed or Not confirmed. A hub acknowledgement never increments delivered count. Delivered is not read or heard. A message's denominator remains its send-time audience even after membership changes; canceled recipients are labelled, not silently removed to manufacture “all delivered”.

Group name: spoken or typed, cleaned with the existing name policy and capped at 24 UTF-8 bytes. Colour: reuse the eight `AccountColours` entries (`NameActivity.kt:249` onward). Picture: choose a small fixed pictogram from existing suitable artwork, separately reviewed; no uploaded group photograph in v0.1.1. It is a group marker, never a person's safety picture or proof of identity. No new bitmap assets or model downloads are required by this pick. All members see a roster/name change event. Do not reuse the shared channel's name or make this look like **Everyone nearby**.

### 5.6 Pictures and airtime

**VERIFIED baseline:** an existing v2 picture is one head plus `ceil(imageBytes / 198)` parts. A 3,200-byte picture uses 18 packets per recipient. Simply multiplying that baseline gives 36/72/162/342 packets for N=3/5/10/20, but that is **not** an implementable group wrapper: full parts already fill 228 bytes.

**INFERRED full-photo design if later approved:** use the group envelope, a four-byte PictureWire-style part header and **123 image bytes per part**. This reserves the 35-byte ratchet key and optional ten-byte acknowledgement: 168 - 31 - 4 - 10 = 123. Extend split/validation limits explicitly; current `decodeV2` expects 198-byte parts and will not accept this as an ordinary v2 picture. With 3,200 image bytes and a 32-byte sketch: 27 parts plus a head, 28 packets, 5,904 application bytes and 55.468 s **per recipient**, assuming every packet keyed and no optional Ack bytes. Ack-bearing packets add more airtime. The head fits at 139 B keyed.

| N | A full-photo packets | A data-only seconds | B broadcast candidate packets / s | C broadcast candidate packets / s |
|---:|---:|---:|---:|---:|
| 3 | 56 | 110.936 | 32 / 67.084 | 36 / 75.629 |
| 5 | 112 | 221.872 | 32 / 67.084 | 36 / 75.629 |
| 10 | 252 | 499.212 | 32 / 67.084 | 36 / 75.629 |
| 20 | 532 | 1,053.893 | 32 / 67.084 | 36 / 75.629 |

B's 64-byte signature reduces picture chunks to 104 B; C's additional nonce reduces them to 92 B in these comparison layouts. Both still need distribution and per-member completion receipts; selective repair over pairwise sessions can add substantial fan-out. These are lower-load broadcast alternatives, not claims that group pictures become cheap.

A's 28 packets exceed the 20-packet flow-control window, so intermediate acknowledgements are needed even before final picture completion. Do not budget only one final receipt per recipient. Lost parts are retried per recipient, not retransmitted to everyone. One recipient's complete picture does not mark the group picture delivered. Cancel remaining jobs to removed members; bytes already aired remain exposed. Retain one outgoing encoded image, not R source images; durable encrypted part copies still scale with R.

**Recommendation for v0.1.1:** SKETCH only. One encrypted head per recipient, no image parts. Reuse the visible **Send a picture** entry only if its preview clearly says that the group receives the colour sketch, not a hidden full photo. Show an aggregate radio-time estimate and require Send. If the owner rejects sketch-only output, disable group pictures clearly rather than quietly multiplying a normal photo by the member count. Full-photo scope is a separate deferred decision.

### 5.7 Hub role and failure cases

The local hub is a blind mailbox and radio scheduler, not the group organiser, key authority or membership database. For A there is no new hub API, port or required hub release. Store every per-recipient ciphertext; phones ask using their existing `Postman.expectedCodes` (`Postman.kt:92-95`). Existing code-based catch-up and exact-byte retry semantics apply. Do not upload a plaintext roster to obtain hub fan-out. A hub cannot create R secure pairwise copies of one plaintext without gaining keys, which is prohibited.

Threat and failure requirements:

- **Outsider:** cannot read or modify authenticated group content, but can observe traffic, replay, flood or jam. Bound unknown packets, pending invitations and future-epoch storage. Authentication does not make radio denial of service impossible.
- **Hub:** can drop, delay, replay, correlate clients or falsely claim custody. Only recipient authentication gives delivery. It cannot add members, decrypt, or manufacture recipient receipts.
- **Lost phone:** saved content and current authorised memberships may be exposed. Remove that identity and wait for the barrier. Account recovery is not an automatic rejoin, and no UI promises remote deletion.
- **Contact outside the roster:** can send an invitation or ordinary 1:1 message, not inject content into an existing group. Authenticate the sender, then check roster membership and epoch.
- **Storage failure:** leave the fan-out job unsent or unconfirmed; never partially advance session keys without durable ciphertext or show Delivered before durable content. Reopen-and-replay tests are mandatory.
- **Offline/changed recipient:** do not downgrade to shared-channel text, stock DIRECT messages, unencrypted pictures or a subset of recipients. Keep pending/failed rows explicit.
- **Removal during retry:** cancel queued old-epoch submissions atomically on freeze. A late transport callback cannot revive a canceled job. Exact stored bytes already accepted by a hub cannot be recalled.
- **Duplicate invitation/change:** idempotent, with bounded replay tracking. Conflicting epoch content freezes the group. Do not allocate unbounded state for arbitrary unknown group ids from a contact.
- **No radio or hub:** retain the message and show **Waiting for a radio**. A pending group update is a different condition and must not use that same error text.

## 6. Compatibility and migration

- v0.1.0 phones remain usable for 1:1 and the shared channel. They cannot join a private group. Never send a group roster/message to a peer until capability support is positively authenticated. The single bounded capability probe is the only deliberate unknown-body interaction.
- Old hubs continue to parse and store A's unchanged outer WireV2 packets. Test this using the old classifier and actual bytes, not a mock that accepts everything. B/C would instead require a new envelope parser and explicit mixed-hub behaviour.
- Add the `PRIVATE_GROUP` stored enum without renaming `GROUP`. Migrate once from the actual release schema to the integrated v0.1.1 schema. `History.VERSION` was 15 in the read source (`History.kt:801`), but concurrent features may advance it; do not claim version 16 unconditionally.
- Preserve all existing chats, contacts, keys, retries, hub cursors and safety state. New groups start empty. No automatic conversion of the shared channel chat or old DIRECT history.
- On session reset or identity replacement, invalidate capability/readiness, pause that member and require revalidation. Do not decrypt and reseal historical copies automatically.
- No new Android permission, AndroidX dependency, crypto library or model is proposed. **VERIFIED ceilings:** `android/gradle.properties:8-9` sets arm64 to 16,575,000 B and armeabi-v7a to 14,715,000 B. **UNKNOWN:** actual signed v0.1.1 growth. The brief reports only about 1.6 KB of signed arm64 headroom. This feature will probably need an owner-approved ceiling increase, measured from the final signed artifacts on both ABIs; the 40,000-100,000 B allowance is an estimate, not permission or a measured delta.

## 7. Test plan and effort in slices

These are planned gates, **not executed tests**. Only the Python sizing calculation ran for this draft.

### JVM first

1. Group codec round trips and malformed-byte tests: every operation, optional Ack placement, 120-byte text, canonical language tags, UTF-8 boundaries, reply overhead, unknown versions, full/max roster, duplicate ids, counters near exhaustion. Verify every proposed packet and actual ToRadio wrapper fit the transport limit.
2. Three and five independent accounts using real Postman and lossy/reordered delivery. Exactly R data copies for one text, no send-to-self, same logical id across copies, distinct recipient codes/ciphertexts, correct sender on receive. New group body handlers must commit before receipts.
3. Capabilities: old parser rejects kind 9 without breaking later 1:1 traffic; at most one unanswered probe; no retry storm; challenge/session binding; replayed positive response cannot admit a replaced identity. Differentiate offline from known old fixtures.
4. Membership: creator-only controls, all-to-all contact readiness, explicit Join, ignored invitation, missing contact, prepare/ready/commit loss and reordering, simultaneous proposals, epoch conflict, organiser disappearance, one offline member stalling removal, no new recipient in an old frozen audience.
5. Security negatives: nonmember injection, spoofed sender field, forged hub receipt, replay, tampered ciphertext, stale-epoch sends, malicious ordinary member claiming to be organiser, removed member failing to decrypt new remaining-member copies. Scope any malicious-organiser consistency claim separately.
6. Crash/SQL-failure injection at each outgoing intent, seal, acceptance, incoming, receipt and roster transition. Reopen storage and prove no reused ratchet key, no lost recipient job, no false Delivered. Test cancellation versus late acceptance callbacks.
7. Existing Mailbox classification, duplicate retention, code-only retrieval, same record not globally consumed, old hub compatibility, cursor catch-up after new session codes. Prove no roster or group id in clear outer bytes.
8. Receipt counts: stable denominator, Delivered to 3 of 4 at the production cap; formatter-only Delivered to 3 of 5; no double counting, no hub-as-delivery, duplicates, partial failures, batching and jitter. Group history never appears in PERSON chats.
9. Sketch tests in picture-enabled and picture-disabled builds. If full photos are later selected, add split/reassembly/cross-group-id collisions, 27-part limit, intermediate receipts, interrupted transfer, selective retries and the 20-packet window; normal PictureWire v2 must remain unchanged.

### Emulator and demo gates, later with permission

Exercise EN and FR creation, missing contacts, old member, consent, waiting, partial delivery, details, rename/colour/icon, leaving, removal and history boundaries. Review readable labels, secure-screen behaviour, TalkBack descriptions and how-to pictures; do not claim speech accessibility is solved by this feature. Use silent speech fakes until recording/playback is explicitly authorised. Test signed size on both ABIs and database upgrade from the released schema.

**VERIFIED demo contract:** `docs/specs/2026-10-25-demo-build-design.md:14-40` requires the separate `Phonemesh Demo` app, real engines, Kofi/Ama/Yaw, and a real in-process hub/mailbox. The demo is not RF or security-boundary evidence (`:42-46`).

Add deterministic group scripts through `src/demo/` and tests through `src/testDemo/`. Demo actors run the production group engine, their own real Postman sessions and storage; do not inject ready-made Delivered UI states. Establish and show the necessary demo contacts. Cover a three-person group with Kofi and Ama, add Yaw later with no history, keep Ama offline to show pending changes and partial delivery, drop a receipt, restart the hub, remove a member and prove no new plaintext reaches that actor. Add a v0.1.0-compatible actor whose decoder rejects capabilities and cannot join. For maximum-size production tests use an additional labelled demo actor; do not silently raise the cap. The stock Meshtastic demo user remains ineligible. Persist the group's state across demo restart. No demo references in `src/main`; production group functionality itself belongs in main/core as normal.

### Effort, INFERRED planning estimates

| Slice | Work | Estimate / acceptance |
|---|---|---|
| 1 | Owner picks, protocol detail, independent membership/security review | 2-4 engineering days; no app code before approval |
| 2 | Capability/group codecs, group state machine, JVM fixtures | 3-5 days; old-peer and loss tests green |
| 3 | Transactional History/MailStore fan-out, receipts, migration, retries | 4-6 days; injected-failure/reopen tests green |
| 4 | EN/FR screens, read-back, appearance and sketch-only preview | 3-5 days; reviewed emulator states and byte limits |
| 5 | Demo actors, compatibility suite, signed-size checks and release evidence | 2-4 days; permission-dependent gates and measured ceilings |

Estimates are not a delivery promise and exclude a sender-key redesign or full-photo implementation. Independent review may change the wire proposal. Physical mesh capacity and usability with shared radios remain **UNKNOWN** until separately authorised field qualification. Do not run that equipment during drafting.

## 8. Decisions for the owner

### Blocking batch

**GC1. Encryption design for v0.1.1**
- **A (recommended): Pairwise fan-out.** Reuses current protection and old hubs; radio cost grows with recipients.
- **B: Sender keys.** One broadcast per text after setup; adds a new protocol, signatures, key rotation and upgraded hubs.
- **C: Shared rotating group key.** Simpler group secret, but exposes an entire epoch after key theft and still needs signatures.

**GC2. Maximum group size, including yourself**
- **A (recommended): Five people.** Keeps fan-out and one-packet roster controls bounded; create with at least three.
- **B: Ten people.** More useful for teams, but fan-out airtime is already costly; reconsider GC1B.
- **C: Twenty people.** Requires sender-key work and a larger-roster protocol before release, not just a UI limit change.

**GC3. Who can add people, and which contacts qualify**
- **A (recommended): One organiser; every member already has every other member as a contact.** No silent trust or session creation, but more setup.
- **B: Organiser introduces unknown contacts through the group.** Easier setup, but requires a separately reviewed identity-introduction and consent protocol.
- **C: Any member can add.** Less organiser dependence, but needs multi-writer membership authority and conflict rules.

**GC4. Membership changes while someone is offline**
- **A (recommended): Freeze old sends and wait for all remaining members before committing.** Honest removal boundary; an offline phone can stall it.
- **B: Apply locally and continue immediately.** Better availability, but removal remains inconsistent and stale members can keep sending to the removed person.

**GC5. History for a member joining later**
- **A (recommended): New messages only.** No automatic disclosure or replay traffic; selected forwarding remains explicit.
- **B: Offer a bounded history transfer.** Easier catch-up, but adds privacy consent, airtime and storage rules.

**GC6. Group name and appearance**
- **A (recommended): Organiser sets a short spoken/typed name, one existing colour and a fixed pictogram.** Small wire and app cost, no photo broadcast.
- **B: Add an uploaded group photo.** More personal, but it is a membership-wide picture transfer every time it changes.

**GC7. Receipts and their denominator**
- **A (recommended): Delivered count out of the send-time recipients, excluding you.** Accurate per-phone status with batched receipts; five people means “of 4”.
- **B: Sent only, no delivery counts.** Saves receipt traffic but loses confirmation people already expect from private chats.

**GC8. Pictures in v0.1.1 private groups**
- **A (recommended): Sketch-only pictures with an explicit preview.** One packet per recipient; no full-photo promise.
- **B: No group pictures yet.** Smaller scope and clearer limitation, but loses a useful non-text message.
- **C: Full photos with new group chunk sizing and an airtime warning.** A maximum image to a five-person group costs 112 data packets before receipts/retries.

**GC9. Old phones and capability checking**
- **A (recommended): Require authenticated group support; old or unconfirmed members cannot join.** Preserves privacy and prevents broken groups; offline checking can wait.
- **B: Rely on a manual “updated” checkbox.** Simpler UI but cannot prove the recipient can persist or acknowledge group content.

**GC10. Organiser departure and loss**
- **A (recommended): No transfer in v0.1.1; close/read-only and create a new group.** One clear authority, at the cost of continuity.
- **B: Build explicit organiser transfer now.** Preserves continuity but adds another consent and authority-change protocol.

**GC11. App-size gate**
- **A (recommended): Measure signed growth, then request the exact ceiling increase.** No new libraries/models; do not ship against a guessed allowance.
- **B: Refuse any ceiling increase.** Existing headroom is unlikely to fit this feature; defer it instead of weakening tests or hiding bytes.

### Deferred batch, not needed to start the recommended scope

**GC12. Larger-group follow-up**
- **A (recommended): Revisit sender keys after pilot traffic and contact-setup measurements.** Spend protocol complexity only when small groups are demonstrably insufficient.
- **B: Design larger groups in parallel now.** Earlier scale option, but independent crypto/hub review must not be mistaken for a v0.1.1 commitment.

**GC13. Full group photos and history transfer**
- **A (recommended): Separate later specs with hard byte, airtime and consent budgets.** Keeps this release bounded.
- **B: Expand this release after GC8/GC5 picks.** Requires re-estimation, extended chunking/repair tests and another owner gate.

## 9. Sources and verification limits

### Read repository sources

- `docs/specs/2026-10-14-accounts-design.md:14-31,52-74,433-471`: owner decisions, receipt/retry policy, deferred work and review constraints.
- `docs/design.md:3,37-56,74-85`: supersession, legacy text cap, no audio transport and explicit confirmation.
- `android/core/src/main/kotlin/org/phonemesh/core/wire/WireV2.kt:7-60`: actual outer packet format and cap.
- `android/core/src/main/kotlin/org/phonemesh/core/wire/Bodies.kt:10-41,58-101,153-181`: body kinds, optional Ack and receipt size.
- `android/core/src/main/kotlin/org/phonemesh/core/session/Ratchet.kt:20-24,52-74,156-175`: windows, bounds, keyed-packet behaviour and code lookup.
- `android/core/src/main/kotlin/org/phonemesh/core/session/SessionState.kt:59-84`: serialized state sizing.
- `android/core/src/main/kotlin/org/phonemesh/core/mail/Postman.kt:15-20,38-74,92-125,138-165,201-238`: sending, durable handling, retries and receipts.
- `android/core/src/main/kotlin/org/phonemesh/core/PictureWire.kt:25-42,67-84,88-117,135-149`: picture limits and framing.
- `android/core/src/main/kotlin/org/phonemesh/core/LoraAirtime.kt:17-49` and `android/hub/src/main/kotlin/org/phonemesh/hub/Airtime.kt:7-38`: distinct direct-picture and broadcast estimates; duty-cycle accounting.
- `android/hub/src/main/kotlin/org/phonemesh/hub/Mailbox.kt:44-98,131-144`: code classification, deduplication, nonconsuming retrieval and disk overhead.
- `android/app/src/main/java/org/phonemesh/app/History.kt:12,442-447,801`, `AppMailStore.kt:60-69,135-151`, `AccountStore.kt:25-28,67-76`: chat kinds, persistence seams and Vault limits.
- `android/app/src/main/java/org/phonemesh/app/AppCore.kt:487,573,876,1758-1778,2396`, `NameActivity.kt:249` onward: channel routing, picture eligibility and colours.
- `android/app/src/main/res/values/strings.xml:19,42-45,68-73,276,323,464`; `android/gradle.properties:8-9`: shipped labels and APK ceilings.
- `docs/specs/2026-10-25-demo-build-design.md:14-46`: real-engine demo contract and its evidence limits.

### Opened external primary sources

- Signal, [The Double Ratchet Algorithm](https://signal.org/docs/specifications/doubleratchet/), sections 2.1-2.3: chain-key forward security and the fresh-DH requirement for recovery. Consulted for properties, not a claim that Phonemesh implements the latest post-quantum sections.
- WhatsApp, [Encryption Overview, technical whitepaper](https://www.whatsapp.com/security/WhatsApp-Security-Whitepaper.pdf), version 9, February 25, 2026, “Group Messages”, pp. 17-18: pairwise sender-key distribution, signatures, one ciphertext and reset on departure.

### Executed calculation and remaining unknowns

`python3 calc/2026-10-26-private-group-chats-calc.py` completed successfully. It computed all four group sizes, text/receipt tables, key-distribution/removal counts, mailbox byte totals, session storage and picture cases. These are source-grounded estimates, not live mesh benchmarks or implementation tests. Full packet protobuf sizing, signed APK growth, low-end-phone performance, complete cryptographic review and field capacity remain UNKNOWN.
