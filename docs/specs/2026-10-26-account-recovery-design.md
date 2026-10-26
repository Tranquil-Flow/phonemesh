# Account recovery (RC)

Draft for the owner's decisions, 2026-10-26. No code before the picks.

Companion: [Encrypted chat backup (BK)](2026-10-26-encrypted-chat-backup-design.md). These are one design, split into account behavior here and the portable encrypted file contract there. RC decisions do not approve BK automatically.

**Evidence labels:** **VERIFIED** means source read for this draft, not a device test. **INFERRED** means a deduction or proposed behavior, including every normative requirement below. **UNKNOWN** means not measured or not decided. Source paths are relative to the repository root. No builds or device operations were run.

## 1. Goal and who it helps

Let someone replace a lost or broken phone, recover their saved names and contacts, and optionally their chats, without a service account or internet. Include people who cannot read through a private paper/QR recovery card and guided practice, not a requirement to memorize words. Be honest: the recommended version recovers saved information, **not the old cryptographic identity**. Contacts must add and verify the new identity. Decision 12 requires recovery in a later build; the owner must decide whether this narrower but feasible recovery meets that requirement.

Core: a tested offline recovery kit, safe new identity, and contact re-verification. Adjacent: social shares and automatic hub storage. Not included: username lookup, cloud accounts, remote wipe, same-account multi-device use, or quantum-safe keys.

## 2. How it works today

| Status | Finding and evidence |
|---|---|
| **VERIFIED** | Decision 12 requires later recovery; decision 31 calls for backup and recovery specs; quantum-safe keys are shelved. `docs/specs/2026-10-14-accounts-design.md:29`, `:69-74`, `:433-443`. |
| **VERIFIED** | AccountStore attempts a non-exportable Android Keystore P-256 identity on API 31+. It falls back to software if creation fails. Android 10/11 normally use a software scalar sealed by Vault. `android/app/src/main/java/org/phonemesh/app/AccountStore.kt:67-95`, `:182-203`. |
| **VERIFIED** | Vault uses a Keystore AES-256-GCM key and row-label AAD; its blobs are nonce(12), ciphertext, tag(16). Copying those blobs to another phone does not copy the wrapping key. `AccountStore.kt:25-64` under the same directory. |
| **VERIFIED** | A new signed prekey is generated every 30 days; a retired prekey remains another 30 days. Neither a saved public card nor a phrase regenerates these private keys. `AccountStore.kt:135-145`, `:177-180`, `:218-225`. |
| **VERIFIED** | Session states are sealed, exact predecessor updates are checked, and explicit mutual QR is the replacement path. `AccountStore.kt:150-173`. SQLite has separate account, prekeys, contacts, sessions, outbox and replay/receipt tables. `android/app/src/main/java/org/phonemesh/app/History.kt:101-186`. |
| **VERIFIED** | A contact's `card` is sometimes a complete signed-prekey card, sometimes only a 33-byte identity received in a hello. `android/app/src/main/java/org/phonemesh/app/Accounts.kt:161-168`, `:197-198`. Treating every saved contact as an invitation link would fail. |
| **VERIFIED** | Existing-contact link opening returns immediately, without creating a session. Receiving a hello for a contact without a session also returns. `Accounts.kt:94-121`, `:135-151`. Restoring rows directly into live `contacts` would strand reconnection. |
| **VERIFIED** | Safety pictures depend on both identity keys, not the name, colour or phone model. They show six of 64 icons and use PBKDF2-HMAC-SHA256 with 65,536 rounds. `android/core/src/main/kotlin/org/phonemesh/core/account/SafetyPicture.kt:6-35`. |
| **VERIFIED** | System backup is disabled: `android/app/src/main/AndroidManifest.xml:26-27`. This is not a portable recovery implementation. OEM transfer behavior must still be qualified. |

**Important correction:** “Keystore” is not proof of hardware backing. The code does not inspect KeyInfo or demand StrongBox. Android documents non-exportability and separately documents how to inspect the security level [W1]. Even a genuine hardware-backed identity cannot be exported after creation. An encrypted copy of its alias or public key cannot restore it.

## 3. Options compared

All options can work offline. A recovery secret without a file cannot recreate names, contacts or messages. A file without its secret cannot restore encrypted contents.

