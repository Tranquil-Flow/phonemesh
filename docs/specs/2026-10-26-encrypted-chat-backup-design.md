# Encrypted chat backup (BK)

Draft for the owner's decisions, 2026-10-26. No code before the picks.

Companion: [Account recovery (RC)](2026-10-26-account-recovery-design.md). **Shared decision:** a backup restores saved information, not the old private identity or ratchet. RC owns the random recovery secret and human verification journey. This document owns the sole portable format, key derivation, content selection and atomic import rules. Changes to that shared contract require both drafts to agree.

**Evidence labels:** **VERIFIED** means inspected source or an opened primary reference, not a device result. **INFERRED** means deductions and every proposed requirement below. **UNKNOWN** means unmeasured or undecided. Source paths are relative to the repository root. No product code was changed, built or exercised.

## 1. Goal and who it helps

Save a person's contacts and selected chat history to a file they can carry on SD card, USB storage or another phone, without internet or a company account. Make restoration understandable and testable before a phone is lost. Include people with low literacy through the RC paper/QR recovery card, short spoken instructions and a visible restore rehearsal. Keep large files off the radio. Give a clear choice about how much private history survives outside the phone.

Core: manual bounded full snapshots, external-file verification and read-only restored history. Adjacent: incremental chains, scheduled exports and a dedicated Wi-Fi hub object store. Not included: backups of speech/model packs, camera originals, raw audio, live sessions, radio credentials, private-group secrets, or automatic merging into current conversations.

## 2. How it works today

| Status | Finding and source |
|---|---|
| **VERIFIED** | Android system backup is disabled. `android/app/src/main/AndroidManifest.xml:26-27`. There is no portable key recovery merely by copying app files. |
| **VERIFIED** | AccountStore keeps a Keystore identity where possible, software identities/prekeys sealed by Vault otherwise, and sealed session states. Vault's wrapping key never leaves Android Keystore. `android/app/src/main/java/org/phonemesh/app/AccountStore.kt:25-76`, `:150-173`, `:182-203`. |
| **VERIFIED** | History is SQLite. Message bodies and contact metadata are not separately encrypted by Vault. Account/session-related tables also contain retry, replay, skipped-key and receipt state that must not be copied. `android/app/src/main/java/org/phonemesh/app/History.kt:58-75`, `:101-186`. A raw database export is therefore the wrong boundary. |
| **VERIFIED** | History includes text, pictures, replies, planned sends and reactions. `History.kt:16-55`, `:80-100`, `:541-588`. The convenient `messages(chatId, limit=500)` is a display query, not a complete backup enumerator (`:735-736`). |
| **VERIFIED** | Pictures are files with database references; complete private pictures are materialized from durable pieces. `android/app/src/main/java/org/phonemesh/app/AppCore.kt:192`, `:1785-1792`, `:1853-1869`. View/upscale files are separate (`:2037-2039`). |
| **VERIFIED** | Current wire pictures have at most 3,200 encoded bytes and dimensions at most 512; sketches at most 32 bytes. `android/core/src/main/kotlin/org/phonemesh/core/PictureWire.kt:23-42`, `:77-80`, `:145-147`. A backup of these is very different from a backup of original camera photos. |
| **VERIFIED** | Contacts hold either a whole signed-prekey card or only a 33-byte identity. `android/app/src/main/java/org/phonemesh/app/Accounts.kt:197-198`. Full cards are 134 plus name bytes, at most 174 bytes; name and colour are not signed. `android/core/src/main/kotlin/org/phonemesh/core/account/ContactCard.kt:9-12`, `:26-38`, `:53-68`. |
| **VERIFIED** | P-256 and no new crypto library remain owner choices; backup is a next-version design item. `docs/specs/2026-10-14-accounts-design.md:17-18`, `:29`, `:69-74`. |

**INFERRED:** Do not export SQLite, WAL, Vault ciphertext, Keystore aliases or raw SharedPreferences. Export an explicit logical snapshot. Restoring those files could leave unreadable keys, re-enable queued sends, roll back receive counters or reuse already consumed message keys. Even a same-identity recovery must start fresh sessions, never restore ratchet state.

## 3. Options compared

All backup approaches below add **0 bytes per ordinary radio message**. No archive is sent over LoRa. Encryption cannot prevent the storage holder from deleting or withholding a file.

| Option | Event bytes / radio cost | Security | Phone cost / app size | Effort / old phones |
|---|---|---|---|---|
| A. Manual self-contained encrypted snapshot **recommended** | Plain records + 120-byte header + 20 bytes per 64 KiB chunk. 0 LoRa packets, 0 s. Examples below. | Random root and per-file data key; no live keys; one independent file restores. Past copies remain readable to anyone later obtaining R. | Streaming I/O; bounded buffers; no compression/library required. Combined RC/BK planning allowance 80,000-200,000 added signed APK bytes. | Medium. v0.1.0 cannot import; its normal QR/link messaging still works with the new identity. |
| B. Incremental encrypted history | Less per change, but needs base, deltas, tombstones and periodic full compaction. Byte savings depend on changes; unknown. 0 LoRa packets. | Missing, replayed or reordered deltas can conceal deletions or break restore. Need authenticated chain manifest and new nonce discipline. | Less repeat I/O, more indexing and retained metadata; growth beyond A unknown. | High. New reader required; defer until full restore is proven. |
| C. Whole database encrypted as one file | Includes implementation details, stale state and potentially unwanted secrets; size unknown without user data. 0 LoRa packets. | Excluding live keys by a deny-list is fragile; WAL consistency and KeyStore binding make restore hazardous. | Fast to prototype, costly to make safe. One-shot AEAD can allocate the whole file. | Reject. Schema/phone-bound; old software cannot safely restore it. |
| D. Dedicated encrypted hub storage | Same A bytes over local Wi-Fi, plus object protocol overhead. 0 radio packets. | Hub sees size/timing, can roll back/withhold, must not get root or plaintext. | Adds server quotas, authorization, retrieval capability custody and interrupted-transfer handling. Size/throughput unknown. | Separate later slice. Current mailbox and old hubs are not a backup service. |
| E. No chat archive, contacts-only recovery | About 31 KB in the 100-contact example; 0 radio packets. | Less retained private content, same secret-custody obligation. | Uses the same A codec, fewer records. | Low incremental scope after RC; chat losses remain explicit. |

