# Private group keys, draft GK1 (decision 47)

Status: DRAFT. Independent crypto review (decisions 41 and 47) OPEN. Not used by the app yet. Code:
`android/core/src/main/kotlin/org/phonemesh/core/group/`. Vectors: `android/core/src/test/resources/group/`, made by
an independent Python implementation written from this note (Python also verified a packet the Kotlin made).

## Owner decision 47 (from the radio airtime review)

One shared group secret renewed on each join/leave, per-sender chains derived from it, every message signed,
20 members initially. P-256 on the existing platform path; raw 64-byte `r || s` signatures.

## Key schedule

- Group id: 16 random bytes. Epoch: u32, starts at 1, never reused for a different secret.
- Epoch secret `S`: 32 random bytes, chosen by an admin, sent to each member over that member's own pairwise
  session (control layer, not in GK1).
- Sender chain start: `CK0 = HKDF-SHA256(salt = "phonemesh group v1" || group || u32(epoch), ikm = S,
  info = "phonemesh group chain" || IK33(sender), 32)`.
- Step at position n: `M = HKDF-Expand(PRK = HMAC(CK_n, 0x01), "phonemesh group message", 52)`;
  key = M[0:32], nonce = M[32:44], code = M[44:52]; `CK_{n+1} = HMAC(CK_n, 0x02)`. Each key/nonce used once.

## Packet (one radio packet)

`0x14 | code(8)` (an ordinary WireV2 plain header, so hubs store and serve it by code like any private mail)
`| AES-256-GCM(key, nonce, aad = header, body) | sig(64)`.
Signature: ECDSA P-256 / SHA-256 by the sender's identity key over
`"phonemesh group sig v1" || group || u32(epoch) || u32(seq) || header || sealed`.
Largest body: 228 - 9 - 16 - 64 = **139 bytes** (the review's comparison layout estimated 105 text bytes).
Nothing on air names the group or sender; a member recognises a packet by its expected code.

## Reading

Per sender: chain at the next unread position, look-ahead window 20, at most 64 kept keys of missed packets. A packet
moves the chain only after its signature and tag check, so a forged code cannot make a reader skip or forget keys.
A writer must durably store `chainAfterNext()` before the packet leaves (no key reuse after a crash).

## Members, admins and epochs (GK2)

- Every epoch is started by an admin of the epoch before it (or by the creator). The admin sends each other member a
  70-byte key over their private session: version 1 | group id 16 | epoch u32 | secret 32 | roster digest 16 |
  roster part count. A person being removed gets no key. On the wire it is private body kind 10 (`GroupKeyBody`),
  receipted like a text; a phone without groups refuses it like an unknown body (no receipt, no session step).
- The member list goes once to everyone as signed group packets on the admin's new chain. Roster bytes: version 1 |
  name length | UTF-8 name (1-24 bytes) | colour RGB | count (2-20) | count x (flags: 1 admin, 0 member | IK33).
  Each packet body: op 2 | index | count | up to 136 roster bytes; 20 people take 6 packets. App messages are op 1.
- Digest: first 16 bytes of SHA-256("phonemesh group roster v1" || group || u32(epoch) || roster bytes).
- A phone takes a new epoch only when the key came from an admin of the list it has (or it has none yet), the epoch
  is newer, the rebuilt list matches the digest, the sender is an admin of the new list, and the phone is on it.
  Otherwise it keeps its epoch; a phone that finds itself off the list stops sending and reading new messages.
- A new member gets no earlier messages ("New members see recent messages" stays off). Each phone keeps only the
  chains derived from a secret, not the secret.
- Cost of one change for N people: N-1 private packets plus at most 6 group packets.

## Signing keys (decision 66, not built yet)

The owner chose deniability toward outsiders, so group packets stop being signed by identity keys. A signature by an
identity key, or by a key that the identity key certifies, proves authorship to anyone. Instead:

- Each member makes one P-256 **group signing key** per group and sends its public half (33 bytes) to the inviting
  admin over their private session (new private body: group id 16 | key 33). Private sessions are deniable (X3DH),
  so this binding convinces the admin and nobody else.
- The roster carries each member's signing key: entry = flags | IK33 | SK33. Twenty people take about 11 roster
  packets instead of 6. The 70-byte group key that the admin sends each member privately gains the admin's own
  signing key (103 bytes, still one packet).
- Group packets are signed by the sender's group signing key, over the same bytes as before; a reader takes the key
  from the roster entry of the chain's owner.
- Cost of one membership change for 20 people: about 5 more group packets, plus one private packet from each new
  member. Leaked messages and rosters carry only signatures by keys bound to nobody outside the group.
- Trade-off: an admin who lies in the roster could now speak as another member to the others, where identity
  signatures prevented that. Admins are already trusted with the member list; the reviewer is asked whether members
  should cross-check each other's keys privately (more airtime) or accept this.

## Review findings to settle before groups are switched on (agent review, lane C)

Fixed in core: signatures are low S only (C5); member lists take identity keys on the curve only (C9). Still to
design in, before any app uses groups:

- **C1:** the signature travels in clear, so anyone who knew the group id can test packets against members' keys
  (removed members name the sender of later packets). Decision 66's group signing keys narrow this to keys bound to
  nobody outside the group; consider sealing the signature inside the AEAD as well.
- **C2:** a co-admin can block his removal with a key for epoch 2^32-1 and no member list. Bound the epoch step and
  drop a pending key whose list does not arrive.
- **C3:** two admins starting the same next epoch split the group silently. Order competing epochs (for example by
  digest) and tell members.
- **C4:** a removed phone is never told and keeps sending into an epoch nobody reads.
- **C6:** a phone never asks the hub for its own next codes, so a hub can name a sender by exclusion; ask for them too.
- **C7:** the epoch secret travels as an ordinary private body and is stored with the incoming row before the group
  takes it; keep it out of message storage and wipe it.
- **C8:** the writer's state cannot be saved before a packet leaves; give it a store-before-send API.
- **C10:** every packet recomputes all windows; cache code to position.

## Questions for the reviewer

1. **Deniability:** settled by decision 66 (above); review the admin-relayed signing keys and their trade-off.
2. **Forward secrecy inside an epoch:** a member keeping `S` can rederive every chain. Phones should derive all
   members' `CK0` at once and erase `S`; a malicious member can still keep it. Renewal on join/leave heals.
3. Signature verified before decryption: is the signed-then-encrypted ordering (signature over ciphertext) right
   for this threat model (members are trusted not to leak, not trusted not to impersonate)?
4. Codes are derived from the chain: does an 8-byte code risk collisions with pairwise codes at 20 members x window
   20 (expected codes per phone grow to ~400 + pairwise)?
5. An offline member misses a key and stays in the old epoch: how does a phone ask an admin again, and how long
   do others keep reading the old epoch? Not designed: receipts, group photo, full pictures, storage, screens.
6. Admins are trusted not to send different lists to different members; digests catch it only when phones compare.