**INFERRED cost convention:** zero additional bytes per ordinary message for every option below. Archive traffic is file/USB/SD/Wi-Fi, never LoRa. Existing contact setup still costs its ordinary QR or link traffic. App-size numbers are planning allowances, not APK measurements.

| Option | Identity and security | Bytes, packets, airtime per event | Phone cost / app growth | Effort / v0.1.0 compatibility |
|---|---|---|---|---|
| A. Random recovery secret + encrypted file; fresh identity **recommended** | Preserves current device-key protection. Secret exposes saved information, not the old identity. Re-verification required. | 16-byte secret, 28-byte binary recovery card, about 31 KB for 100 saved contacts in BK's example. Export: 0 LoRa packets, 0 s. QR re-add: 0 setup radio packets. Link re-add: ordinary hello plus later traffic. | Streaming file work; HKDF is cheap. Shared RC/BK planning allowance 80,000-200,000 added signed APK bytes. | Medium. Old peers add a new account through existing QR/link; old apps cannot import backup files. |
| B. Recoverable software identity from a phrase, plus file | Preserves identity and safety picture if the same secret yields the same scalar. Secret theft permits permanent impersonation, including off-device. No server can evict a clone. | Scalar is 32 bytes; phrase carries at least 128 bits of entropy. File adds negligible bytes. Still 0 backup radio packets. Fresh sessions require explicit reset, not just a restored key. | Must expose/derive scalar in app memory even on new phones; importing it into Keystore later cannot erase existing copies. Extra crypto review and migration work. | High. Existing hardware identities cannot be converted without changing identity once. v0.1.0 peers have no automatic same-identity reset; mutual QR needed. |
| C. Encrypted file with a separate random key card, no word phrase | Same cryptography as A. Good for users who can keep two physical objects. Lose either object and recovery fails. | Same archive; key-card QR is 28 binary bytes before scanner text encoding. 0 backup radio packets. | Removes word entry cost, but needs QR scanning and paper handling. | Medium. Same messaging compatibility as A. This is an accessible entry method for A, not a different encryption system. |
| D. Shamir shares with contacts and/or a hub, plus file | Threshold holders can decrypt; fewer shares do not disclose the secret with a correct scheme. Threshold collusion or coercion defeats it. Shares do not restore lost file contents. | Proposed 2-of-3 policy; exact share bytes depend on a selected reviewed format. **UNKNOWN**, not a new wire allocation. QR/file handover: 0 LoRa packets. | More screens, custody tracking, reconstruction tests and audits. Size unmeasured and outside the A allowance. | High. Older apps cannot manage shares; paper shares could be held without the app. Defer. |
| E. Re-add contacts in person, no backup | No recovery-secret exposure. All lost-phone history and local names are lost. | No stored recovery bytes. Mutual QR: 0 setup radio packets; normal messages afterward. | No new crypto. Small help/UI change only, size unknown. | Low. Works today, but does not satisfy the intended information-recovery goal alone. |

**INFERRED airtime, calculated from code:** `android/hub/src/main/kotlin/org/phonemesh/hub/Airtime.kt:10-22` gives 2.116 s for a 228-byte private-port packet at LongFast including its estimated 22-byte overhead, one hop. A 120-byte packet is 1.296 s, a 140-byte packet 1.460 s. Do not price recovery as “one second per packet” or scale airtime linearly by bytes. Relays, retries, receipts and duty-cycle waiting are additional. These are formula evaluations, not measurements. `WireV2.kt:18` caps payloads at 228 bytes; `AppCore.kt:2396` fixes primary channel 0. The legacy text contract remains 200 UTF-8 bytes including tag and forbids audio (`docs/design.md:48-56`). No recovery packet is proposed.

## 4. Recommendation

**INFERRED:** Choose A with C as its equally valid QR/paper entry method. Keep hardware/non-exportable identities where available. On replacement, generate a new identity and prekeys; restore information only. Use one app-generated recovery root R for both contacts-only recovery files and optional full chat archives. Each file has its own random data key. BK defines the sole file format, `PMBK0001`.

Call the feature **“Recovery kit”**, not “Keep your identity forever”. A kit has two separate things: an encrypted file outside the phone and a secret kept apart from that file. Offer a contacts-only file before asking whether to save chats. The export must say what is and is not saved.

The bottleneck is a trustworthy replacement and re-verification journey, not key derivation speed. The cheapest falsifying check is an offline restore rehearsal with only the external file and the external secret, then re-add a peer without using any source-phone key or ratchet state. The first trial must include the current code's missing-session trap.