**INFERRED airtime:** `WireV2.MAX_PACKET=228` (`android/core/src/main/kotlin/org/phonemesh/core/wire/WireV2.kt:18`). `android/hub/src/main/kotlin/org/phonemesh/hub/Airtime.kt:10-22` evaluates a full private-port packet at approximately 2.116 s on LongFast, one hop including its estimated mesh/protobuf overhead. Retries, relays and duty-cycle pauses add cost. `android/core/src/main/kotlin/org/phonemesh/core/LoraAirtime.kt:10-18`, `:29-43` likewise models airtime but its generic overhead includes a PKI direct-message allowance; do not mix those overheads. Full backups are not a radio feature. The existing text/audio contract and primary-channel setting remain unchanged (`docs/design.md:48-56`; `AppCore.kt:2396`).

## 4. Recommendation and why

**INFERRED:** A, with E as an equal first-class choice. Start with manual self-contained files, explicit scope, one recovery root shared with RC, and no passwords. Use built-in AES-256-GCM plus the existing HKDF-SHA256 primitive. Encrypt while writing a private temporary file, export the finished ciphertext through Android SAF or a narrowly scoped share URI, then reopen and authenticate the destination before saying it is checked.

The hardest part is not AES. It is proving that every selected record was copied consistently, that a missing picture was not silently skipped, and that import never revives old sends or ratchets. Start with a contacts-only round-trip and one historical chat. Resolve those boundaries before adding hub APIs or automatic schedules.

## 5. Recommended design

Everything in this section is **INFERRED / proposed**, not implemented.

### 5.1 Included content and explicit exclusions

| Content | Default and restore rule |
|---|---|
| Own public identity, saved name, colour | Included for provenance and optional suggested profile. Public identity is never installed as the new private identity. |
| Contacts, public cards/identity, local names, colours, met history, voice-pack identifier | Included. Saved separately from live contacts. `met` becomes historical only; never current verified status. Missing voice packs do not auto-download. |
| One-to-one private text, shared-channel text, old radio-addressed history | Included only for selected chats/time range. Private chats selected by default; shared and old radio-addressed histories off until chosen. Shared sender claims remain unverified. Legacy radio endpoints and channel credentials are not exported. |
| Replies and reactions | Include as archive-local references when their target is selected. If outside the range, retain a visible missing-target marker, not an invented target. Never reuse radio packet ids as active reply references. |
| Completed picture payload and sketch | Included by default when its message is selected. Preserve the small original encoded radio picture and codec, not view/upscale caches or camera originals. Missing/incomplete pictures get explicit placeholder metadata and summary counts. |
| Historical delivery status | Display-only “status when saved”. It is not a new delivery receipt or proof of current recipient possession. Unsent/uncertain messages remain visibly unsent/uncertain. |
| Pending/scheduled messages | Not in default snapshot. An optional scope toggle may save their text as unsent archive entries, with the old due time for display. No restored timer or outbox work. |
| Deleted messages and pictures | Not recoverable from the app. New snapshots omit them. Old external files may retain them indefinitely. |
| Private identity scalar, prekey scalars, session/ratchet state, skipped keys, recipient/header keys, QR one-time keys | Always excluded on every Android version. No extension record can smuggle them in. |
| Outbox ciphertext, held packets/bodies, replay tables, receipt state, QR/session fingerprints, picture-id allocator, hub cursors/custody | Always excluded. New sessions get independent state; imported history cannot be a source of send/retry work. |
| Hub cards, pins, Wi-Fi passwords, radio/channel keys, device ids, permissions, tokens | Excluded. Rejoin hubs/radios deliberately. Backups are not a device configuration clone. |
| Recorded/synthesized audio, voice imprints, speech/model packs, downloaded APKs | Excluded. No audio is required or generated for backup. Restore must work with no voice pack installed. |
| Private-group data from another proposed feature | Not covered. Block or clearly exclude with an explicit count until its approved format and key policy are integrated. Never silently claim “all chats”. |
| Earlier imported archives | Not recursively embedded in this format. Keep their original encrypted files as separate saved copies; show this exclusion before export. Export is a snapshot of selected live-account history, not an ever-growing nesting of backups. |

Export preview shows selected chats, date range, counts, estimated bytes, missing pictures, optional unsent entries and exclusions. Do not call a selected-range snapshot a complete lifetime backup. Count with a dedicated paged export query, not the current 500-message display method. A limit must cause a scoped retry choice or refusal before publication, never silent truncation.

### 5.2 Destinations and UI

Current entry is **Settings** (`android/app/src/main/res/values/strings.xml:20`); proposed child **Saved copies** has **Save a copy**, **Check a copy**, **Restore a copy**, and **Delete saved copies**. They are new labels, not existing buttons.

1. Choose **Contacts only** or **Contacts and chats** and the explicit scope.
2. Obtain the RC root by its private card/words or the explicitly retained Vault copy. Never suggest a phone PIN as the backup key.
3. Prepare the ciphertext privately, with progress and Cancel. No plaintext temporary archive in shared storage.
4. **Save to a file** uses `ACTION_CREATE_DOCUMENT`, MIME `application/vnd.phonemesh.backup`, suggested filename `phonemesh-<random-id>.pmbak`. Do not put a name/contact list in filenames. The picker may expose SD, USB or a cloud provider. Say which provider the user selected; do not imply every SAF destination is local or offline. Android documents this user-driven access and provider-specific operations [W1]. No broad storage permission is needed for this path.
5. **Give to another phone** uses `ACTION_SEND`, a read-only content URI for exactly one already-encrypted file, temporary URI permission and an explicit Android chooser. A framework ContentProvider is sufficient; no AndroidX FileProvider dependency is required. Return only the selected file, never arbitrary paths; reject writes. Revoke grants after a bounded sharing window, with a visible retry path if the recipient has not finished. No automatic secret attachment. A chooser returning does not prove the other device kept the file.
6. A friend can receive through an OS/local file-transfer app and later return the opaque file. They need not know R or install Phonemesh. Device-specific transfer availability is **UNKNOWN** until qualified.
7. Check the actual saved file, not the private source file, using RC's full parse and separately entered external secret. Mark SAF read-back **Checked on this phone**. For share-sheet destinations that cannot be read back, remain **Sent for copying, not checked** until the received file is independently checked.

