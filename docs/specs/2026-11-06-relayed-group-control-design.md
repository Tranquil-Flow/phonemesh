# Relayed group control (v0.1.3 G1): design for review

Status: **construction chosen; being built (only G3a, the envelope body, so far).** Source-based design for the v0.1.3 G1 gate
(`docs/specs/2026-11-04-v0.1.3-release-plan.md`, section G). The owner answered D1a, D2a, D3a and D5a on 2026-11-06
(accounts decision 79): R3 with K2 below, with the D1 trust change; and D4a the same day (decision 80): envelopes only
where needed, no padding, the visible metadata told plainly. So the ops below are pinned as written. No code, wire byte
or default changes with this note.

Source reviewed (identical in the prepared v0.1.2 candidate `10492845` and the v0.1.3 branch; `git diff --stat
10492845 HEAD` over `core/group`, `core/mail` and `core/wire` is empty): `android/core/src/main/kotlin/org/phonemesh/core/
group/{GroupPost,GroupMembers,GroupCipher,GroupChains}.kt`, `core/mail/Postman.kt`, `core/wire/{Bodies,WireV2}.kt`,
`core/crypto/P256.kt`, `android/app/.../AppGroupStore.kt`, the key draft `2026-10-27-private-group-keys-draft.md` (GK4 and
review G9) and accounts decisions 47, 66, 72, 74, 75 and 77.

## The problem, from the source

- An epoch key (`GroupKey`, private body kind 10, 119 bytes) goes only over the admin's pairwise session with each
  member (`GroupPost.startEpoch` → `Change.private`). `GroupPost.unreachable` refuses an epoch when any other member is
  not the admin's contact (`CannotReach`, review G9-7), and the receiving side takes a private group body only from a
  contact (`takePrivate`: `store.identityOf(from) ?: return false`).
- So a promoted admin who has not added every member cannot remove anyone, add anyone, restyle the group, promote
  someone or hand over as the last admin (`edit`, `addJoined`, the leaving batch in `tick` and the last admin's
  `leave` all refuse an unreachable member). The test
  `GroupPostTest#anAdminChangesTheGroupOnlyWhenEveryMemberIsAContact` pins today's refusal. Decision 77 kept this rule
  for v0.1.2 and named relayed delivery as later work.
- The same dependence holds for the other private bodies: a resend request (`Ask`, kind 13) goes to the waiting key's
  admin, a leaving notice (`Leave`, kind 15) to the first admin the leaver has as a contact (none: nothing is sent), a
  removal notice (`Removed`, kind 12) only to a removed contact.
- Removing the contact check alone is unsafe: there would be no path for the key at all, and the obvious substitute,
  sending the new secret inside the group under the old epoch, gives it to the member being removed (they hold the
  old epoch's chains).

## What any answer must keep (plan G1)

1. A forwarding peer, the radio mesh or a hub is transport, never authority. Nobody outside the new list learns the new
   secret. A member gains no rights beyond its place on the list.
2. The replacement secret is never protected by the old epoch alone.
3. The key a member takes is authenticated as coming from an admin of the list it holds, independently of who carried
   the bytes; the existing epoch rules still decide (`GroupMember.receiveKey`: exactly the next epoch, parent = the list
   held, one waiting key per admin, conflicts, rival window).
4. Join stays an explicit choice; invitations still go only to the inviting admin's contacts. No personal contact is
   created automatically.
5. Outsider deniability (decision 66): nothing new is signed by an identity key or by a key an identity key certifies.
6. State is saved before anything leaves (review C8); no chain position or nonce is used twice.
7. Mixed v0.1.2/v0.1.3 groups never stall silently and never fall back to something weaker.

## Candidates compared

**R0, today (admins know every member).** Safe and reviewed; fails the v0.1.3 goal. Kept as the behaviour whenever the
admin has a contact for the member, and as the refusal when no other way is allowed.

**R1, forward through a member who knows both.** The admin signs the `GroupKey` with its group signing key and sends it to
a member M who has the recipient X as a contact; M forwards it over M-X's session.
- Works only when the admin can find such an M, which needs members' contact lists (a social-graph disclosure the group
  does not have today) or asking everyone.