### What survives under A

| Item | After restore |
|---|---|
| Old identity private key and account id | Private key never included. New identity and account id. Old public identity retained only as archive provenance. |
| Own name and colour | Recovered as editable suggestions, then validated for the current UI. Not proof of identity. |
| Contacts, their cards and local names | Recovered into a separate saved-contact area. Full public identity pinned; do not trust only the truncated account id. Full cards may be stale; bare identity entries have no prekey. |
| Met-in-person flag | Retained as “verified on the old phone” history, never written as current `met=true`. New phone must re-verify each pair. |
| Per-contact voice preference | Optional identifier only, if the pack exists. No automatic pack download. |
| Chats and completed small pictures | Only if BK full backup was selected. Display as recovered history, not new incoming messages. |
| Ratchet, prekeys, replay state, pending requests, outbox, scheduled sends | Not restored. Fresh sessions only. Old incomplete or queued messages may be archive text, never automatically resent. |
| Mail still held by a hub for the old identity/session | Not recoverable with the new phone. Ask contacts to send again after reconnecting. |

The six safety pictures are recomputed and normally change because our identity changes. A short picture fingerprint can coincidentally collide; a matching picture alone must not clear the identity-change warning. If option B were chosen instead and both identity keys stayed unchanged, the pictures would stay the same even with new sessions. That would not prove the old device was gone.

## 5. Recommended design

### 5.1 One recovery root, separate from identity

**INFERRED contract:** Generate R as 16 random bytes using SecureRandom. Security target is 128 bits, consistent with P-256's classical security level. Never derive it from a name, phone PIN, date, six safety icons, or a person-chosen sentence. It is not a P-256 scalar. R decrypts all files made with that root, through per-file HKDF and wrapped keys in BK. It does not sign a contact card or authorize an identity replacement.

Store R only if the person chooses “Keep key on this phone”, off by default. If retained, Vault seals it under the distinct label `recovery-root:v1`; no alias, root, phrase, derived key or plaintext goes into logging, crash diagnostics or Android saved-instance state. Default manual exports require the external card/phrase again. Vault protection does not stop an attacker who can run the unlocked app from decrypting a retained root. Changing a screen PIN does not rotate R.

A new root affects new files only. Existing stolen files remain decryptable with the old root. Re-encrypting an owned copy does not revoke unseen copies. After phone loss or suspected secret exposure, make a fresh kit on the new phone. Never overwrite the last good kit before the new kit passes its restore check.

### 5.2 Words, pictures and QR are representations, not extra secrets

**VERIFIED:** BIP39 defines 128 entropy bits plus four SHA-256 checksum bits as twelve indices into a frozen 2,048-word list, and explicitly rejects treating human sentences as generated randomness [W3].

**INFERRED recommended representation:** adopt only that entropy-to-words encoding, **not** BIP39's wallet seed derivation or optional passphrase. Decode words back to the 16-byte R. Do not PBKDF2 the words with BIP39's 2,048-round setting, and never suggest entering a cryptocurrency wallet's existing phrase.

- Bundle the official English and French lists, with immutable indices and MIT attribution. A fetched raw list was 13,116 bytes English and 16,777 bytes French, each 2,048 words [W4, W5]. These are uncompressed resource bytes, not APK growth. Pin exact reviewed list revisions/hashes when implementing.
- Encode `R || first four bits of SHA256(R)` in MSB-first order, twelve 11-bit indices. Language chooses index-to-word mapping, not a different root. This differs from BIP39's word-string-to-seed rule. Print the language and “Phonemesh recovery v1” outside the secret words.
- Entry requires an explicit English/French list selector, Unicode NFKD and lowercase lookup, whitespace splitting, exact complete words, exact count and checksum. No guessed word correction or silently accepted typo. A valid four-bit checksum is only a typo aid, not proof that the correct secret was entered; the backup's AEAD tag decides.
- A 64-icon alternative needs **22 icons**, because the same 132-bit encoding uses 22 six-bit indices. The existing six-icon safety picture carries only 36 bits and is wholly unsuitable as a recovery secret. Do not shorten this to six “memorable” pictures.
- Defer icon-secret entry pending user testing. Reusing the frozen 64 safety drawings saves artwork, but a long ordered sequence is hard to copy, duplicates are normal, and safety pictures are publicly compared while this sequence must be secret. Never use the same screen or six-picture grouping as the safety comparison.
- For non-readers, prefer a paper card containing the QR, an unambiguous keep-private picture, and optional words. It is a possession secret, not a test of literacy. A printed QR can be photographed or copied; keep it away from the file and phone. Printing must be explicitly chosen, with a warning that printer/spool/cloud services may keep it. No automatic screenshot or gallery save.
- Spoken instructions may explain the steps. Reading the secret aloud is a separate explicit action, off by default, with a nearby-listener warning and only a verified on-device TTS path. No secret speech recognition, microphone capture or audio-file export in this version. If no private audio path is available, use QR/words silently. Ewe/Kabiyè word lists and secret pronunciation remain **UNKNOWN**, not implied by French support.