Small-phone/low-literacy flow uses a progress picture, file icon and key-card icon kept separate, plus a short optional explanation. Proposed EN/FR copy:

| English | French |
|---|---|
| Save a copy. | Enregistrer une copie. |
| Your key stays separate. | Votre clé reste séparée. |
| This copy has no live keys. | Cette copie exclut les clés actives. |
| Saved. Check it now. | Copie enregistrée. Vérifiez-la maintenant. |
| This copy is not checked yet. | Cette copie reste à vérifier. |
| Old messages will not send again. | Les anciens messages ne repartiront pas. |
| Choose fewer chats or fewer days. | Choisissez moins de discussions ou de jours. |

All copy is proposed for translation/CopyRules and owner mockup review. No automatic audio playback. A storage error must explain where the save failed and offer another destination; a generic success toast is not enough.

### 5.3 Shared key design and Android KDF availability

RC generates a uniformly random 16-byte root R. Words/QR encode that exact root. R is not a user password, a mnemonic-derived P-256 key, or the public six-icon safety picture. RC's key-card format is `PMRK0001`, 28 binary bytes; this file format is distinct.

Every new snapshot generates independently with SecureRandom:
- archive id A, 16 bytes;
- HKDF salt S, 16 bytes;
- data encryption key D, 32 bytes;
- wrapping nonce W, 12 bytes.

`PRK = HKDF-Extract-SHA256(salt=S, IKM=R)`.

`Kwrap = HKDF-Expand-SHA256(PRK, info=ASCII("Phonemesh backup wrap v1") || A, L=32)`.

Use AES-256-GCM with 128-bit tags. Kwrap wraps D once; D encrypts bounded numbered chunks below. R has 128-bit entropy even though derived AES keys are 256 bits. Do not claim 256-bit root security. RFC 5869 defines HKDF [W2], and `android/core/src/main/kotlin/org/phonemesh/core/crypto/Hkdf.kt:6-36` already implements it with HmacSHA256.

**No slow password KDF is needed to defend a uniformly random 128-bit root.** Adding PBKDF2 does not solve secret-card theft. KDF profiles for user passwords are not accepted in version 1.

| Candidate | Android without a new library | Low-end phone cost and decision |
|---|---|---|
| HKDF-SHA256 on random R **recommended** | Existing JCA HmacSHA256 implementation. | Tiny fixed crypto work compared with file I/O; exact milliseconds **UNKNOWN**. No memory-hard allocation. Require background execution regardless. |
| PBKDF2-HMAC-SHA256 for human password | **VERIFIED:** Android SecretKeyFactory lists API 26+ [W3]. Existing SafetyPicture also implements byte-input PBKDF2 (`SafetyPicture.kt:28-36`). | Built in but GPU-friendly, not memory-hard. A future benchmark candidate is 600,000 rounds, one 32-byte output, a fresh 16-byte salt, with a 0.5-2 s low-end latency goal, not a measured result or guarantee. Could take seconds or exceed the goal. Do not silently reduce work or borrow the safety picture's 65,536 rounds. |
| Argon2id | Not a guaranteed Android standard provider algorithm in the opened list [W3]. Need a reviewed library/native build or substantial new implementation. | RFC 9106's lower-memory recommendation is 64 MiB, three passes, four lanes [W4]. Extra heap/native pressure and battery work are material; exact device cost and APK bytes **UNKNOWN**. No new crypto library approved here. |
| scrypt | Not guaranteed by Android's public SecretKeyFactory list [W3]; private/internal APIs are not a portable app contract. | Illustrative `N=65536,r=8,p=1` requires about `128*N*r = 64 MiB` for the main array, plus overhead [W5]. Seconds/thermal behavior **UNKNOWN**. New reviewed code/library required. |

If the owner later insists on passwords, write a separate version/profile with fixed password normalization, min/max work factors enforced before allocation, a password-strength policy and actual low-end benchmarks. Do not silently fall back from Argon2 to PBKDF2, accept a six-digit PIN, or reuse BIP39's 2,048-round wallet seed derivation. This proposal avoids that interoperability and brute-force problem altogether.

Compromising R later opens every older file made with it. Backup history has **no forward secrecy**. It intentionally retains plaintext messages under a long-lived recovery secret even after live ratchets discard their message keys. Use a separate chat root only if the owner chooses BK2-B, because that changes which secret is needed for each file and needs explicit UI.

### 5.4 Canonical container byte layout: PMBK0001

This is a **proposed local-file contract**, not a radio wire revision. Integers are unsigned big-endian. Readers reject unsupported versions/profiles, flags, oversized fields, integer overflow, malformed UTF-8, duplicate ids and trailing bytes. No ZIP, compression, symlinks, filenames or executable content.

#### Fixed header H, 120 bytes

| Offset | Bytes | Field |
|---:|---:|---|
| 0 | 8 | ASCII `PMBK0001` |
| 8 | 1 | suite = 1: HKDF-SHA256 root, AES-256-GCM wrap and chunks |
| 9 | 1 | purpose = 0 contacts-only; 1 history snapshot |
| 10 | 2 | flags = 0 |
| 12 | 16 | random archive id A |
| 28 | 16 | random HKDF salt S |
| 44 | 4 | maximum chunk plaintext size = 65,536 |
| 48 | 8 | total plaintext stream bytes P, including END record |
| 56 | 4 | chunk count N = ceil(P / 65,536) |
| 60 | 12 | wrapping nonce W |
| 72 | 48 | AES-GCM ciphertext of D (32) followed by tag (16) |

Let H0 be header bytes 0..71 inclusive. The wrap operation is `AES-GCM(Kwrap, nonce=W, plaintext=D, AAD=H0)`. H is H0 plus the 48-byte result. Never expose a caller-chosen nonce/key reuse path in the exporter. Every attempt, even a retry after cancellation, starts with fresh A/S/D/W and a new temporary file. A retry of file transfer may copy the already finished byte-identical ciphertext; it must not reseal changed plaintext using the old D.