- M learns the new secret: acceptable only if M is on the new list, and M must be online and honest about forwarding.
- A signed key is 119 + 33 + 64 bytes plus the kind byte (217), over one private packet's 203-byte body
  (`WireV2.maxBody(false)` = 228 - 9 - 16), so it needs a new two-packet body.
- A v0.1.2 Postman neither consumes nor acknowledges an unknown private kind (`Body.decodeWithAck` fails and
  `Postman.receiveOne` returns before `store.keep`), so a new kind must never reach a v0.1.2 relay. Capability must be
  known first.
- Rejected as the main path: liveness and graph disclosure without a security gain over R3.

**R2, the new secret inside the group under the old epoch.** Rejected outright: the removed member reads it (rule 2).

**R3, a sealed envelope for one member, carried by the group (proposed).** The admin encrypts the new epoch's key to a
group-only public key of the recipient, and sends the result as an ordinary group packet on its own radio chain of the
epoch the recipient holds. The radio mesh and hubs carry it like any group packet; the old epoch only hides it from
outsiders, while the envelope keeps it from other members. Details below.

**R4, group-scoped pairwise sessions** (X3DH and a ratchet between group keys, no contacts). Gives forward secrecy, but
doubles the session machinery and its stored state for rare control traffic. Not proposed now; R3 can move to it later
without changing the authority rules.

Recipient key for R3:
- **K1, reuse the member's group signing key for ECDH.** It is a software P-256 scalar sealed by the Vault
  (`AppGroupStore.newSigner`), so it would work technically, and every roster already lists it. Rejected: it uses one
  key for both signing and key agreement, which would need its own analysis, and it gives no signal of which phones can
  open an envelope (a v0.1.2 member would just never get the key).
- **K2, a separate group box key per member (proposed).** Each v0.1.3 member makes a P-256 key pair per group, kept like
  the signing key (sealed by the Vault, never in a backup), and announces the public half in the group. The
  announcement is signed with the member's group signing key inside the packet, so it is bound to that member's list
  entry and to nobody outside. It also tells admins that this phone can open envelopes.

## Proposed construction (R3 with K2)

### New group ops (group packet bodies; v0.1.2 readers drop them)

A v0.1.2 reader consumes the chain position and drops any op above 7 without a receipt or an event
(`GroupMember.message`: `op !in MESSAGE..LAST` → nothing; `took` stays true so the position is saved). So the ops
below cost a v0.1.2 member one unread packet each and change nothing else. All go on the radio chain (they are also
handed to hubs like every radio-chain packet). Version byte 1 in each.

| Op | Name | Body after the op byte | Sent by | Taken from |
|---|---|---|---|---|
| 8 | Envelope | version 1, ephemeral key 33, sealed key 85 (= 120 bytes with op) | an admin, before it takes the new epoch | an admin of the list held |
| 9 | Box key | version 1, box key 33 (35 bytes) | any member | the chain's owner, on the list held |
| 10 | Ask | version 1, admin index 1, epoch u32 (7 bytes) | a member waiting for a list | a member of the issued list, for that admin |
| 11 | Leave | version 1, admin index 1, epoch u32 (7 bytes) | a leaving member | a member of the list held, for that admin |

Largest body 120 of 139 bytes (`GroupCipher.MAX_BODY`); the envelope packet is 9 + 120 + 64 + 16 = 209 bytes on air.

### The envelope

For recipient X with box key B (the newest one X announced in the list held) and admin A starting epoch e + 1 from
epoch e:

- A makes a fresh ephemeral P-256 pair (E, eph) per envelope and wipes the private half after sealing.
- `z = ECDH(eph, B)` (the 32-byte x coordinate, `P256.agree`).
- `key || nonce = HKDF-SHA256(salt = "phonemesh group envelope v1" || group || u32(e + 1), ikm = z,
  info = IK33(A) || IK33(X) || B || E, 44)`.
- Sealed key = AES-256-GCM(key, nonce, aad = op || version || E, u32(e + 1) || secret 32 || list digest 16 ||
  parent digest 16 || list part count 1). The group id and A's signing key are not sent: X takes them from the group
  and from A's entry in the list X holds, exactly the `GroupKey` fields they replace.
