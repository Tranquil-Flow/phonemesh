# Private group keys, draft GK4 (decisions 47, 66, 69, 72, 74)

Status: DRAFT. An agent review (lanes A-D) of GK3 is done and its group findings are fixed below; GK4 adds what the
app needs (section "GK4 additions") and gets a fresh agent review before v0.1.2 (decision 76). A human review is
skipped for now (decision 68). Code:
`android/core/src/main/kotlin/org/phonemesh/core/group/`. Vectors: `android/core/src/test/resources/group/`, made by
an independent Python implementation written from this note (Python also verified a packet the Kotlin made).

## Owner decision 47 (from the radio airtime review)

One shared group secret renewed on each join/leave, per-sender chains derived from it, every message signed,
20 members initially. P-256 on the existing platform path; raw 64-byte `r || s` signatures.

## Key schedule

- Group id: 16 random bytes. Epoch: u32, starts at 1, never reused for a different secret.
- Epoch secret `S`: 32 random bytes, chosen by an admin, sent to each member over that member's own pairwise
  session.
- Sender chain start: `CK0 = HKDF-SHA256(salt = "phonemesh group v1" || group || u32(epoch), ikm = S,
  info = "phonemesh group chain" || IK33(sender), 32)`.
- Hub chain start (GK4): the same with info `"phonemesh group hub chain" || IK33(sender)`. Each member has this second
  chain for what goes only through a hub, so people who only hear the radio never see gaps in the radio chain.
- Step at position n: `M = HKDF-Expand(PRK = HMAC(CK_n, 0x01), "phonemesh group message", 52)`;
  key = M[0:32], nonce = M[32:44], code = M[44:52]; `CK_{n+1} = HMAC(CK_n, 0x02)`. Each key/nonce used once.

## Packet (one radio packet)

`0x14 | code(8)` (an ordinary WireV2 plain header, so hubs store and serve it by code like any private mail)
`| AES-256-GCM(key, nonce, aad = header, body || sig(64))`.

Sign, then encrypt. The signature is ECDSA P-256 / SHA-256, low S only, by the sender's group signing key over
`"phonemesh group sig v2" || group || u32(epoch) || u32(seq) || code || IK33(sender) || body`.
It travels inside the sealed part, so only people holding the epoch key see it (review C1).

Largest body: 228 - 9 - 16 - 64 = **139 bytes**. Nothing on air names the group or the sender. A member recognises
a packet by its expected code.

## Reading

Per sender: chain at the next unread position, look-ahead window 20, at most 64 kept keys of missed packets. A packet
moves the chain only after its tag and signature check, so a forged code cannot make a reader skip or forget keys.
The window's steps are worked out once per move of the chain (review C10).

## Signing keys (decision 66)

The owner chose deniability toward outsiders, so group packets are not signed by identity keys. A signature by an
identity key, or by a key that the identity key certifies, proves authorship to anyone. Instead:

- Each member makes one P-256 **group signing key** per group. Invitation: the admin sends its signing key to the
  invitee over their private session (body kind 11: version 1 | group 16 | key 33); the invitee answers the same way.
  Private sessions are deniable (X3DH), so this binding convinces the admin and nobody else.
- The member list carries each member's signing key. A reader takes the key from the list entry of the chain's owner.
- Leaked messages and lists carry only signatures by keys bound to nobody outside the group.
- Trade-off: an admin who lies in the list could speak as another member to the others. Admins are already trusted
  with the member list; members can compare safety codes (below).

## Members, admins and epochs

- **Group key** (body kind 10, 119 bytes, receipted like a text): version 2 | group 16 | epoch u32 | secret 32 |
  list digest 16 | parent digest 16 | list part count | the admin's signing key 33.