**New recovery-card byte layout:** `magic[8] = ASCII PMRK0001 | R[16] | check[4]`, 28 bytes. `check` is the first four bytes of SHA-256 of the first 24 bytes. It detects corruption, not forgery. Scanner text is `pmrk1:` followed by unpadded base64url of exactly those bytes; no URL, App Link, website, analytics or clipboard handler. Reject wrong magic, length, alphabet, non-canonical base64url, checksum or trailing bytes. The normal Add someone scanner must not interpret this as a public contact card. A dedicated recovery scanner handles it. Wipe mutable buffers best-effort; managed-runtime copies cannot be guaranteed erased.

### 5.3 Make a kit and prove it works

New labels below are proposals, not existing UI. Entry is **Settings** (`values/strings.xml:20`) → proposed **Recovery kit**.

1. Explain: “Save your people for another phone.” Then: “Your safety pictures will change.” Offer **Contacts only** and **Contacts and chats**. A short looping how-to picture shows two separate objects, file and key.
2. Generate R. Show the secret privately. Copy it to the chosen external medium; do not put it next to the backup automatically. Sensitive release screens use FLAG_SECURE, exclude clipboard/autofill and avoid secret accessibility announcements without a deliberate reveal. These are mitigations, not protection from another camera or a compromised OS.
3. Export the BK file through SAF. No radio or network send occurs simply by opening this screen.
4. Close and reopen the actual exported URI, ask for the external secret again, without using cached R, and run the entire authenticated restore parser into an isolated sandbox with networking/radio disabled. Display recovered counts and one chosen contact for the user to recognize. Do not install sandbox keys or overwrite the live account.
5. Mark **Checked on this phone** only if that read-back and full check succeed. Ask for a separate-phone rehearsal when possible. Only a real independent-device rehearsal earns **Checked on another phone**. A storage-provider acknowledgement, remembered phrase or same-process encryption round-trip does not earn either label.
6. Show the last successful check date and a reminder to keep a copy away from this phone. Refresh after contact changes; a reminder never means a new file was saved.

Proposed short copy, EN / FR, subject to translation and CopyRules checks:

| Purpose | English | French |
|---|---|---|
| Explain key | Keep this card private. | Gardez cette carte secrète. |
| Separation | Keep the file somewhere else. | Gardez le fichier ailleurs. |
| Practice | Try your saved copy now. | Essayez votre copie maintenant. |
| New device | This phone has a new identity. | Ce téléphone a une nouvelle identité. |
| Trust | Compare your pictures again. | Comparez encore vos images. |
| Missing secret | Find your recovery card. | Retrouvez votre carte de récupération. |

### 5.4 Restore and reconnect

Offer proposed **Restore a saved copy** before initial account naming, and as an import-history action in Settings. File authentication and validation happen before any account changes. An existing installation may import a read-only archive without replacing its live identity. A lost-phone restore on a fresh install generates a new normal AccountStore identity and new prekeys only at commit. Never delete existing Keystore aliases to make an import fit. Use a staging account namespace and commit marker as specified in BK.

Saved contacts live in a proposed `recovered_contacts` table, not live `contacts`; BK defines its minimal semantic fields. A missing session never becomes a sendable chat. The recovered list offers **Reconnect**. Prefer mutual fresh QR through existing **Show my code** and **Add someone** (`values/strings.xml:349-372`). If using a link, ask for a current public card; old cards have no issue date, and local save time cannot prove a prekey is still live. A bare 33-byte identity is not enough to construct a link. Import must not automatically send hellos to every saved contact.