- A seals the envelope packets on its radio chain of epoch e **before** it takes epoch e + 1, in the same `Change` that
  saves the new state, the list packets and the private keys for its contacts. Then it takes the epoch as today.

X opens it like this:

1. The packet opened on A's chain of epoch e, so the group signature (sealed inside, by A's group signing key from the
   list X holds) already shows it is A's. A relaying member, the radio mesh or a hub cannot change it, and a packet
   copied onto another admin's chain fails the HKDF binding to IK33(A).
2. A is an admin of the list X holds; otherwise the envelope is dropped.
3. X tries its current box key, then the previous one while kept (no recipient field: trial opening, at most one ECDH
   per envelope per kept key). A failed tag is "not for me" and is dropped silently.
4. The opened fields become a `GroupKey` (group, epoch, secret, digests, parts, A's signing key) handed to
   `receiveKey(from = A, …)`. Every existing rule then applies unchanged: next epoch only, parent = the list held, one
   waiting key per admin, the 72-hour waiting limit, conflicts and the 6-hour rival window. The list itself still
   comes as list packets on A's new chain and must match the digest.
5. The same key by the private path and by an envelope is the same pending key (`receiveKey` returns true and changes
   nothing). A replayed packet is refused by the chain reader (a position opens once).

What this gives, against the rules above:
- Rule 1: the bytes may come from anyone; the sender is the chain owner proven by its group signature. Nonmembers see
  only opaque packets, as today.
- Rule 2: the secret is sealed to X's box key. A member being removed opens the outer packet and sees an ephemeral key
  and ciphertext only.
- Rule 3: authority is A's place on X's list plus A's group signature; the forwarding peer plays no part.
- Rule 5: box keys and envelopes involve no identity-key signature; announcements are signed by group signing keys,
  bound to nobody outside the group.
- Rule 6: envelopes use A's old chain positions, saved in the same transaction; the old writer is then gone, so no
  position is reused after a restart. Each envelope has its own ephemeral key, so each AES key and nonce is used once.

Built as G3a (2026-11-06, host JVM tests only): `core/group/GroupEnvelope.kt` seals and opens one op 8 body exactly
as above: a fresh ephemeral key per envelope, trial opening under the kept box keys newest first, the inner epoch
compared with the bound one, and anything malformed opening to nothing rather than an exception. Its bytes match a
second implementation written from this note on another crypto library (same author, so not an independent review).
Nothing sends or reads op 8 yet. Box keys and their announcements (built as G3b below), which path an admin uses, chain
positions, retries, Ask and saved state are later slices, and the chain-origin, admin-on-the-list and `receiveKey`
checks stay with the caller.

Built as G3b (2026-11-12, host JVM tests only): `core/group/GroupBoxKey.kt` builds and reads one op 9 body (35 bytes:
op 9, version 1, the 33-byte compressed public key) and `core/group/GroupBoxKeys.kt` keeps, per member of the list
held, the newest key that member announced on its own chain. The body names no owner, so op 9 carries no cryptography
of its own: the announcing member is the chain the body travels on. A key belongs to one member, and the claim survives
that member rotating the key or leaving the list, because a member that left still holds the private half. "Newest" is
the chain position, not the order of arrival, because a reader may open a later position before an earlier one inside
its reading window. Seventeen mutants are each caught by their own test; the first round's survivors found three missing
tests and the arrival-order fault. Nothing sent or read op 9 when this was built; G3d below reads it. The private half is
not made or kept by the Vault yet, and the app's behaviour and wire traffic are unchanged.

### Which path an admin uses

For each other member of the next list:
1. a contact → the private `GroupKey` as today (forward-secret pairwise session; works with v0.1.2 members);
2. otherwise, a box key announced on the list held → an envelope;
3. otherwise → the change does not start, and the admin is told who is missing, as `CannotReach` does now. Nothing is
   sent to anyone (no partial change).

New joiners are always the inviting admin's contacts (invitations go privately), so their first key stays private.
Removal notices still go privately to removed contacts; a removed non-contact finds out when keys stop coming (as today).

Built as G3c (2026-11-12, host JVM tests only): `core/group/GroupReach.kt` decides that path for each other member of
the next list: a contact over the private session as today, otherwise in an envelope sealed to the box key that member
announced on the list held, otherwise not at all. One member that cannot be reached stops the whole change, because a
change reaching some members and not others would leave the group half in one epoch and half in another. The admin's own
key is not part of this: it holds it itself. Eleven tests were seen failing first against a stub that makes every member
unreachable (5 failed) and then passing, and twelve mutants of the path rules are each caught by their own test; the one
survivor found the last missing test, that the box key handed out must be a copy. The plan is computed but not acted on
yet: no envelope is sent, the admin's old chain positions and the retries are not built, and the app's behaviour and
wire traffic are unchanged.

### Box keys

- A v0.1.3 member makes its box key when it joins, or at its first change after an upgrade for groups it already
  holds, and announces it with its next profile (`profileDue`), i.e. one radio packet.
- After an epoch reached it by envelope, the member makes a new box key and announces it in the new epoch: this is also
  the admin's sign that the envelope arrived. The previous private half is kept 72 hours (an admin may have sealed to
  it), then wiped.