- **Member list**, sent once to everyone as group packets on the admin's new chain. Bytes (GK4, version 3): version 3 |
  name length | UTF-8 name (1-24 bytes) | colour RGB | picture kind (0 letter, 1 emoji, 2 photo) | picture length |
  picture (emoji UTF-8 up to 32, or the photo's 8-byte id) | flags (1: new members see recent messages, else 0) |
  count (2-20) | count x (flags: 1 admin, 0 member | IK33 | SK33). Each packet body: op 2 | index | count | up to 136
  list bytes. Twenty people take 11 packets.
- **Digest**, chained to the list before: first 16 bytes of SHA-256(`"phonemesh group roster v3"` || group ||
  u32(epoch) || parent digest || list bytes). The first epoch's parent is 16 zero bytes.
- **Safety code**: first 8 bytes of SHA-256(`"phonemesh group safety v1"` || group || u32(epoch) || digest). Equal
  codes mean the same list.
- A phone takes a new epoch only when:
  - the key is for exactly the next epoch and names the list it holds as parent (review C2);
  - it came from an admin of that list (or the phone has no list yet: an invitation);
  - the rebuilt list matches the digest;
  - the sender is an admin of the new list under the signing key its key named;
  - the phone is on the new list with its own signing key.
- One waiting key per admin. A key whose list does not arrive within 72 hours is dropped (review C2). While a newer
  key waits, the phone stops sending in the old epoch. It can ask that admin to send the key and list again (body kind
  13: version 1 | group 16 | epoch u32). The admin keeps what it sent for 72 hours and answers only people on that list.
- The admin's first messages in a new epoch may arrive before the list: up to 32 are kept and shown once the list
  checks.
- Members of the new list may still write in the old epoch for 72 hours. Their packets are read and marked as old.
  Packets from people no longer on the list are not read.
- Nobody gets earlier messages unless an admin turns on "New members see recent messages" (GK4, decision 74). Each
  phone keeps only the chains derived from a secret and a tag of it, not the secret.
- Cost of one change for N people: N-1 private packets plus at most 11 group packets.

### Two admins at once (review C3)

Two admins may start the same next epoch with different lists. A phone that has checked both lists for one epoch
(each from an admin of the list before, each complete and matching its key) goes into **conflict**: it stops sending
and the app tells the person. Any admin of the list it holds settles it by starting the next epoch, built on either
list. A rival key whose list never arrives, or does not check, causes no conflict.

### Removal (review C4)

The admin sends each person dropped from the list a notice over their private session (body kind 12: version 1 |
group 16 | the new epoch u32), with no secret. A phone takes it only from an admin of the list it holds, for the next
epoch, and then stops sending and reading. A phone that receives a list without itself leaves the same way.

## Storage and sending (reviews C6-C8)

- The mail layer hands a group body to the group side before anything is stored, and stores only its kind, never its
  bytes (review C7). The group side must store it durably before it says yes; a refused body is not receipted. A
  group body is never held as plaintext waiting for a session.
- After a list is taken, the waiting key's secret is wiped and the saved state holds only chains. The admin keeps the
  key it sent for 72 hours, for resending, then wipes it.
- Every change to a phone's group state is saved (`GroupMember.encode`) before anything leaves the phone, so a
  restart never reuses a chain position (review C8).
- A phone asks hubs for its own next codes together with everyone else's, so a hub cannot name a sender by which
  codes their phone did not ask for (review C6).

## GK4 additions (v0.1.2)

- **Ops**, the first byte of every group packet body: 1 message (an app body, the same bytes as a private one),
  2 list part, 3 profile (the member's own profile bytes, as cards carry them; decision 72), 4 receipt, 5 shared
  recent message (decision 74), 6 member photo piece, 7 group photo piece. Lists come only from a waiting key's
  admin. Other ops are read and handed to the app; unknown ops are dropped.
- **Receipt** (op 4, radio chain): count | count x (sender's index in the epoch's list | highest position read u32 |
  missing u32, bit i set when position highest - 1 - i was not read). At most 15 entries. A phone sends one after
  reading other people's packets (never for receipts alone), with the senders it read since the last one first.
  A sender counts a member as having its packet at position n when that member's entry covers n: at or below the
  highest, and within the 32 below it, not missing. "Delivered to N of M": M is the other members at sending time.
- **Shared** (op 5, hub chain only): author account id 8 | time in seconds u32 | app body (up to 126 bytes). An admin
  who adds people to a group with the flag on shares the last 7 days, at most 50 messages, after the new list. Only
  a phone that is new on that list shows them, as shared by that admin.
- **Pictures**: a message picture is its head on the radio chain (the colour sketch, one packet) and its parts on the
  hub chain, 133 picture bytes each. A member photo (decision 64) goes wholly on the hub chain; the group photo's head
  goes on the radio chain and its parts on the hub chain (decision 69).
- **Invitation** (private body kind 14): version 1 | group 16 | the admin's signing key 33 | people invited (2-20) |
  name length | name | RGB | picture kind | picture length | picture. Shown with Join and Ignore. Join sends the
  signing key back (kind 11). A phone takes its first key only from the admin who invited it.
- **Leaving** (private body kind 15): version 1 | group 16 | epoch u32, from a member of the list held, to one admin:
  the first admin on the list other than the sender, so only one admin acts. The leaver stops at once. The last admin
  first starts an epoch making the chosen person an admin, then leaves the same way (decision 72).
- **Who must know whom**: keys, removal notices and leaving notices go over private sessions. So an admin starts an
  epoch only when every other member is its contact; otherwise the change is refused and the screens ask the admin to
  add that person first. Ordinary members need not know each other. A removal notice goes only to a removed person the
  admin has as a contact; one it does not know finds out when keys stop coming. A leaving member tells the first admin
  on the list it has as a contact.
- **Batching**: an admin gathers joins and leavings for 60 seconds into one epoch (each epoch costs a private key packet
  per member plus the list). A phone that has waited 10 minutes for a list asks that admin once to send it again.
- **One list, two secrets**: each phone keeps a tag of the epoch secret (first 16 bytes of SHA-256(`"phonemesh group
  secret tag v1"` || group || u32(epoch) || S)). A checked key for the epoch held with the same list but another
  secret is a conflict too, since its holders could not read each other.

## Review findings

From the agent review (lane C), all in core with tests and mutants:

- C1: signature sealed inside the AEAD, by a group signing key.
- C2: epoch step of exactly one, parent digest, admin only, waiting keys dropped after 72 hours.
- C3: chained digests, conflict state, settled by an admin, safety codes.
- C4: removal notice, resend request, first messages kept until the list, old-epoch reading for members only.
- C5: low S only. C6: own codes asked for. C7: secrets out of message storage and wiped. C8: saved state.
- C9: keys on the curve only. C10: window steps cached.

## Review G9 (storage, transport and engine): rules fixed and limits kept

Lane G9 reviewed the engine and the app around it (owner decision 76). Every finding is fixed, or kept here as a limit
with its reason. Rules the draft now states:

- **A signing key is new to the group (G9-1).** A Join reply (`Signer`) whose key equals this phone's own signing key,
  a listed member's key, or the key another person already gave is refused. The admin's epoch for the other joiners
  still goes. If a duplicate ever reached the stored keys, the batch drops that joiner and never throws.
- **Rival keys are believed for 6 hours (G9-4).** A key for the epoch held, from an admin of the list before, counts
  as a second list only within 6 hours of taking that epoch. After that a removed admin's rival is refused like any
  key. Inside the window the group still stops at a conflict until an admin fixes it (two admins acting at once is
  the case the rule exists for). The time the epoch was taken is saved with the member state.
- **Photo pieces are checked on reading (G9-3).** A member photo or group photo piece must decode as a whole group-cut
  piece (`PictureWire.decodeGroup`); anything else is dropped before it reaches storage. Joining a photo uses the
  cut it was made with (133-byte parts for groups); the part size is never defaulted.
- **Keys go to contacts only (G9-7).** An epoch that would need a key for someone who is no longer a contact does not
  start (a new member joining, a leaving, or the last admin handing over); the admin is told by a line in the group
  chat, and the epoch goes at the next batch once every key has a way. A key already queued for a contact is kept
  (not dropped) until its 72-hour horizon, then let go with a note.
- **Invitations (G9-8).** The first invitation for a group id stays; a later one from anyone is ignored. One contact
  may leave at most 4, all together at most 16, and any invitation expires after 30 days.
- **Recent messages (G9-9).** Read from an admin's hub chain, by a phone that joined in the epoch they were sent in;
  they are still read while the next epoch is new (the old hub readers last 72 hours). At most 50 are taken per
  group, and a time later than now is shown as now.
- **Old state goes (G9-10).** Once an hour: finished outbox rows of no shown message after 7 days, queued private
  bodies and waiting hub-only packets after 72 hours (a key that never went is reported), and picture pieces that
  never completed after 72 hours (the picture then shows as lost).
- **An Ask is answered once per member per epoch (G9-11).** The answer carries the key and every list part over the
  private session, which retries by itself, so asking again for the same epoch adds nothing.
- **Ops keep to their chains (G9-12).** Messages and the group photo travel on either chain; profiles and receipts on
  the radio chain only; recent messages and member photos on the hub chain only. Sealing one elsewhere throws, and
  reading one from the wrong chain drops it.
- **Admin calls never throw (G9-13).** Removing or promoting your own account is refused; a list the engine refuses
  is a refusal.
- **Leaving (G9-14).** A Leave more than 4 epochs behind the epoch held is a stale notice and is not taken.
- **Packets for no group cost a look-up (G9-6).** The codes every group expects are kept in memory, refreshed after each
  change that group makes; a packet whose code no group expects opens no group.

Limits kept, with reasons:

- **G9-9 (1), recent messages are a reader-side rule.** Every member derives every member's hub chain from the epoch
  secret, so "only new members read the batch" is the reader's choice. A member with a modified client can read a
  batch meant for a later joiner. Sending the batch under a key given only to the new members would cost a key packet
  per joiner per batch and was not chosen. The admin still sends at most 50 messages from the last 7 days.
- **G9-9 (3), author and time of a shared message are the admin's word.** They are not signed by the author. The
  time is capped at now and the count at 50, and the line is shown as shared by that admin. Signing each shared
  message by its author would not fit a group packet next to the 126-byte body.
- **G9-9 (4), a message longer than 126 bytes is not shared.** The shared packet carries op, author and time too.
- **G9-4, no "ignore this admin" choice.** Inside the 6-hour window a rival key still stops sending until an admin
  fixes the group. Naming the rival admin and offering to ignore them was not built: it would let a member decide
  alone which of two admins is right.
- **G9-7, retries run with the postman.** The epoch of people who joined while a contact was missing goes again at
  the next postman run after the contact exists, not at a fixed time.
- **G9-11, one answer per epoch.** A member whose answer was lost with its private session waits for the next epoch
  (or asks another admin); the manual "Ask again" button sends the request but is answered once.
- **G9-14, last admin of a two-person group.** The leaving admin has nobody to hand over to; the other person keeps a
  group with no admin and nobody is told. A group of two is better left by both. Likewise a leaving member tells one
  admin it has a contact for; with none, nothing is sent.
- **G9-14, `Removed` is final.** A removal notice sent wrongly by one side of two admins acting at once cannot be
  undone without a new invitation.
- **G9-15, receipts and shared authors are claims.** "Delivered to N of M" counts what members say they read; a member
  can claim to have read everything, and a forged `missing` mask makes the sender air a row again, at most 3 times
  per row. Profile names are not unique inside a group.

## Questions for the reviewer

1. **Deniability:** decision 66 above. Review the admin-relayed signing keys and the admin trade-off.
2. **Forward secrecy inside an epoch:** a member keeping `S` can rederive every chain. Phones derive the chains and
   erase `S`; a malicious member can still keep it. Renewal on join/leave heals.
3. **Conflicts:** a co-admin can keep starting rival epochs. Settling needs an admin who keeps the others. Is
   stopping sending (rather than picking a winner by digest) the right failure for this threat model?
4. **Different lists to different members:** an admin can still do this; the chained digest and safety codes catch
   it only when phones compare.
5. **Codes:** an 8-byte code at 20 members x window 20 means about 400 expected codes per phone plus pairwise ones.
   Is the collision rate acceptable?
6. **GK4:** receipts, hub chains, shared recent messages, invitations and leaving (section above).
