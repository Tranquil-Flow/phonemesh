# Private group keys, draft GK3 (decisions 47 and 66)

Status: DRAFT. Independent crypto review (decisions 41 and 47) OPEN: an agent review (lanes A-D) is done and its
group findings are fixed below; a human review is still needed. Not used by the app yet. Code:
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
- **Member list**, sent once to everyone as group packets on the admin's new chain. Bytes: version 2 | name length |
  UTF-8 name (1-24 bytes) | colour RGB | count (2-20) | count x (flags: 1 admin, 0 member | IK33 | SK33). Each packet
  body: op 2 | index | count | up to 136 list bytes. Twenty people take 11 packets. App messages are op 1.
- **Digest**, chained to the list before: first 16 bytes of SHA-256(`"phonemesh group roster v2"` || group ||
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
- Nobody gets earlier messages ("New members see recent messages" stays off). Each phone keeps only the chains derived
  from a secret, not the secret.
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

## Review findings

From the agent review (lane C), all in core with tests and mutants:

- C1: signature sealed inside the AEAD, by a group signing key.
- C2: epoch step of exactly one, parent digest, admin only, waiting keys dropped after 72 hours.
- C3: chained digests, conflict state, settled by an admin, safety codes.
- C4: removal notice, resend request, first messages kept until the list, old-epoch reading for members only.
- C5: low S only. C6: own codes asked for. C7: secrets out of message storage and wiped. C8: saved state.
- C9: keys on the curve only. C10: window steps cached.

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
6. **Not designed yet:** receipts, group photo, full pictures, screens.