On the other phone, a new identity is an ordinary new contact/request. Newer apps may offer a user-driven association to the old archived conversation, but never by matching a display name alone. Proposed “Kofi has a new phone” is shown as **your confirmation** after in-person verification, or as an unverified claim before it. The phrase/file is not evidence for a peer, because anyone with the file key can edit archive contents.

A plain text explanation can be sent after the new conversation starts, with explicit Send. It is ordinary message content, not a signed key-change protocol. Never broadcast the notice to the shared chat. No cross-signature from the old key is assumed after loss. Existing contacts keep the old account until they explicitly archive/block it. There is no global revocation and the stolen old phone may still message people who have not switched.

After mutual QR or deliberate safety comparison, current `met` can become true through the normal path. Keep provenance and old transcript separate from active message references. A newly accepted contact on v0.1.0 appears separately with the same chosen name; tell the user this is expected. Do not claim the old phone has been disabled.

### 5.5 Storage and wire changes

- **Radio:** none. Keep P-256, current contact cards, starts, ports and primary channel. BK bytes are never passed to Postman or the picture encoder.
- **Portable storage:** BK is canonical for `PMBK0001`, with purpose 0 for contacts-only, purpose 1 for history. No second recovery-container format.
- **Local storage:** add recovery configuration (optional sealed root, check status, check time, last-export URI with consent), a durable import transaction marker, saved-contact provenance and archive tables. Merge schema changes with concurrent features instead of assigning a conflicting next History version here.
- **Hub:** no changes in the first slice. A hub operator can hold an opaque file obtained manually, but the existing mailbox is not a backup API. BK defers a bounded Wi-Fi object store; the hub never receives R, plaintext or an automatic share.

### 5.6 Threat model and failure behavior

| Actor/event | Can do | Cannot be promised / required response |
|---|---|---|
| Radio outsider | Observe normal reconnect traffic and timing. | No secret/archive sent over LoRa. No claim to hide the fact of reconnecting. |
| Stolen locked phone | Attack Android/lockscreen; hold any locally cached encrypted archive. | Keystore extraction resistance is not proof against an unlocked or compromised OS. Existing keys do not require a per-use user-auth prompt (`AccountStore.kt:34-38`, `:198-201`). |
| Stolen unlocked phone | Read local messages; use identity; use retained R or open a currently revealed card. | Non-exportable keys are still usable on-device. Recovery is not remote wipe or account revocation. |
| Stolen backup only | See format, size, random identifier and chosen purpose; destroy or replay file. | Cannot decrypt under the intended cryptographic assumptions. No human-password guessing path exists in recommended profile. |
| Backup plus secret | Read and alter saved data and trust-history fields. | AEAD proves possession of a secret, not authorship. Reset trust; never treat restored met flags as current verification. |
| Malicious hub/friend holding file | Copy, delete, withhold, replay, substitute malformed bytes; see size/timing. | Cannot be relied on for freshness or availability. Keep an independent copy and test it. |
| Contact holding a share (deferred) | Withhold or corrupt it; collude/coerce with enough other holders. | Threshold shares do not authenticate holders, guarantee honesty or stop coercion. No hub majority or hidden escrow. |
| Coercion | Compel phone/card/share disclosure. | No duress PIN, plausible-deniability or coercion-resistance claim. Separate physical custody helps only within this limitation. |

Wrong secret and changed ciphertext share one error: “Key or file does not match.” Unsupported version and storage failure get separate actionable errors without including sensitive bytes. A missing/corrupt file, missing secret, invalidated Vault or missing prekey must not cause an automatic weaker import. Permit a fresh empty account and in-person re-add when recovery is impossible. Detect old files using locally known authenticated generations when available; a fresh phone cannot prove it has the latest copy.

### 5.7 Why not preserve a software identity now?

**INFERRED:** B could either back up an existing exportable software scalar or derive a new scalar from a random recovery root. These are different migrations. A root newly created today cannot regenerate an already random identity. A deterministic design needs domain-separated derivation and unbiased scalar rejection against the P-256 order, test vectors and explicit key-mode migration. Never use R directly as a scalar or reduce arbitrary bytes modulo the order without analysis.