Header fields reveal format, purpose, length and random id. They reveal neither owner identity nor creation date. Those belong inside encryption. No public recovery-root identifier is stored, so separate exports cannot be linked by such an identifier, though size/timing and storage-provider accounts can still link them.

#### Chunk records

For i from 0 through N-1, in exactly that order:

`plain_length u32 | ciphertext[plain_length] | tag[16]`.

Nonce = `zero[4] || u64(i)` (12 bytes). AAD = `H || u32(i) || u32(plain_length)` (128 bytes). D is a fresh random key per file, so the counter nonces are unique under that key. Every non-last chunk is exactly 65,536 plaintext bytes; last is the positive remainder, or 65,536 when P is an exact multiple. i is implicit in physical order, not attacker-supplied. No empty extra terminal chunk. Require exactly N chunks, total decrypted size P, a valid END record and immediate EOF. Reordering, duplicate chunks, splicing files, trailing data and truncation must fail.

Authentication of one chunk permits only bounded parsing into an isolated, non-visible staging store. It does **not** publish partially restored content. Every chunk and the complete logical stream must pass before preview or commit. This avoids trusting CipherInputStream behavior that can surface bytes before the final tag is checked.

File size is exactly `120 + P + 20*N`. Limits before KDF/decryption: P in 1..536,870,912 bytes (512 MiB), N consistent and at most 8,192, chunk size exactly 65,536. Compute sizes in checked 64-bit arithmetic. Do not trust provider-reported length; enforce actual streaming length and EOF. Stream timeout/cancellation and local storage limits also apply. A legal file can still fail semantic checks.

**Why a wrapped random D?** It separates the long-lived root from bulk encryption, gives each snapshot its own nonce domain, and allows future key-management evolution without reusing identity/ratchet material. Version 1 offers no in-place rewrap feature: H authenticates every chunk, so changing the wrap/header requires resealing all chunks under a fresh D.

### 5.5 Logical plaintext records

The format is not a dump of Kotlin objects. Common frame: `type u8 | flags u8 (=0) | payload_length u32 | payload[payload_length]`, six bytes overhead. A frame may span encryption chunks. Payload length is at most 65,536. Unknown types/flags fail closed in v1. This deliberately makes future additions require a new version, not speculative parser behavior.

Definitions:
- `u8/u16/u32/u64` are fixed-width big-endian unsigned integers.
- `str` = u16 byte length + strict UTF-8, no NUL. `optstr` uses length 65,535 for absent, zero for a present empty string. Field-specific bounds below still apply. Text is display data, never markup, a path or a command. Validate name fields with current Names policy; never silently normalize imported record bytes.
- `blob` = u32 length + exact bytes. No absent sentinel; zero length is permitted only where specified.
- `point` = exactly 33-byte compressed P-256 point; validate on-curve using existing P256 code.
- Archive-local `id` = u64, nonzero and at most signed Long.MAX_VALUE. References of zero mean absent as explicitly stated. They are not SQLite primary keys, wire references or account authority.
- Times are u64 milliseconds since epoch, capped at signed Long.MAX_VALUE, display metadata only. Zero means unknown. No timestamp controls trust or freshness.
- SHA-256 digests are exactly 32 bytes. No authentication claim beyond the enclosing AEAD.

Order: one MANIFEST first; zero or more CONTACT, then CHAT, then MESSAGE, then REACTION, then PICTURE records; one END last. Within each family order by ascending archive-local id. Counts and references are checked using staging database indexes, not an unbounded in-memory map.

| Type | Exact payload in order |
|---|---|
| 1 MANIFEST | `schema u16 (=1), source_identity point, created_at u64, generation u64, own_name str (1..40 bytes), own_colour u8, scope_start u64, scope_end u64, scope_flags u16, contact_count u32, chat_count u32, message_count u32, reaction_count u32, picture_count u32, missing_picture_count u32`. |
| 2 CONTACT | `id u64, identity point, card blob (33 or 135..174 bytes), saved_name str (1..40), colour u8, historical_met u8 (0/1), voice optstr (0..128), added_at u64`. |
| 3 CHAT | `id u64, kind u8 (0=private person,1=shared channel,2=legacy radio history), contact_id u64 (0 only for non-person), title str (0..256), pinned u8 (0/1)`. No live destination address. |
| 4 MESSAGE | `id u64, chat_id u64, direction u8 (0=in,1=out), author_contact_id u64 (0=self/unknown), author_label optstr (0..160), text str (0..4096), language optstr (0..64), time u64, status u8, reply_id u64, reply_missing u8 (0/1), planned_at u64, kind u8 (0=text,1=picture), picture_id u64`. |
| 5 REACTION | `id u64, target_message_id u64, author_contact_id u64 (0=self/unknown), author_label optstr (0..160), emoji str (1..32), time u64, was_unsent u8 (0/1)`. |
| 6 PICTURE | `id u64, message_id u64, state u8 (0=complete,1=missing/incomplete), codec u8, width u16, height u16, sketch blob (1..32), data blob (0..3200), digest[32]`. |
| 255 END | `preceding_record_count u64, preceding_stream_bytes u64, digest[32]` where digest is SHA-256 over exact framed plaintext bytes before END, excluding END itself. |

Further constraints are part of the contract:

- MANIFEST scope_flags bits: bit 0 shared histories selected; bit 1 legacy histories selected; bit 2 include unsent/scheduled text; bit 3 include picture data. All higher bits zero. Scope_start/end describe inclusive selected times; both zero means no date filter, otherwise start <= end. Historical zero/unknown times require an explicit inclusion choice before export; do not silently assign today's date.
- Generation is a local monotonically increasing export counter for this source identity, allocated durably before export; gaps are allowed. It is not a globally trusted clock or freshness proof on a new phone. Store the highest seen generation only as a local warning aid.
- purpose 0 permits only MANIFEST, CONTACT, END; chat/message/reaction/picture counts and scope flags/times are zero. Contacts-only does not omit the user's own public provenance/name.
- A full card must pass ContactCard.decode and match the separate identity exactly. A 33-byte bare key must equal identity. Recompute the contact's account id when needed; never import it as independent authority. Distinct identities with an account-id collision must not merge. Saved names/colours can differ from unsigned card metadata and remain unverified.
- Current runtime colour handling must validate the imported byte; preserve unknown historical indices for display fallback without using them as unchecked array indexes. Profile UI may clamp by explicit user choice, never mutate authenticated archive records.
- A private CHAT references one CONTACT. No two CONTACT records may carry the same full identity. Existing live-account contact matching uses full identity, not name alone. Non-person titles may retain display names but omit radio node ids/addresses.
- MESSAGE status values: 0 received, 1 sent, 2 reached hub, 3 delivered, 4 failed, 5 unsent, 6 unconfirmed. Map source states explicitly; unsupported states fail export or need a new format. Status is archive-only. Incoming rows must be status 0. `author_contact_id=0` plus direction OUT identifies own authorship; IN with zero is unknown/claimed author, never verified.
- Reply_id must name a selected message in the same chat, or be zero. If an original reply target was excluded/absent, set reply_missing=1 and reply_id=0. No reply means both zero. Scheduled/unsent text has status 5 or 6 as appropriate and never becomes a planned action. Header scope flags must authorize its inclusion.
- TEXT has picture_id=0 and nonempty meaningful text. PICTURE has a unique matching PICTURE record; caption text may be empty. Preserve body bytes and language, but reject invalid/control-bearing strings using the applicable display policy; report unsupported historical data rather than silently dropping it.
- REACTION target must be included. Reactions whose target is outside scope are counted as omitted in the export preview, not inserted with dangling references. If attribution is a legacy radio id, replace with an appropriate display label or unknown, never export the endpoint as a routing field. Emoji must pass current reaction validation.
- PICTURE dimensions are 1..512 and codec must be a currently supported PictureCodec wire value. Complete non-sketch data must pass its codec size/structure validation; sketch-only codec has zero data and a valid sketch. Missing/incomplete state has zero data and is not recoverable later by replaying pieces. `digest=SHA256(data)` including for empty data. If picture data was not selected, emit state 1 and explain the omission. Do not restore file paths from input; generate new internal names. Enforce safe image decode bounds before creating bitmaps.
- Counts must match observed records. missing_picture_count counts state-1 PICTURE rows. Maximums: 10,000 contacts, 10,000 chats, 500,000 messages, 500,000 reactions, 100,000 pictures, additionally bounded by P and available disk. END counts all preceding frames including MANIFEST. Exactly one END and immediate stream end are required.
- A file holder with R can forge every manifest field, including identity provenance and historical trust. None of these records authenticates an identity transition to a peer.

This version deliberately lacks fields for identity private keys, prekeys, sessions, ciphertext packets and restoration of active network addresses. Parser rejection tests must prove that unexpected “session” or “key” records cannot be accepted through an extension path.

### 5.6 Snapshot consistency and export lifecycle

The current History comment says main-thread calls are small indexed queries (`History.kt:58-60`). A backup is not one. Do not run an entire dump on the UI thread or call current main-thread helpers from a worker without a reviewed DB ownership seam.

Proposed approach: one serial backup worker with a dedicated read connection/transaction for a stable logical snapshot; writes continue through existing History ownership. Confirm Android SQLite WAL/connection behavior before implementation. Under a short app-level snapshot lock, record selected message/picture ids and hold a read lease on their immutable encoded picture files. Deletion must postpone physical removal while the snapshot owns a lease. If that cannot be integrated safely, take a brief bounded exclusive snapshot copy into app-private staging, with Cancel and explicit busy UI. Never copy a live `.db` file alone and assume it is consistent.

Export selected records by keyset pagination, not offset against changing rows. Validate and count all records and picture files against the frozen view. Establish P, counts and stream digest before producing the final header. A two-pass walk over a stable snapshot is allowed; a private logical staging file, if needed, must be encrypted under a fresh temporary key sealed by Vault and deleted on completion/cancel. No plaintext staging file outside app-private storage; avoid even private plaintext logical spools when streaming can do it.

Write finished encrypted output to an app-private temporary file. Reopen/authenticate it. Only then copy to a newly created external document. SAF providers may not support atomic rename; never truncate a last-known-good external backup in place. A partially written new document remains invalid and is removed only if app-owned and deletion is supported. Close/sync local files as supported; provider close is not proof of remote durability. Reopen the external URI and run the check before reporting success. If read-back fails, retain the old valid copy, report the exact failure category and offer retry/another destination.

Cancel or process death discards incomplete private staging on next startup. No resumable encryption across changed snapshots in version 1. Copying finished ciphertext can restart safely. Progress is bytes/records processed, not an invented time estimate. Encrypt/decrypt on a worker with bounded cancellation checks and no microphone, TTS, radio or automatic internet activity.

### 5.7 Restore lifecycle and local storage changes