- Every v0.1.3 member (any member may be promoted later) keeps, per member of the list held, the newest box key read from
  that member's own chain; it reads members' chains of the epoch held and, for 72 hours, of the one before. The key is
  dropped when its member leaves the list. A box key from anyone else's chain, or a duplicate of another member's, is
  ignored.
- Box keys, like signing keys and group state, are never in a chat backup (decision 75): a restored phone is not back in
  its groups.

Built as G3d (2026-11-12, host JVM tests only): a member announces its box key on the radio chain it writes on, so the
announcement is bound to that member by the group signature every packet carries, and another member reads it there and
keeps it as that member's key. Op 9 is not something people say: it is shown to nobody and receipted by nobody, and it
travels on the radio chain only. The key of a member that leaves the list is dropped, and the claim stays, so nobody else
may take it. The keys are saved with the group state: the extension is version 2, a v0.1.2 save (version 1) loads with no
keys, and a key of a member that is not on the list held is not loaded. Thirteen announcement tests were seen failing
first against a stub that keeps no key (5 failed; the refusals that passed were those a refuse-everything stub passes)
and then passing. Twenty mutants of the reading, the ownership rules and the saving are each caught by their own test;
the first round's five survivors found three missing tests (a forged op 9 on the hub chain, an unknown extension
version, and a claimed key after a restart) and one design fault, that the list check was written twice. The prepared
v0.1.2 candidate core (its group core unchanged since `10492845`) reads a v0.1.3 announcement as it must: no event, no
receipt, and the position consumed. Not yet wired into `GroupPost`: the private half is not made or kept by the Vault, no
announcement is sent, the app's behaviour and wire traffic are unchanged.

Built as G3e (2026-11-13, host JVM tests only): a member reads an envelope on the admin's radio chain and takes the key.
The packet opened on the admin's chain, so the group signature shows it is the admin's, and the admin is checked against
the list held, not a name in the body (there is none). The body is opened with this phone's own box keys, newest first:
the current one, then the one before it while it is kept, because an admin may have sealed to it before this phone
changed it; a key that does not open is not this phone's and is dropped without a word. What opens becomes a key that
goes through the unchanged `receiveKey` rules, so next epoch only, parent = the list held, one waiting key per admin,
the 72-hour waiting limit, conflicts and the 6-hour rival window. An envelope is shown to nobody and receipted by
nobody, like a box key announcement; the admin learns it arrived from the member's next box key, not from a receipt.
Thirteen envelope tests were seen failing first against a stub that opens no envelope (4 failed; the refusals that
passed were those a refuse-everything stub passes) and then passing. Twenty mutants of the reading, the ownership
rules and the kept keys are each caught by their own test; the first round's four survivors found three missing tests (a
member that is no longer an admin, the keep window's last moment, and the sender's own entry in the list) and one
mutant that could not be told apart from the right code, because the group is bound by the AEAD itself. The prepared
v0.1.2 candidate core (its group core unchanged since `10492845`) reads a v0.1.3 envelope as it must: no event, no
receipt, and the position consumed. Not yet wired into `GroupPost`: the private half is not made or kept by the Vault,
no envelope is sent, the app's behaviour and wire traffic are unchanged.