Even on Android 12+, generating a software scalar and then importing it into Keystore only protects the operational copy. Anyone with the seed retains an exportable clone. The current protocol has no device epoch or revocation authority. Same-identity restored contacts would need fresh bilateral starts; existing link/hello shortcuts do not provide that. Ratchet state must still never be restored. Therefore B needs a separate approved continuity/reset protocol and migration, not a hidden “advanced” toggle in A.

### 5.8 Social recovery, honestly deferred

**INFERRED:** If later chosen, split R, not a Keystore identity, using a reviewed threshold scheme with frozen vectors and a specified format. A candidate is 2-of-3 with independent people, at most one share held by a hub. Store encrypted archive copies separately. No new share byte format is approved in this draft; all share inputs are rejected by version 1.

Before marking it ready, collect the threshold from real custody locations, reconstruct only on the owner's trusted device, and authenticate/parse the external file. Exercise a missing holder and a corrupt share. With three holders, test every allowed pair during enrollment or rehearsal. Ordinary Shamir shares lack malicious-share authentication; checksums only catch accidental damage, and archive AEAD only detects a wrong reconstruction. Do not call that cryptographic verifiable secret sharing. A selected scheme must bind shares to one set/threshold and prevent ambiguous mixing. Threshold compromise permits archive decryption. Re-sharing or deleting one holder's copy does not revoke old shares or stolen backups; change R and make a new kit. Added crypto code/library size and independent review remain owner gates.

## 6. Compatibility and migration

**INFERRED:** Updating v0.1.0 in place preserves all live keys and sessions; enabling export does not rotate them. Export software and Keystore accounts identically, with no private identity in either archive. Existing messages and radio format do not change. A replacement phone using this design has a new account; v0.1.0 contacts can add it normally but cannot show a dedicated verified replacement banner. Never send new control kinds to old phones.

An older app must refuse the new backup MIME/format rather than treating it as a picture or voice pack. Keep Android automatic backup disabled. Explicitly test OEM device-transfer cases and a restored SQLite database without Keystore keys: fail into a repair/recovery screen, not a crash, silent history deletion or alias replacement. Unknown private-group keys from another draft are excluded until that feature defines its own restore contract.

## 7. Test plan and implementation slices

**All tests below are proposed, NOT RUN.** No Gradle, emulator, adb, phone, radio, voice capture or playback was used for these drafts.

1. **JVM first, shared contract:** official entropy/word vectors; language index equivalence; malformed words/QR, every truncation, checksum collision limits; independent BK vectors; no six-picture secret path. Test a regenerated identity differs and no scalar/prekey/session is present in exported records. Target future `:core:test` with focused recovery/backup classes.
2. **JVM protocol regression:** source and replacement identities against a peer; fresh QR showing on both ends; link with a new identity; stale card and bare-key saved contact; explicit pending-contact promotion; verify no missing-session dead-end, duplicate ratchet keys, replay delivery or archive-driven sends. Include v0.1.0-equivalent peer behavior.
3. **Android integration after approval:** API 29/30 software path, API 31+ Keystore path and Keystore failure fallback; process death at each staging/commit step; no aliases lost; recovered met flags false; no auto network/radio traffic; no secret in logs, clipboard or saved state. Real backing security remains a physical-device gate.
4. **External-file rehearsal:** SAF destination becomes unavailable, a friend's copy is old, secret is wrong, SD card removed, disk full, URI permission revoked; do not show checked/saved status early. Restore without source phone, retained R or network.
5. **Emulator/screens:** EN/FR, large fonts, non-reading paper/QR walkthrough, wrong file, empty contacts, existing-account import, same-name impersonator and key-change warning. New gestures need owner-reviewed talking-picture mockups and short copy. No automatic voice playback in tests.
6. **Demo:** real restore parser and Postman with isolated synthetic data; Kofi returns on a new identity; Ama offers a fake same-name replacement; Yaw's saved card is stale. The demo can rehearse the flow but does not prove Keystore hardware, physical media survival or RF delivery. `docs/specs/2026-10-25-demo-build-design.md:14-46` requires release/demo separation and recurring coverage. Demo secrets must never enter production recovery stores.

Slices: (1) RC/BK picks and format review; (2) pure codecs and adversarial vectors; (3) contacts-only exporter, external read-back and sandbox; (4) new-phone staging and reconnection UX; (5) full BK history integration; (6) mixed-version/demo/size qualification. Social shares and hub object storage are separate later slices. **UNKNOWN:** elapsed engineering time, low-end device runtime and final APK growth. Owner must approve the measured ceiling increase; current ceilings are 16,575,000 arm64 and 14,715,000 armeabi-v7a (`android/gradle.properties:8-9`), with the brief reporting only about 1.6 KB prior arm64 headroom. The proposed 80,000-200,000 combined allowance requires a raise, not an assertion that it fits.