1. Open a user-selected file with `ACTION_OPEN_DOCUMENT`. Parse only bounded public framing; request R. Wrong key and authenticated corruption use the same failure category.
2. Copy/read ciphertext with actual length bounds. Authenticate every chunk and parse into an isolated app-private staging database. The staging database is plaintext like current History, protected by app sandbox and Android file encryption, **not** a claim of extra database encryption. Do not expose it to active AppCore or network workers. Sensitive scopes should be short-lived; remove abandoned staging at next startup.
3. Check END, counts, references, identities, picture hashes/codecs, limits and EOF. Regenerate picture filenames internally. No visible preview before complete authentication and validation. Show source name/date as “from this saved copy”, not a verified identity statement.
4. Show the new-identity warning from RC. On a fresh installation, generate a new normal identity/prekeys in a fresh, unique Keystore/Vault namespace. On an existing installation, **import historical archive only**, preserving current live keys and sessions. No destructive replacement option in v1.
5. Store historical records in separate archive tables. Proposed minimum: `backup_archives(archive_id BLOB PRIMARY KEY, file_digest BLOB, source_identity BLOB, generation INTEGER, created INTEGER, scope BLOB, state TEXT)`; `recovered_contacts(archive_id, local_id, identity BLOB, card BLOB, saved_name TEXT, colour INTEGER, historical_met INTEGER, voice TEXT, added INTEGER)`; archive chats/messages/reactions/pictures keyed by `(archive_id, local_id)`. The exact DDL is implementation work; required uniqueness, foreign keys and semantics follow the format. Never overload live History message ids or contact met flags.
6. Use a durable import journal. Copy picture blobs to a staging directory; close files and validate hashes before a single SQLite commit marks the archive visible and records its final location. Publication is gated by that commit. A crash before commit leaves only cleanup candidates; after commit, startup must resolve the committed path before rendering. Missing committed files show a storage error and never fabricate a complete import. Android filesystem and SQLite are not one transaction; test each boundary. The fresh account's alias names must be recorded and reused on resume, never recreated under an existing alias.
7. Import identity `(archive_id, whole-file SHA256)` prevents duplicates. Same id and digest is idempotent. Same id with different bytes is refused, not overwritten. A newer/different snapshot imports as another read-only archive with an explicit prompt; no message deduplication by text, timestamp or truncated wire reference. Do not alter a live account's trust/session state to “merge” it.
8. After commit, wipe R/D buffers best-effort, delete staging and offer RC reconnect actions. New sessions start through explicit fresh QR/link, not archive import. Archive messages cannot notify as newly received, trigger speech, create receipts, refresh hub cursors, send reactions/replies or rearm scheduled sends. A deliberate “Copy text to new message” can be a later user action with ordinary Send confirmation.

Schema version is not allocated here because other feature drafts may modify History. One integration migration must cover fresh install and v0.1.0 upgrade, with rollback/failure tests. Keep `allowBackup=false`; qualify platform/OEM transfer behavior instead of assuming that flag proves every device-transfer path is blocked.

### 5.8 Sizes, bounds and avoiding unbounded growth

**INFERRED examples, computed with Python, not observations of a user's data.** Assume approximately 300 bytes per contact, 400 bytes per selected text message including record metadata, 3,400 bytes per picture including its maximum 3,200-byte encoded payload and metadata, and 1,024 bytes other metadata. This is an illustrative model, not a cap per arbitrary export. Actual progress/preflight uses encoded bytes.

| Example | Plain stream P | Chunks | Encrypted bytes | MiB |
|---|---:|---:|---:|---:|
| 100 contacts only | 31,024 | 1 | 31,164 | 0.030 |
| 100 contacts + 10,000 texts | 4,031,024 | 62 | 4,032,384 | 3.846 |
| Above + 1,000 maximum-size pictures | 7,431,024 | 114 | 7,433,424 | 7.089 |
| 100 contacts + 100,000 texts + 10,000 pictures | 74,031,024 | 1,130 | 74,053,744 | 70.623 |

No speech weights or original camera JPEGs are included. If future pictures grow, change this format's bounds explicitly rather than allowing arbitrarily large blobs. No padding is proposed; the storage holder learns approximate history size.

Proposed defaults: last 90 days of selected live chats plus all saved contacts, with **All available history** available within hard caps. Show the exact range on every snapshot. Do not silently discard older messages from the live app; archive selection and local history deletion are separate choices. Limits: 512 MiB plaintext per file, record/count caps above, warn and offer a smaller scope if exceeded. Avoid loading all pictures or message ids in RAM; fixed chunk buffer plus one bounded record, indexes on disk. Exact heap/RSS and battery costs remain **UNKNOWN** until Android tests.

A snapshot is a new file, never an append-only forever-growing container. App-private cache keeps at most one in-progress export and one finished share file with a 24-hour cleanup deadline. Persist deadline and clean on next launch as well as scheduled maintenance; no promise that Android always runs cleanup exactly on time. Do not retain private decrypted staging for that whole sharing period.

For a user-selected managed folder, offer **Keep the latest three checked copies**, but default to asking before deleting external files. Delete only exact recorded app-created URIs after the new copy has passed read-back; never recursively prune arbitrary folders or delete another app's files. Manual SAF exports outside a managed folder and copies held by friends cannot be enumerated/revoked reliably. Show that limit. The owner can delete older copies with informed confirmation; a corrupted new file must never remove the last working one.

No automatic scheduled backup in v1. Local weekly reminders may be added later; a reminder is not a saved snapshot. Incremental backup remains deferred: it needs stable record identifiers, authenticated deletion tombstones, bounded chain length, base availability and compaction into a new full snapshot. Whole-file snapshots are cheaper to reason about for the small encoded pictures currently sent over radio.

### 5.9 Hub over Wi-Fi: destination policy, not mailbox abuse

First slice: no changes to Hub, HubServer or Mailbox. A user may manually copy the encrypted file to storage operated by the hub owner, through an already available OS file-sharing route; do not claim the Phonemesh app currently uploads it. The shipped app screen is still **Village hub** (`values/strings.xml:464`), despite the owner's approved rename to local hub (`docs/specs/2026-10-14-accounts-design.md:60-63`).

A later dedicated service would accept the unchanged ciphertext over pinned local Wi-Fi TLS, never LoRa, never the existing `/send` packet endpoint. It would need explicit upload authorization, random unguessable retrieval capabilities kept separately from R, per-owner and global quotas, bounded object count/size, expiry visible before upload, complete-before-publish semantics, and authenticated read-back. No capability, HTTP route or share format is allocated here. If BK7-B is chosen, design those exact bytes/routes before implementation rather than inventing them in code. Losing a retrieval capability must not be presented as cryptographic key loss; an independent removable-media copy avoids sole hub dependence. A blind hub can still copy, delete, withhold and replay files. It must not automatically hold a recovery share too.

### 5.10 Threat model and failure cases