Built as G3f (2026-11-13, host JVM tests only): the admin seals the envelope. An admin that is not a member's
contact seals the new epoch's key to the box key that member announced on the list held, and sends it as a group
packet on its own radio chain of the epoch the member holds, before it takes the new epoch: the member reads it on the
chain it already reads, and a message sealed after the epoch is taken rides the new chain, which the member cannot
read yet. The key is sealed to the box key the admin read on that member's own chain, bound to that member by the
group signature every packet carries; the admin cannot be told to seal it to any other key. A member reached by an
envelope gets no private key: each is reached one way or the other. Each envelope has its own ephemeral key, so each
AES key and nonce is used once. A member that announced no box key, a member that is not on the next list, the admin
itself, a second envelope to the same member, and an admin that hands the group over and leaves are each refused.
Twelve tests were seen failing first against a stub that seals no envelope (6 failed; the refusals that passed were
those a refuse-everything stub passes) and then passing. Fourteen mutants of the sealing, the ownership rules and the
kept keys are each caught by their own test; the first round's four survivors found one gap in the test (the envelope
body is inside the chain packet, so the ephemeral key was never visible), two rules masked by a later check (a member
with no box key, and the admin, which never holds a box key for itself), and one mutant that was a tautology. The
prepared v0.1.2 candidate core (its group core unchanged since `10492845`) reads the envelope the v0.1.3 admin sealed
through the real producer path as it must: no event, no receipt, and the position consumed. Not yet wired into
`GroupPost`: the private half is not made or kept by the Vault, no envelope is sent, and the app's behaviour and wire
traffic are unchanged.

### Asking again, retries and limits

- **Admin side.** The envelopes join the list packets in the admin's `Issued` copy of the epoch (already kept 72 hours,
  `KEEP_MS`). For a recipient whose new box key has not appeared, the admin airs that recipient's envelope and the list
  again at most twice (after 10 and 60 minutes), then stops. Hubs keep the packets by code for their mail period, so a
  member that reaches a hub catches up without a resend.
- **Member side.** A member that holds an envelope key but no list after 10 minutes (`ASK_AFTER_MS`) and does not have
  that admin as a contact sends op 10 on its own chain of the epoch it holds (sealing it is allowed while app sends are
  paused). The admin answers each member once per epoch (review G9-11) by airing that member's envelope and the list
  again, only while it keeps the epoch's copy.
- **Reading window.** A reader looks 20 positions ahead (`GroupReader.WINDOW`). An admin that sends many envelopes in one
  change may push a lagging member's envelope past that window until the member reads the earlier packets (a hub
  serves them). The admin seals envelopes first in the change and at most 19 exist (20 members).
- **Leaving.** A leaving member with no admin as a contact sends op 11 naming one admin (the first admin on the list that
  announced a box key), then stops. Only the named admin acts, read from the leaver's old chain while the 72-hour old
  reading lasts; the Leave epoch rules (review G9-14) apply. A leaver that has an admin as a contact still sends the
  private `Leave`, so a v0.1.2 admin hears it.

## Mixed versions

- **v0.1.2 admin, v0.1.3 members.** Unchanged behaviour: it uses private keys only and needs every member as a contact.
  It drops ops 8-11 (one unread packet per box-key announcement).
- **v0.1.3 promoted admin, v0.1.2 member it has not added.** That member announced no box key, so the change does not
  start and the admin is told to add them or that they need a newer app. No envelope is ever sent to a phone that
  cannot open it, and nothing weaker replaces it.
