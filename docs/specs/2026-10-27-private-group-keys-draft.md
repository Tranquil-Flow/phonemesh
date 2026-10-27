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

## Questions for the reviewer

1. **Deniability:** signing with the long-term identity key lets any member prove to outsiders who wrote a message.
   Alternative: a per-epoch signing key per member, certified once by the identity key and sent with the roster.
2. **Forward secrecy inside an epoch:** a member keeping `S` can rederive every chain. Phones should derive all
   members' `CK0` at once and erase `S`; a malicious member can still keep it. Renewal on join/leave heals.
3. Signature verified before decryption: is the signed-then-encrypted ordering (signature over ciphertext) right
   for this threat model (members are trusted not to leak, not trusted not to impersonate)?
4. Codes are derived from the chain: does an 8-byte code risk collisions with pairwise codes at 20 members x window
   20 (expected codes per phone grow to ~400 + pairwise)?
5. An offline member misses a key and stays in the old epoch: how does a phone ask an admin again, and how long
   do others keep reading the old epoch? Not designed: receipts, group photo, full pictures, storage, screens.
6. Admins are trusted not to send different lists to different members; digests catch it only when phones compare.