| Threat | Protection / limit |
|---|---|
| File stolen from SD/USB/friend | Random R plus AEAD protects content under standard assumptions. Format, purpose, length and storage timing remain visible. No low-entropy password option in v1. |
| Secret stolen, but file unavailable | Secret does not recover contacts/history or identity by itself. Attacker can seek any old copy; sharing one root broadens the eventual exposure. |
| File plus secret stolen | All selected history, names and contacts are exposed; content can be forged. No forward secrecy or provenance guarantee against a key holder. Old identity scalar remains excluded. |
| Unlocked or compromised phone | Current history is readable and active keys usable. Cached R, currently entered secrets and plaintext staging may be exposed. FLAG_SECURE and buffer wiping are limited mitigations, not OS-compromise protection. |
| Malicious hub/contact/provider | Can withhold, truncate, replace, replay or selectively retain copies. AEAD detects unauthorized modification, not withholding or an authentic old backup. A new phone cannot prove latest generation without an independent trusted record. |
| Coercion | A person can be forced to reveal card, phone or shares. No duress/plausible-deniability claim. Social thresholds do not remove coercion risk. |
| Parser bomb or damaged storage | Strict checked limits before allocation, fixed profiles, no compression/path extraction, bounded records and image decode, reject trailing data. Cancellation and timeouts do not publish partial imports. |
| Old pending sends or reused ratchets | Never exported as active state; archive tables have no route into sender/receiver workers. Fresh identity/session on replacement, with explicit trust checks. |
| Deletion expectation | Deleting a message now does not delete external copies. Re-keying cannot revoke someone else's old ciphertext/key pair. Explain before enabling backup. |
| Future quantum attacker | P-256 remains the project's selected key system; this backup does not claim quantum resistance or reopen shelved identity-key decisions. |

Failure categories: unsupported format/profile; file too large; key-or-authentication failure; invalid authenticated contents; not enough space; destination unavailable; copy not checked. Do not log record content or provider URIs containing capabilities. No fallback to plaintext, partial restore or guessed schema. Missing picture placeholders must be counted and shown, never quietly dropped. A restore can be cancelled without changing a live account.

## 6. Compatibility and migration

**INFERRED:** No changes to current private radio packets, shared-chat wire, contact cards, P-256 identity algorithm, primary channel or audio prohibition. Backup files are not messages. v0.1.0 peers continue normal messaging. A replacement identity is added through existing QR/link flows; old peers cannot show dedicated new-phone association UI. No automatic same-name replacement and no key-change control packet.

A v0.1.0 source installation must update before it can export. Upgrading an existing phone keeps its identity/session state; exporting never resets them. Imports go only into new archive tables, with a separate restore path for fresh-install profile setup. Do not use an imported account row to claim “same account”. Known future formats fail clearly with “Use a newer Phonemesh version”, never a crash. Never downgrade the installed database to make an older app import.

Recovering a contacts-only file and a full file with the same R is deliberate. Each is self-contained and may represent a different date. A user chooses which to trust/import; no automatic cross-file merge. A fresh snapshot under a new root does not obsolete all independently retained old files cryptographically.

## 7. Test plan and effort slices

**Proposed tests, NOT RUN.** The brief forbids Gradle/emulators/adb/devices/radios and voice operations for this drafting task. Draft validation is not crypto qualification.

### JVM first

- Independent reference vectors for HKDF, wrap and chunks; compare exact byte layout and offsets. Use fixed test inputs only in fixtures; product RNG must not be caller-controlled. Check `120 + P + 20*N` for tiny, exact-boundary and multi-chunk files.
- Flip every header byte, wrap byte, chunk length, ciphertext and tag; wrong R; every truncation of small fixtures; missing END; swapped/duplicated/skipped chunks; file splices; extra EOF bytes. Reject before publication.
- Profile/flags unknown; u64 overflow; inconsistent chunk count; 512 MiB cap boundary via bounded fake streams; malicious huge declared record; timeout/cancel. Assert oversize rejection precedes large allocation/KDF work.
- Record round-trips for full cards and bare identities, expired prekeys as inert metadata, unicode names/text, historical met flags, every status, reactions, out-of-range reply targets, sketches, completed/missing pictures. Reject duplicate ids, cross-chat reply references, conflicting identities, bad point/signature, invalid codec/dimensions, dangling references and mismatched counts/digests.
- Export source containing more than 500 messages and assert exact selected count, so the display-query limit cannot silently truncate it. Test scoped exclusions, skipped old reaction targets and pending plans saved only as archive text.
- Test complete replay of the same archive is idempotent; same A/different bytes refuses; stale generation warns without falsely proving freshness. Empty contacts/history still has MANIFEST+END and valid encryption.
- Assert no exporter path contains identity scalar, prekey, session, outbox, QR key, received-mail, skipped-key or hub-credential records. Decrypted fixture inspection is a structural test, not proof from a ciphertext substring scan.

### Android integration after approval

- API 29/30 and 31+ key paths; fresh-install and existing-account import. Preserve existing alias/key bytes and live sessions. Kill/relaunch at every file/DB journal boundary and inject late SQL/storage failures. Require exactly one complete visible archive or no change.
- SAF local document, revoked permission, USB/SD removal, read-only destination, disk full, provider non-seekable stream, wrong reported length, delayed close and denied read-back. Never overwrite the last good copy.
- Share provider traversal/write attempts, excessive URI grants, old-grant expiry and a recipient that reads slowly; no plaintext or root in the shared file, filenames, clipboard, logs, accessibility auto-announcement or saved instance state.
- Snapshot versus concurrent message receive/delete and picture completion. Verify a consistent chosen snapshot or an explicit retry, not mismatched counts/files.
- Instrument sender/notification/receipt/speech/hub seams and prove import triggers none, including unsent/scheduled rows and retained picture metadata. Archived reactions/replies cannot address live messages.
- Measure single-core low-end export/import wall time, cancellation latency, peak heap/native memory, battery/thermal behavior and temporary disk overhead. Test text-only operation without packs. Do not substitute desktop PBKDF timing for Android results.

### Demo and UX coverage

Use the actual codec and import controller in the separate demo app with synthetic archives only. Include good contacts-only/full restore, wrong card, corrupt file, missing pictures, cancelled import, old copy, existing-account import and Kofi returning with a new identity. Keep demo state/keys distinct from release. `docs/specs/2026-10-25-demo-build-design.md:14-46` requires demo coverage each release and says it is not hardware/network/security-isolation proof. Review EN/FR screens, large fonts, reading assistance, “checked here” versus “checked elsewhere”, and the file/key separation how-to. Real removable-media survival and another-phone restore remain separate physical tests after authorization.