- **v0.1.2 member, envelopes for others.** It reads the admin's chain, consumes the envelope position and drops it. Its
  own key comes privately (it must be the admin's contact) or the change does not start.
- **Saved state.** `GroupMember.encode` gains an appended extension (box keys of members, own box key labels, envelopes
  in `Issued`), the same way v0.1.2 appended `EXT_VERSION` 1; a v0.1.2 save loads with no box keys. The list (version 3)
  and `GroupKey` (version 2) bytes do not change. Downgrading the app is not supported (same signer, no data clear).
- **Incompatibility shown truthfully.** The screens say who cannot be reached and why (not a contact; no newer app
  seen), never that a key was delivered when only an envelope was sent (G4 decides the wording).

## What is still trusted or exposed

- **The roster's signing-key binding (trust change, D1a, accepted).** A promoted admin gives the key to a member it
  never added, trusting the signing key the earlier admin bound to that member and the box key that member then signed.
  Today an admin only trusts its own contacts for this; ordinary members already trust admins' lists for everyone
  (decision 66 trade-off). An admin that lied in the list could already speak as that member and is already given
  every secret.
- **Metadata inside the group and on air (D4a, accepted, decision 80).** Envelopes go only where needed, so members of
  the old list, including one being removed, see how many envelopes an admin sent in a change, so how many members it
  has not added.
  There is no recipient field, but members of the new list see who announces a new box key after the change, which
  names every member the envelopes reached (D3a), and a member who asks with op 10 is named to the old list. The new ops
  have fixed sizes and group packets are not padded, so someone who can pick out one change's packets on air may count
  them. See "D4 trade-offs" below. No recipient anonymity or traffic-analysis resistance is claimed; these are
  inferences from the design, not observations of built code.
- **Forward secrecy.** A pairwise key is ratchet-protected; an envelope is protected by the recipient's box key until it
  is rotated after use and the old half wiped. A phone compromised before that wipe exposes the epochs sealed to it.
- **Airtime.** One radio packet per box-key announcement (at join/upgrade and after each envelope-delivered epoch), one
  per envelope instead of a private key packet, at most two re-airs per envelope recipient, small Ask/Leave packets.
  No numeric radio budget is claimed until measured.
- **Review.** New bytes and a new construction (ECDH + HKDF + AES-GCM, existing primitives only). Plan G5 requires a
  changed-protocol review with positive and negative controls; earlier v0.1.2 review does not cover it.

## Verification plan for G2 (after the decisions)

Deterministic core tests in `GroupPostTest`/`GroupMembersTest` style, written first and seen failing:
- Positive topology: original admin Ama knows everyone; Kofi is promoted and lacks Esi; Kofi removes Yaw, adds a
  contact of his, restyles, promotes Esi and hands over as last admin, each without adding Esi as a contact; Esi takes
  every epoch by envelope; Yaw (removed) holds no new chain and cannot open any envelope.
- Wrong origin: an envelope on a non-admin member's chain; on a co-admin's chain copied from another admin; from an
  admin removed in the epoch held. Wrong recipient: sealed to another member's box key. Wrong group/epoch/parent:
  each field changed, each refused by the existing `receiveKey` rule, not by a new one.
- Replay, reordering and loss: the same packet twice; envelope after the list; list never arriving (op 10 asked once);
  envelope past the reading window then caught up through a hub; admin restart between sealing and sending (no reused
  position); offline member returning inside and after 72 hours.
- Box keys: announcement from another member's chain ignored; rotation after use, previous key kept then wiped; never
  in a backup; v0.1.2 save loads.
- Mixed versions: packets made by v0.1.3 fed to the group core of the prepared v0.1.2 candidate (`10492845`) prove op 8-11
  are dropped without events or receipts; a v0.1.3 admin refuses to start a change needing an envelope for a member with
  no box key.
- Mutants for each refusal (origin check, recipient binding in HKDF, parent rule, box-key ownership, no-partial-change).
- Vectors from an independent implementation written from this note, as for GK3.

## Decisions for the owner

Answered 2026-11-06 (accounts decision 79): **D1a, D2a, D3a, D5a**. As answered, D3a rotates a box key after it has
been used for an envelope-delivered epoch, not after each chat message, and keeps the previous private half 72 hours
for envelopes still on their way; D5a lets no change start while any member lacks a valid route. **D4a** was answered
the same day (accounts decision 80). The options as they were put, recommended picks first:

- **D1, trust change.** (a) A promoted admin may give the new key to a member it has not added, trusting the list's
  signing-key binding and that member's signed box key. (b) Keep "admins know every member" (no relay; G is reduced to
  better refusal messages and the two-person fix).