## 8. Decisions for the owner

### Blocking batch

**RC1. What does recovery promise?**
- A. **(recommended)** Recover saved information onto a new identity; keep non-exportable device keys and require contacts to verify again.
- B. Preserve identity through an exportable/software seed model; accept clone/impersonation risk and fund a separate reset/migration design.
- C. Keep only in-person re-add for now; no saved information recovery, so decision 12 remains outstanding.

**RC2. How does someone keep the secret?**
- A. **(recommended)** One random 128-bit root with paper QR and optional twelve English/French words; no memorization requirement.
- B. Add 22-icon secret entry immediately; reuse drawings but pay usability and confusion testing before release.
- C. Words only; simpler screens but excludes some non-readers without a trusted helper.

**RC3. What information is initially recovered?**
- A. **(recommended)** Contacts-only kit offered first, optional full BK archive under the same root; files remain separate snapshots.
- B. Always require a full chat backup; more exposure and storage, fewer choices.
- C. Contacts only this release; useful recovery, with full backup explicitly deferred.

**RC4. What counts as a working kit?**
- A. **(recommended)** Reopen the exported file and re-enter the external secret for a full sandbox restore; separately label another-phone rehearsal.
- B. Allow untested setup, clearly marked “Not checked”; fewer steps but higher false confidence.

**RC5. How does trust move to the new phone?**
- A. **(recommended)** Preserve historical met flags only; reset current trust, use explicit fresh QR/link and no automatic same-name merge.
- B. Preserve current met flags when contact public keys match; easier but the local identity changed and file holders can fabricate flags.

**RC6. Keep R on the source phone?**
- A. **(recommended)** Off by default; offer explicit Vault-sealed retention with the unlocked-phone warning.
- B. Always retain it; easier repeat backups, but phone compromise can expose all saved archives.
- C. Never retain it; smallest exposure, repeated external-card entry required.

### Deferred batch, not needed to approve A

**RC7. Social recovery custody?**
- A. **(recommended)** Defer; later review a 2-of-3 scheme, independent holders, at most one hub share, and actual threshold restore rehearsal.
- B. Include it now; add format/library/security review and malicious-share/availability testing to the release gate.
- C. Never offer it; only user-held secrets and in-person re-add.

**RC8. Spoken secrets and local-language representations?**
- A. **(recommended)** Private QR/words first; opt-in on-device secret reading only after privacy/accessibility qualification, no speech entry.
- B. Include spoken entry/read-out now; speech mistakes, overhearing and engine retention become release blockers.

## 9. Sources

Code references above were opened during this draft. `AccountStore.kt`, `History.kt`, `Accounts.kt` and `AppCore.kt` abbreviations mean `android/app/src/main/java/org/phonemesh/app/`; `WireV2.kt` means `android/core/src/main/kotlin/org/phonemesh/core/wire/`; `values/strings.xml` means `android/app/src/main/res/values/strings.xml`.

- [W1 Android Keystore](https://developer.android.com/privacy-and-security/keystore), opened: non-exportability versus hardware security level, permitted key use.
- [W2 Android SecretKeyFactory](https://developer.android.com/reference/javax/crypto/SecretKeyFactory), opened: PBKDF2-HMAC-SHA256 available from API 26; no guaranteed Argon2/scrypt entry. BK covers KDF choices and low-end costs.
- [W3 BIP39](https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039.mediawiki), opened: entropy, checksum, word mapping, distinction from seed derivation and MIT license. Its general checksum discussion must not override the four-bit checksum for a twelve-word encoding.
- [W4 English list](https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt) and [W5 French list](https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/french.txt), fetched and counted: 2,048 entries each; resource byte sizes above.
- [W6 HKDF, RFC 5869](https://www.rfc-editor.org/rfc/rfc5869.txt), opened; existing implementation `android/core/src/main/kotlin/org/phonemesh/core/crypto/Hkdf.kt:6-36`.
- [W7 Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), opened: user-selected providers, persisted permissions and provider-dependent operations. A successful write is not independent-device restore evidence.