### Slices, dependencies and size gate

1. Owner RC/BK picks and independent format/security review. Freeze shared root, no-ratchet rule, content scope and byte layout.
2. Pure codecs, generated reference vectors and adversarial JVM tests. Shared with RC; do not budget the crypto twice.
3. Contacts-only snapshot, private ciphertext staging, SAF export and real read-back. Cheapest end-to-end discriminator.
4. History/picture snapshot consistency, import journal and archive-only viewer; then scoped reactions/replies/unsent-history support.
5. RC new-phone reconnection composition, mixed-version tests, demo walkthrough, screenshot/copy review, Android/OEM transfer tests and APK-size qualification.
6. Optional separate work: hub object storage, schedules, incrementals, password profiles or Shamir recovery. None is required for A's first release.

**INFERRED planning budget:** combined RC/BK code/resources might add 80,000-200,000 signed APK bytes before optional hub/share/password work; actual minified per-ABI growth is **UNKNOWN**. The RC English/French word lists alone are 29,893 raw bytes before compression, verified by fetching/counting the lists. No new native library or model is proposed. Current signed ceilings are 16,575,000 arm64 and 14,715,000 armeabi-v7a (`android/gradle.properties:8-9`), and the brief reports only about 1.6 KB arm64 headroom. Request owner approval for a measured increase; do not silently alter limits or claim this fits. Effort is medium-to-high across the coupled features; exact developer-days are **UNKNOWN** without an admitted build baseline and device qualification slots.

## 8. Decisions for the owner

### Blocking batch

**BK1. First backup shape?**
- A. **(recommended)** Manual self-contained encrypted snapshots, with contacts-only and scoped-history choices; easiest independent restore.
- B. Incremental chains immediately; lower repeat I/O but more failure, deletion and compaction machinery.
- C. Contacts-only this release; chat backup remains an explicit outstanding requirement.

**BK2. Which secret opens chat files?**
- A. **(recommended)** Same random RC root, separate random data key per file; one kit to understand, shared compromise scope.
- B. Separate random chat root/card; contacts-only key theft no longer opens chats, but users must keep and rehearse two secrets.
- C. Human-chosen password; requires another reviewed profile and real low-end KDF/strength tests before release.

**BK3. What scope is offered by default?**
- A. **(recommended)** Contacts plus last 90 days of selected private live chats and small completed pictures; shared/legacy/unsent off, clear exclusions.
- B. All available live history and shared/legacy chats by default, within caps; simpler selection but more sensitive retention and storage.
- C. Text and contacts only; smaller files, pictures explicitly lost.

**BK4. Where does the first release save?**
- A. **(recommended)** SAF file export/import plus encrypted share-sheet handover; SD/USB/friend support depends on available providers.
- B. SAF only initially; fewer grant/recipient failure paths, less convenient phone-to-phone copying.
- C. Add a dedicated hub Wi-Fi service now; requires the separate capability/quota/protocol design in BK7.

**BK5. Approve the bounded PMBK0001 format?**
- A. **(recommended)** HKDF random-root profile, AES-GCM wrapped per-file key, 64 KiB chunks, 512 MiB limit, strict typed records and full validation before publication.
- B. Select an established reviewed archive library/format instead; revisit no-new-library policy, compatibility, footprint and streaming bounds before code.

**BK6. Restore behavior and proof of success?**
- A. **(recommended)** Read-back checked files, isolated staging, read-only archive import, fresh identity on a replacement phone, no live keys or automatic sends.
- B. Merge imported history into active chats; adds identity/reference/deduplication risks and needs a separate approved merge contract. Ratchets still remain excluded.

### Deferred batch

**BK7. Dedicated hub storage?**
- A. **(recommended)** Defer; opaque manually copied files are possible but the existing mailbox is not a backup API.
- B. Include pinned Wi-Fi storage after an exact capability/route/quota/expiry design and independent-copy restore tests; never use LoRa.

**BK8. Retention and scheduling?**
- A. **(recommended)** Manual snapshots, one temporary share file with 24-hour cleanup, and optional keep-three-checked-copies with confirmation in an explicitly managed folder.
- B. Automatic periodic snapshots now; requires retained R, durable provider grants, scheduling and disk-pressure tests.
- C. Never offer external pruning; simpler permissions, but users manage accumulation themselves.

## 9. Sources

Abbreviated app filenames above are under `android/app/src/main/java/org/phonemesh/app/`. `SafetyPicture.kt` is `android/core/src/main/kotlin/org/phonemesh/core/account/SafetyPicture.kt`; `values/strings.xml` is `android/app/src/main/res/values/strings.xml`. The cited code and local specs were opened during drafting.

- [W1 Android documents/SAF](https://developer.android.com/training/data-storage/shared/documents-files), opened: create/open actions, provider-dependent permissions and operations.
- [W2 RFC 5869](https://www.rfc-editor.org/rfc/rfc5869.txt), opened: HKDF extract/expand, salt and domain context; existing Hkdf.kt reused in the proposal.
- [W3 Android SecretKeyFactory](https://developer.android.com/reference/javax/crypto/SecretKeyFactory), opened: PBKDF2-HMAC-SHA256 API 26+, public supported algorithm list. Absence from this list means no guaranteed portable Argon2/scrypt API, not proof no OEM ever implements one.
- [W4 RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html), opened: Argon2id, recommended memory-cost profiles and vectors. Device performance remains untested.
- [W5 RFC 7914](https://www.rfc-editor.org/rfc/rfc7914.txt), opened: scrypt N/r/p inputs, ROMix block storage, vectors and secret-memory caveats.
- [W6 Android Keystore](https://developer.android.com/privacy-and-security/keystore), opened: non-exportability, conditional hardware backing and key-use limits.
- [W7 BIP39](https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039.mediawiki), opened: generated entropy and word encoding; RC deliberately does not use its wallet seed derivation.
- [W8 English word list](https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt) and [W9 French word list](https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/french.txt), fetched and counted for the raw resource budget. Their live URLs must be replaced with reviewed pins before implementation.
