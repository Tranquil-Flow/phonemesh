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

## Questions for the reviewer

1. **Deniability:** signing with the long-term identity key lets any member prove to outsiders who wrote a message.
   Alternative: a per-epoch signing key per member, certified once by the identity key and sent with the roster.
2. **Forward secrecy inside an epoch:** a member keeping `S` can rederive every chain. Phones should derive all
   members' `CK0` at once and erase `S`; a malicious member can still keep it. Renewal on join/leave heals.
3. Signature verified before decryption: is the signed-then-encrypted ordering (signature over ciphertext) right
   for this threat model (members are trusted not to leak, not trusted not to impersonate)?
4. Codes are derived from the chain: does an 8-byte code risk collisions with pairwise codes at 20 members x window
   20 (expected codes per phone grow to ~400 + pairwise)?
5. Not yet designed: roster distribution (20 x 33-byte identities does not fit one packet), admin changes, join
   history (off by default), receipts, group photo and full pictures.