- **D2, construction.** (a) R3 with K2: an envelope sealed to a separate group box key, carried by the group. (b) R1:
  forward through a member who knows both. (c) R4: group-scoped pairwise sessions.
- **D3, box-key rotation.** (a) Make one at join or upgrade, rotate after each use. (b) Rotate every epoch (one more radio
  packet per member per change). (c) Never rotate.
- **D4, envelope count visible to members.** (a) Accept: members of the old list see how many envelopes a change has.
  (b) Hide it: an envelope for every member in every change (one more radio packet per member per change).
- **D5, mixed versions.** (a) Envelopes only to phones that announced a box key; others need a contact as today, with a
  clear note. (b) Relayed changes only when every member's phone announced one.

### D4 trade-offs (2026-11-06; the owner then chose D4a, decision 80)

From this note and a small protocol model outside the repository (one change, at most one member who misses it, no
other traffic, no radio-layer metadata, no implementation); sizes from `GroupCipher` (9 + body + 64 + 16, no padding).

- **What gives the recipients away besides the count.** With D3a, a new box key announced in the new epoch names each
  member an envelope reached, to every member of the new list; the admin's retries are keyed on it. An op 10 Ask names
  the asker to every member of the old list, a removed one included. Envelope (209 B), box key (124 B) and Ask (96 B)
  sizes are fixed, so someone who can pick out one change on air may count announcements.
- **Dummy envelopes for the other members** hide the envelope count from the old list, but the announcements still give
  the count to anyone who can count them, and the names to the new list. They hide nothing from retained members.
- **A real envelope for every member with a box key** (contacts too), with every member who misses the change asking by
  op 10 and the admin re-airing all envelopes or none, is the only modelled way to hide who and how many from every
  member: everyone then announces a new box key after every change. It puts each contact's new key under its box key,
  which is weaker than the pairwise session until the key is rotated and wiped; and if contacts also still get the
  private key, counting those private packets can give the number back. Envelope-only keys for contacts would change
  "Which path an admin uses" rule 1, which is outside D1-D5 as answered.
- **Slots, if envelopes are padded.** One per member of the list being replaced, other than the admin, tells its
  holders nothing new. One per member staying tells a removed member how many stay. A fixed 19 hides the group's size
  on air but sends 19 envelopes for a group of three. Slot order is shuffled.
- **Cost of one change (arithmetic, not airtime).** 20 members, 2 without the admin as a contact: needed-only 32 packets,
  5,504 B; dummies 49, 9,057 B; a real envelope for everyone 49, 8,700 B (contacts by envelope only, 19 new box keys)
  or 66, 11,165 B (also privately). A member's worst re-airs: 24 packets needed-only, 60 when all envelopes are re-aired.
  Every reader of the old epoch checks one signature per envelope; a member with a box key tries up to two key
  agreements per envelope (4 versus 38 here). With 19 envelopes in a row, a reader that missed more than one earlier
  packet of the admin's must open an earlier envelope first (the window is 20). Radio time, CPU time and battery are
  not measured.

## Related G4 input (no trust change)

Two-person groups (review G9-14 limit): when the only admin of a two-person group leaves, today the other person keeps a
group with no admin and is not told. Proposed: the leaving admin sends the other person a `Leave` (privately when a
contact, else op 11 naming them), and that phone ends the group for itself (`LEFT`) with a note that the other person
left and the group has ended. A v0.1.2 receiver ignores the notice as now. This needs no new key path and can be
built and tested separately from D1-D5.

Built as G4a (2026-11-06), with two differences from the proposal above. The ending is its own state, `ENDED`, so the
conversation can say "This group has ended." rather than "You left the group."; it is stored after the earlier states,
so saved v0.1.2 states read the same. The notice goes only privately, to a contact; op 11 for a non-contact waits for
G2/G3. Either person's `Leave` ends a group of two, but only one that names the epoch the receiver holds, while no
newer key waits and while nobody invited may still join (the admin's next epoch then adds them without the leaver).
