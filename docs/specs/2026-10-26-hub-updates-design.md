# Signed app and voice-pack updates through the local hub

Draft for the owner's decisions, 2026-10-26. No code before the picks.

## 1. Goal and who it helps

Let a person get a checked app update or speech pack from a nearby local hub without buying internet data. An operator brings the files once, over internet, a USB stick or another hub. Phones copy them over the hub's Wi-Fi, or slowly over its Bluetooth bridge. People choose what to copy and confirm each app installation. This helps anyone with little or no internet, including pilot users in Togo. Pictures and short spoken explanations must support people who do not read. Updating must never stop ordinary messaging just because a hub has old files.

**Claim labels:** **VERIFIED** means read in the cited code or opened primary source. **INFERRED** means a proposed design, calculation or planning estimate, not an implemented feature. **UNKNOWN** means evidence or an owner decision is still needed. All requirements below are **INFERRED / proposed** unless explicitly marked otherwise. No build, device, radio or emulator was used for this draft.

## 2. How it works today

Paths below are relative to the repository root.

| VERIFIED fact | Evidence and consequence |
|---|---|
| The release is `org.phonemesh.app`, versionCode 1, with separate arm64-v8a and armeabi-v7a APKs. | `android/app/build.gradle.kts:31-46`; `docs/releases/v0.1.0.md:11-17`. Use numeric version codes, not version-name string ordering. |
| Public releases have one owner-controlled APK signer. | `docs/publication.md:14-29`. Certificate SHA-256: `ef8557c6133a62388c5263cc26552d4ed3b036acce267a0b841a57bbdf5ecdac`. Development-signed phones are a separate line; do not uninstall them to change signer. |
| Pack URLs come from `PACK_BASE_URL`; production uses the published packs release. | `android/app/build.gradle.kts:25-46`; `android/app/src/main/java/org/phonemesh/app/PackDownloads.kt:34-66`. The download uses system DownloadManager and currently allows metered data. It is not a hub-only downloader. |
| The catalog pins the hash of **SHA256SUMS**, not the ZIP's whole-file hash. It also records ZIP size. | `android/core/src/main/kotlin/org/phonemesh/core/PackFormat.kt:7-9`; `PackStore.kt:152-176`; `PackDownloads.kt:153-162`. The new format needs separate ZIP and manifest hashes. |
| Existing published sizes differ from the scenario in this task. | `GeneratedPackCatalog.kt:6-9`: Whisper Tiny 60,136,101 bytes, Base 93,630,372; English voice 13,979,221; French voice 6,334,594. The 60-75 MB voice pack below is a future sizing scenario, not today's French pack. |
| A release rejects unknown packs and checks every extracted file against the pinned manifest. | `PackStore.kt:128-176,179-218,232-257`; `android/app/build.gradle.kts:42,69-73`. A new catalog key cannot be delivered as an unsigned pack and become trusted. |
| “From a friend” and “Share the app” already exist. Sharing rebuilds ZIPs from installed pack files and copies the installed APK. | `android/app/src/main/res/values/strings.xml:25,112`; `PackSharing.kt:29-61,94-144`. Rebuilt ZIP bytes can differ while manifest/files remain identical. `ShareProvider.kt:13-38` exposes only read-only shared files, with APK MIME support. It is not an updater or signature authority. |
| The hub already uses HTTP inside pinned TLS, over Wi-Fi or a BLE byte stream. | `android/hub/src/main/kotlin/org/phonemesh/hub/HubServer.kt:17-36,100-127`; `android/core/src/main/kotlin/org/phonemesh/core/hub/HubClient.kt:162-221`. The pin authenticates a particular hub, not a software publisher. |
| Current hub endpoints are `/hello`, `/send`, `/mail`. The client buffers replies up to 512 KiB. | `HubServer.kt:34-36`; `HubClient.kt:226-238,272-319`. Do not raise that mail limit to APK size. Add a separate bounded streaming update path. |
| Hub Wi-Fi sockets bind to the selected local Network; the endpoint is its IPv4 gateway. | `android/app/src/main/java/org/phonemesh/app/HubLink.kt:33-40`; `android/hub/README.md:17-31`. An arbitrary Mac on the same access point is not automatically a reachable hub. |
| The BLE bridge is not the Meshtastic radio. | `android/hub/README.md:17-19`; `android/core/src/main/kotlin/org/phonemesh/core/hub/HubGatt.kt:36-59`. It tunnels TLS directly to the JVM hub, with two streams and an 8,192-byte credit window. |
| LoRa is the wrong bulk transport. | `android/core/src/main/kotlin/org/phonemesh/core/wire/WireV2.kt:18`: 228-byte cap. `docs/design.md:48-56,74-75`: 200-byte legacy text limit and no audio transport. `android/app/src/main/java/org/phonemesh/app/AppCore.kt:2396`: primary channel 0. |
| No installation permission is presently declared among the app permissions. | `android/app/src/main/AndroidManifest.xml:1-27`. The in-app installer adds a new sensitive capability. |
| Size headroom is effectively exhausted. | `android/gradle.properties:8-9`: 16,575,000 and 14,715,000-byte ceilings. `docs/android-verification.md:14` records a 16,573,369-byte development-signed arm64 candidate and 1,631-byte headroom. This is not a measurement of this feature or a new public APK. |

**VERIFIED owner constraints:** P-256 and no extra crypto library; quantum-safe keys shelved; local-hub naming; updates and demo required. See `docs/specs/2026-10-14-accounts-design.md:17-18,60-74,433-443`. The current screen is **Village hub**, not yet **Local hub** (`values/strings.xml:464`). This draft follows the approved rename rather than silently claiming it shipped.

## 3. Options and their costs

Costs are **INFERRED** planning figures. Network bytes mean application data before HTTP/TLS overhead. Radio costs are additional to normal messaging. Effort is person-days, not elapsed calendar time.

| Option | Bytes, LoRa packets and airtime per event | Security | Phone and permission cost | Added compressed APK size | Effort | v0.1.0 |
|---|---|---|---|---|---|---|
| A. Signed catalog, hub cache, Wi-Fi first, optional BLE, in-app installer **(recommended)** | Catalog <=64 KiB; chosen APK 17-20 MB or pack 60-75 MB scenario; **0 LoRa packets / 0 airtime** | Publisher signature, exact artifact digest, persistent version floors; Android verifies APK signer | Streaming/hash/unzip work; storage; user choice; new install-source grant for APKs only | Target <=200,000 B total growth, not measured | 18-27 including qualification | One manual same-signer APK bootstrap, then full path |
| B. Same signed catalog and cache, export APK to Files for installation | Same bulk bytes; 0 LoRa | Same checks until export; Android final signer check; mutable exported file needs care | No install permission in Phonemesh; more navigation and a grant for the Files app instead | Target <=170,000 B, not measured | 16-25 | Familiar manual APK route; still needs bootstrap for new catalogs |
| C. APK updates only; keep all pack pins in each app | Same bulk bytes, 0 LoRa; every new pack set also needs an app transfer | Strong static pins; fewer key-management paths | More APK installs and consent steps for language additions | Target <=120,000 B, not measured | 10-16 | Existing packs work; new packs need a newer APK |
| D. Trust whatever the pinned hub serves | Same bytes and 0 LoRa | **Reject:** a malicious enrolled hub can substitute pack content; TLS is not publisher authorization | Smaller implementation, unacceptable trust expansion | Not budgeted | Not estimated | Does not solve old catalog trust |
| E. Add a small LoRa availability hint to A | Proposed 30-byte hint, 1 packet, about 0.641 s per hub transmission on code's LongFast model; relays extra | Untrusted hint only; spoofing and battery-drain risk | More discovery logic and unnecessary trips if files are absent | Additional size UNKNOWN | Additional 2-3 | Old apps ignore it; needs a separately allocated port |

### Transfer time: Bluetooth is a fallback, not a fast download

**INFERRED calculation, not a benchmark.** Decimal MB and kB/s. `minutes = bytes / application_goodput_bytes_per_second / 60`. Rates below are assumptions spanning poor to good local conditions, after framing overhead. Wi-Fi 625 and 2,500 kB/s correspond to 5 and 20 Mbit/s of useful payload. TLS setup, pauses, verification, unpacking and Android installation add time. Shared access divides capacity.

| Transport and assumed sustained useful rate | 17 MB APK | 20 MB APK | 60 MB pack | 75 MB pack |
|---|---:|---:|---:|---:|
| BLE GATT, poor 5 kB/s | 56.67 min | 66.67 min | 200.00 min | 250.00 min |
| BLE GATT, planning 20 kB/s | 14.17 min | 16.67 min | 50.00 min | 62.50 min |
| BLE GATT, optimistic 50 kB/s | 5.67 min | 6.67 min | 20.00 min | 25.00 min |
| Wi-Fi, 625 kB/s | 0.45 min | 0.53 min | 1.60 min | 2.00 min |
| Wi-Fi, 2,500 kB/s | 0.11 min | 0.13 min | 0.40 min | 0.50 min |

**VERIFIED framing:** `HubGatt.kt:29-59,90-91` gives a three-byte frame header, maximum 512-byte frame and 8,192-byte window. At ATT MTU 23, only 17 bytes remain per DATA frame; at the frame cap, 509 remain, before TLS overhead. A nominal Bluetooth PHY rate cannot be used as download goodput. **UNKNOWN:** sustained goodput, battery drain and notification fairness on the actual pilot phones and Mac bridge. No claim that today's bridge has passed a multi-megabyte run.

**INFERRED airtime calculation:** the brief's “about one second” is only an order-of-magnitude guide. Applying `android/hub/.../Airtime.kt:10-22` literally, with SF11, 250 kHz, coding rate 4/5, preamble 16 and 22 bytes of overhead, yields 0.641024 s for 30 application bytes and 2.115584 s for 228. This differs from the direct-message overhead in `core/LoraAirtime.kt:17-21`. Even pretending every 228-byte packet carried only file bytes, 20 MB needs at least 87,720 packets and 75 MB at least 328,948. These are lower bounds before file framing, retries, relays and duty-cycle waiting. **No update data, catalog or key object goes over LoRa.**

## 4. Recommendation

Choose A. Pin a separate offline P-256 catalog key in v0.1.1, with a separate offline recovery authority. Keep the existing APK signer. Make the hub a verified cache, not a signer. Phones independently verify all bytes. Prefer Wi-Fi; offer BLE only after showing its current estimate and letting the person cancel. Discover updates on local contact and show a badge in the app plus an operator status on the hub. Do not add a LoRa broadcast in this version.

Require a tap to download and another to install an APK. Never make app or speech updates a prerequisite for reading/sending existing messages. Failed updates leave the current app and packs usable. Keep the legacy online pack path separately labelled and explicitly chosen; it must never be an automatic fallback from **From local hub**.

## 5. Recommended design

### 5.1 Three distinct authorities

1. **APK release key:** Android's application identity, unchanged for v0.1.1. Metadata cannot override it. The public certificate pin above is a full 32-byte SHA-256, not its abbreviated display prefix.
2. **Catalog key C:** new offline P-256 key signing release catalogs. Its public key and identifier ship in v0.1.1, along with a signed baseline catalog. It authorizes new pack IDs and versions within formats/runtimes already supported by the app. It does not authorize executable libraries, arbitrary files or new model runtimes.
3. **Recovery key R:** distinct offline P-256 key, separately stored. Its public key ships in v0.1.1. It alone authorizes a new trust epoch and replacement C. A compromised C must not be able to authorize its own successor. Keep R fixed for v0.1.1; replacing R requires a trusted same-signer app release, not a self-signed hub key file.

Use Android/JDK `SHA256withECDSA` on secp256r1. Do not add Ed25519 or a crypto dependency from generic release guidance: the project's P-256 decision takes precedence. Keys are separate from account keys and hub TLS keys. No private signing key belongs on a hub, phone, release server, CI runner or USB distribution bundle. Keep encrypted offline backups; test recovery without publishing private material.

**VERIFIED platform boundary:** Android checks APK signatures; compatible signing lineage can support rotation on Android 9+ [W1,W4]. “Same signer” here means the current unchanged signer. Supporting APK lineage later requires an explicit migration design and testing, not merely allowing a new certificate in a catalog.

### 5.2 Signed metadata wire format

Propose a small binary format rather than signing reserialized JSON. It is not a LoRa packet format. All integers are unsigned big-endian; reject values above the implementation's signed integer range, overflow, truncation, extra trailing bytes and unknown schema/kind/flags. Limits apply before allocation. Exact bytes are signed and archived.

**Envelope, 76 bytes plus payload:**

| Field | Bytes | Rule |
|---|---:|---|
| Magic | 4 | ASCII `PMUP` |
| Schema | 1 | 1 |
| Object kind | 1 | 1 = catalog; 2 = recovery policy |
| Signing key ID | 2 | Local allowlisted identifier; never a key lookup URL |
| Payload length | 4 | Whole envelope <=65,536 bytes |
| Payload | n | Layout below |
| ECDSA signature | 64 | Raw r(32) followed by s(32), both in range; low-S required |

Sign `ASCII("phonemesh-update-v1\0") || envelope_header || payload` using SHA-256 with ECDSA. Convert strictly between raw signature form and platform DER at the JCA boundary. Reject non-minimal DER during tooling verification. Signature algorithms are not negotiable in schema 1. Validate public points on secp256r1; reject infinity/off-curve keys. Public keys use SEC1 uncompressed form, 65 bytes.

**Catalog payload:** fixed 43-byte header, bounded entries, then revocations.

| Header field, in order | Bytes |
|---|---:|
| Channel: 1 = public production | 1 |
| Trust epoch | 8 |
| Catalog sequence, increasing within epoch | 8 |
| Issued-at UTC seconds | 8 |
| Review-after UTC seconds, advisory | 8 |
| Minimum catalog-reader app versionCode | 8 |
| Entry count, 0..128 | 2 |

Each entry has the following fixed prefix, **150 bytes**, followed by its three strings:

| Entry field, in order | Bytes | Meaning |
|---|---:|---|
| Kind | 1 | 1 APK, 2 recognition engine, 3 voice |
| ID length; version-label length; ABI length | 1 + 1 + 1 | ID 1..64, label 1..64, ABI 0..32 |
| Artifact version | 8 | APK versionCode or pack version; positive |
| Minimum installed app versionCode | 8 | Minimum migration/runtime compatibility |
| Maximum installed app versionCode | 8 | 0 = no upper bound; packs can constrain runtime support |
| Minimum Android API | 4 | Must match APK/pack runtime requirements |
| Pack format | 2 | 2 for current packs; 0 for APK |
| Artifact bytes | 8 | Exact published archive length |
| Artifact SHA-256 | 32 | Exact distributed APK/ZIP digest |
| Expanded bytes | 8 | Exact sum of extracted regular-file sizes for packs; 0 for APK |
| File count | 4 | Exact extracted regular-file count including SHA256SUMS; 0 for APK |
| SHA256SUMS digest | 32 | Pack content identity; zero for APK |
| APK signer certificate digest | 32 | Public release certificate; zero for packs |
| ID, version label, ABI | variable | APK ID must be `org.phonemesh.app`; pack ID uses existing pattern; ABI ASCII allowlist |

IDs/labels are bounded UTF-8 with no controls, bidi overrides or markup; ABI values are the known shipped variants, or empty for ABI-independent packs. APK artifact length is capped at 64 MiB; pack archive length at 128 MiB, independently of signed claims. Pack expanded bytes and file count also stay within the existing 512 MiB and 5,000-entry extraction limits. Pack versions must fit the existing positive Kotlin Int representation; an APK version must fit the platform's positive longVersionCode. No URLs or filesystem paths appear in entries. Derive object paths solely from verified digests. Check embedded `pack.properties` identity/version/kind against the entry. Check APK package, version, ABI and minSdk against the entry. A valid publisher signature does not relax pack path/file/runtime rules.

After entries: `revoked_count:u16` (0..256), followed by that many 32-byte artifact digests. Sort entries by `(kind,id,artifactVersion,abi)` and revocations by digest; reject duplicate tuples, conflicting same-version content and duplicates in the revoke list. Baseline and later catalogs retain entries for supported older clients, not just the newest runtime. Catalogs are complete release-policy snapshots; a hub may hold only some listed objects. Availability is not publisher authorization.

**Recovery-policy payload, 87 bytes:** `newEpoch:u64 | newCatalogKeyId:u16 | newCatalogPublicKey:65 | minimumCatalogSequence:u64 | minimumReaderVersion:u32`. Kind 2 must verify under fixed R, never under C. Epoch strictly increases. A later recovery policy can be verified directly under R, so a phone does not need every missed intermediate rotation. New C must differ from R and the revoked C. A root object incompatible with the reader stops update acceptance and explains that a compatible app is needed; it does not disable messaging.

**Calculated size examples:** six entries with 28-byte IDs, 12-byte version labels, 7-byte ABI strings and no revocations produce a 1,303-byte envelope. At all specified entry/string/revocation maxima, a catalog is 47,993 bytes, below the 65,536-byte cap. Actual release catalog bytes remain **UNKNOWN**. These are schema calculations, not captured release artifacts.

### 5.3 Baseline, monotonic state and publication order

The APK includes public C and R, a signed baseline catalog, and minimum supported trust epoch/sequence. First launch installs these into app-private storage only when no trust state exists. Never overwrite corrupt existing trust state with baseline data to make it work. Show a recovery error; preserve installed speech and messaging. The baseline contains pack metadata only, not models or another APK.

Persist one transactional update-state record containing: epoch, active C, recovery-policy bytes/digest, highest accepted sequence and catalog digest, accepted catalog bytes, installed artifact version floors and active pack pointers. Use a journal or a transaction with crash-tested ordering. Sync object data before publishing state; use same-filesystem atomic rename and directory sync where supported. If platform storage cannot provide the stated durability, document the weaker guarantee and test recovery; do not call a rename alone power-loss-safe.

Rules:

- A lower epoch or sequence is rejected. Same epoch/sequence plus different signed content is a conflict, not “latest wins”. Re-fetching identical signed payload is idempotent.
- Commit a verified new catalog and its high-water mark together, even if the hub lacks payloads. Keep existing installed packs usable. The publisher must keep compatible entries in the new snapshot so old phones are not stranded by metadata alone.
- Artifact version floors advance only on successful installation/activation, not when the user merely sees an offer. Keep pack floors after pack removal. Same version with different content is rejected; publisher fixes always use a new version.
- APK candidates must exceed installed versionCode and satisfy the catalog's minimum installed version. An intermediate update must be offered if migration requires one. Never use downgrade flags, uninstall or clear data as a repair.
- Recovery-policy acceptance changes epoch, C and catalog floor atomically and invalidates pending offers from the old epoch. No fallback to the old key after restart. Existing installed packs remain usable unless an authenticated revocation applies.
- Revalidate the latest stored policy before activation or APK handoff. A paused file revoked while downloading must not become active.

Local monotonic state resists a malicious hub, not root access, a full filesystem rollback, clearing app data or reinstallation. Android retains the installed APK version while app data is merely cleared, but pack/catalog history can be lost. Backup/recovery designs must not restore older update-state over newer local floors. Do not advertise hardware rollback protection.

Catalog epoch recovery does not automatically reset installed artifact-version floors. If a compromised C caused installation of a pack at the maximum accepted version, rotation alone cannot make a lower corrective version acceptable. Quarantine that pack, preserve text messaging, and deliver a same-signer recovery APK with an explicit, narrowly scoped migration for the affected pack ID/digest/floor. Never clear all trust state to recover one pack. This exceptional recovery code requires independent review and migration tests; ordinary hub metadata cannot request it.

**Offline freshness limit:** signatures prove who authorized bytes, not that no newer catalog exists. A new/offline phone can be kept on an old valid release by a withholding hub. The signed baseline and seen-version floors limit replay but do not prove global freshness. Review-after is advisory, not an expiry that disables a disconnected village. With an implausible clock, show “Update date unavailable”; never silently trust the hub's clock. An operator periodically brings a recent signed catalog by an independent route. Expired review dates warn but do not block verified compatible offline updates under this policy.

### 5.4 How updates reach and live on a hub

One import/verifier path accepts three sources:

- **Internet:** operator chooses a release/channel and sees total bytes before downloading. An optional operator setting permits scheduled checks, off by default. Hub fetches from a configured allowlist of publisher HTTPS endpoints. Redirects, if required by hosting, stay on an explicit host allowlist. The signed metadata, not a URL or TLS certificate, authorizes content.
- **USB stick:** operator chooses an import directory. No autorun, scripts or executable installers. Accept `catalog.pmup`, optional `recovery.pmup`, and `objects/<lowercase-sha256>`. Treat all filenames, links, lengths and bytes as hostile. Ignore unrelated stick contents; do not recursively import arbitrary directories.
- **Another hub:** explicit operator pull on local Wi-Fi/LAN, or export/import of the same directory on USB. It is not a new mesh protocol. Verify every object again. Do not auto-sync account mail, hub credentials or user download histories. Bulk hub-to-hub BLE is deferred.

Proposed hub layout under its existing private data directory:

```text
updates/
  trust-state.bin
  catalogs/<payload-sha256>.pmup
  policies/<payload-sha256>.pmup
  objects/<artifact-sha256>
  partial/<artifact-sha256>.part
  partial/<artifact-sha256>.state
  active-index.bin
```

The active index is local cache bookkeeping, never a signature substitute. Import in staging, bound all reads, verify policy/catalog, stream-hash and size-check objects, then atomically publish ready objects. Do not serve `.part` files or mutate ready bytes. Keep catalog and root records even when evicting payloads. Retain current and previous compatible releases by default, with a proposed 2 GB object quota and 100 MB reserved for mail/operating headroom. These are configurable caps, not a claim about pilot hardware capacity. Refuse an import that cannot fit; never evict mail to make room for a pack. Keep upstream notices and corresponding-source material in operator export packages; distributing APKs does not remove AGPL/source or model-license obligations.

Expose read-only endpoints on the existing pinned TLS service:

| Proposed route | Response and bounds |
|---|---|
| `GET /updates/v1/catalog` | Exact active signed catalog, <=64 KiB, or 404 |
| `GET /updates/v1/recovery` | Latest recovery policy, or 404 if baseline epoch |
| `GET /updates/v1/objects/<digest>` | Exact ready object; bounded Range supported |
| `HEAD /updates/v1/objects/<digest>` | Size and digest ETag; unauthenticated publisher-wise, only an availability hint |

Keep `/hello`, `/send` and `/mail` byte-compatible. Old hubs may return 404 or an unrelated body for an unknown path; new phones require `PMUP` and bounded parsing, not just HTTP 200. No internet redirect from a phone update endpoint. Reject redirects, content encoding, ambiguous duplicate length/range headers, multipart ranges, invalid digest paths and ranges outside the object. Set `Content-Length`, `Accept-Ranges: bytes`, and ETag to the quoted full digest. A range reply must be 206 with exact `Content-Range` and matching total length; 416 is a bounded restart/recheck, not a crash.

Separate transfer admission from the current 32-worker mail pool (`HubServer.kt:28-29`): proposal, two concurrent bulk transfers globally and one per client, with additional requests receiving 429 and a retry delay. Mail gets priority; Bluetooth bulk pauses at chunk boundaries when mail needs the link. Retain the bridge's two-stream ceiling; do not consume both for parallel download parts. Logging contains aggregate bytes/status only, not recipient codes, account identifiers or persistent per-phone pack histories. The hub can still observe requested packs and timing.

### 5.5 Phone transport, partial downloads and storage

Create an update downloader distinct from `PackDownloads` and from message route selection. Its transport type can represent only `PinnedHubWifi` or `PinnedHubBluetooth`. It has no `MeshRadio.send`, `/send`, default-network socket factory or DownloadManager dependency. Never reuse the messaging fallback “Wi-Fi, BLE, radio” for updates.

Wi-Fi uses the enrolled hub's local Network socket factory and TLS pin. Connection loss pauses; it cannot fall through to mobile data, another Wi-Fi or a public CDN. BLE goes to the hub bridge, never the LoRa radio's GATT service. A Wi-Fi-to-BLE switch asks first because it can turn minutes into hours. A BLE-to-Wi-Fi resume may proceed after normal network consent and reauthentication, fetching the same digest. On a different enrolled hub, independently verify its pin and current signed metadata, then resume by digest if still authorized.

Use at most 256 KiB per requested range, streamed through a <=64 KiB buffer. Metadata is separately bounded. Record expected digest, length, epoch/sequence, durable byte offset and source type in a sidecar. Flush data before advancing the durable offset. On restart truncate any unsynced tail to that offset. A malicious sidecar or inconsistent offset causes a fresh bounded download, not trust in cached bytes. Completed files are SHA-256 checked in full before opening/extraction. Resume only when the server returns the requested offset, length, total and ETag. If a server returns 200 for a nonzero offset, truncate/restart after explaining the restart; never append a whole file to a partial one.

Use bounded inactivity and per-range deadlines suited to measured throughput, not the mail client's short whole-request deadline. Retries stop after three automatic failures in a foreground session, with an explicit Resume action. Partial bytes survive leaving the screen; automatic transfer pauses when the app leaves the foreground in the first version. No new background service or notification permission is required for this feature. Show progress immediately; hash/unzip phases have their own progress/status. Permit pause/cancel; Cancel may offer removal of partial bytes, while Pause keeps them.

**Space admission:**

- Pack: remaining ZIP + signed expanded size + 20 MB reserve, in addition to the already installed pack. Require expanded size/file count to match during bounded extraction and preserve current path and 512 MiB total/256 MiB single-file ceilings unless separately approved (`PackStore.kt:179-218`). Example: a 75 MB ZIP with 150 MB expansion requires 245 MB free before starting from zero. Expanded size for future packs is **UNKNOWN**, not assumed equal to ZIP size.
- APK: allow for the downloaded APK plus PackageInstaller staging plus a 50 MB reserve. A 20 MB APK therefore needs a planning allowance of 90 MB free. Android may require more for native extraction/dex work; handle its insufficient-space result without deleting user data.
- No full-object memory buffer. Hashing reads the artifact once; pack extraction checks each file. Energy/time are **UNKNOWN** until measured on low-end phones.

For packs, retain the old active directory while validating and staging the new one. Publish the new active pointer and version floor together only after structural/runtime-open checks succeed. Failure before commit leaves the old version active: that is failed activation, not an authorized downgrade. A bad pack found after committed activation needs a publisher-signed higher-version corrective pack; do not silently reinstall an old pack. A correction may contain known-good old model bytes but must have a new pack version and manifest. Keep one inactive prior copy for diagnosis until cleanup, never auto-select it. Coordinate this with existing `PackStore` behavior, which currently deletes older copies after rename (`PackStore.kt:253-257`).

### 5.6 “From a friend” and dynamic pack trust

For v0.1.1 recipients, share the signed catalog/recovery-policy sidecars as well as the pack. Import metadata first using exactly the same verification rules. Two modes stay distinct:

- **Hub/publisher archive:** enforce exact signed ZIP length and digest, then manifest/files.
- **Rebuilt friend archive:** whole ZIP bytes need not match the publisher artifact. Use the signed entry's SHA256SUMS digest, exact extracted file set/hashes, identity/version and expanded bounds. Cap the archive at `min(zipBytes + floor(zipBytes / 8), 128 MiB)`, allowing the existing one-eighth recompression margin per authorized entry without an unbounded import. Never pretend a rebuilt ZIP passed the publisher ZIP hash.

Do not set `ALLOW_UNKNOWN_PACKS` or trust a key supplied alongside a pack. Public release mode remains fail-closed. Unsupported format, recognizer or language support still requires an app update. A signature alone cannot add Ewe or Kabiyè support to the current runtime (`PackStore.kt:43-45,95-111`). Language capability/quality is decided by the separate language spec.

### 5.7 APK installation and permission cost on Android 10-15

After full digest verification, inspect APK metadata and signing information using platform APIs. Require public package ID, correct ABI, higher versionCode and the pinned release certificate. Metadata inspection is not a replacement for Android's full APK signature validation. Copy only the verified immutable private file into a single-package PackageInstaller session; never use an arbitrary URI from the hub or share sheet. Revalidate digest/policy at handoff, avoid mutable public storage, and keep a durable pending-install record.

Declare `REQUEST_INSTALL_PACKAGES` only if UP2A is approved. Call `canRequestPackageInstalls()` [W2]. It is special install-source access, not the microphone-style runtime permission dialog. Open `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for `package:org.phonemesh.app` only after the person taps Install [W3]. Returning from Settings is not proof of a grant: recheck. Do not request it for voice packs, at first launch, or just to show an update badge.

| Android | VERIFIED API/source behavior | Proposed experience and qualification limit |
|---|---|---|
| 10-11, API 29-30 | Per-source authorization exists from API 26 [W2,W3]. Android 10 AOSP has the warning “For your security, your phone is not allowed to install unknown apps from this source.” and a Settings action [W5]. | First use: explanation, Settings, Phonemesh's source toggle, return, system update confirmation. Android 10 AOSP asks whether to install an update and labels its action Install. Exact OEM layout needs screenshots. |
| 12-13, API 31-33 | PackageInstaller exposes `setRequireUserAction` from API 31 [W6]. | Explicitly set `USER_ACTION_REQUIRED`; do not rely on defaults or self-update exemptions. Same source grant, then system consent for every update. Do not promise identical text across OEMs. |
| 14-15, API 34-35 | Same APIs; Android 15 AOSP includes “Do you want to update this app?”, Update, and a possible different-update-source reminder [W7]. | Show and respect system prompts, including ownership/verification warnings. Never request update ownership or silent-install privileges. Test cancel, blocked policy and source-change cases. |

On Android 10-11 use the ordinary unprivileged installer flow, with no device-owner/shell privileges. On 12+ explicitly require user action. Handle `STATUS_PENDING_USER_ACTION` only while the user is in the requested flow. Persist progress across process death and app replacement. On next launch query installed package/version/signer and reconcile the pending session. Only that observation can say “App updated”; handing a file to the installer or receiving a preliminary callback cannot.

**Permission cost:** one new sensitive manifest capability, a per-source trust decision on first use or after revocation, and system confirmation on every APK install. The grant can remain enabled until the user turns it off, so explain the scope and offer a later route back to the setting. No blanket “disable security” instruction, no Accessibility automation, and no bypass of Play Protect or administrator restrictions. A denied grant leaves the APK ready and messaging usable. Managed devices or OEM verification may block installation; **UNKNOWN** exact offline behavior on each pilot device. Do not promise the Android system itself will never make a verification network request. Phonemesh's downloader never uses mobile data; qualification must also test installation with internet physically unavailable.

**Distribution cost:** Google Play treats this permission as high-risk and requires an eligible core use and declaration [W8]. Its policy explicitly guards against non-Play self-updating. This sideloaded pilot design is not a claim of Play eligibility. A future Play build needs a separate reviewed distribution policy, likely without this self-update path, not a renamed permission or concealed updater. Option B avoids this permission in Phonemesh but moves install-source navigation to Files; it does not remove Android consent.

### 5.8 Screens, words and announcements

Entry: Settings → **Local hub** → proposed **Updates**. On v0.1.0 instructions still say **Village hub**. Add a small update badge only after a signed, compatible offer is verified; do not infer an update from unsigned HTTP headers. Check metadata when the update screen opens or once on foreground hub contact, rate-limited to once per hub per hour. Do not require background discovery.

Show app and language/engine rows separately: installed version, offered version, size, route, progress, and estimated remaining time once measured. Icons distinguish phone/app, speech pack, Wi-Fi, Bluetooth, pause and checked result. Color is not the only signal. Speak short explanations only when asked and only with an installed voice; first-run without speech packs must still work visually. Do not add bundled voice recordings/models to solve this feature's UI.

Proposed new copy, not claimed shipped strings:

| State/action | English | French |
|---|---|---|
| Section | Updates | Mises à jour |
| Source | From local hub | Depuis le relais local |
| Ready offer | An update is ready. | Une mise à jour est disponible. |
| Slow route | Bluetooth is slow. Use Wi-Fi? | Bluetooth est lent. Utiliser le Wi-Fi ? |
| Network loss | Hub disconnected. Copy paused. | Relais déconnecté. Copie en pause. |
| Data explanation | This copy uses no mobile data. | Cette copie n'utilise pas de données mobiles. |
| Grant explanation | Allow Phonemesh to request app updates. | Autorisez Phonemesh à demander des mises à jour. |
| Verification failure | File not trusted. Nothing installed. | Fichier non fiable. Rien n'est installé. |
| Space failure | Not enough space. Free some space. | Espace insuffisant. Libérez de la place. |
| Old hub | This hub has no updates yet. | Aucune mise à jour sur ce relais. |
| Actions | Download; Pause; Resume; Install; Not now | Télécharger ; Pause ; Reprendre ; Installer ; Pas maintenant |

All new copy must pass `CopyRules` and EN/FR accessibility review before implementation acceptance. The data sentence describes the copy, not a promise about Android/Play Protect. Confirmation/error appears beside the relevant action. Include an illustrated “copy, check, Android asks” sequence; system screens use Android's words and language, not app-translated imitations. Do not copy an OEM prompt into a custom dialog that looks like the real security prompt.

The current JVM hub prints status rather than having a general graphical screen (`android/hub/README.md:79-85`). Add a local operator status command/view showing verified release, available/missing files, import progress, quota and “last brought here” date. A paper notice may tell people to visit; it conveys no cryptographic trust. Recommended announcements cost **zero LoRa bytes**.

Deferred hint only: a future private port, not 300-303, could carry `schema:u8 | kind:u8 | hubHint:8 | epoch:u32 | sequence:u64 | catalogDigestPrefix:8`, exactly 30 bytes. No download URL, name, automatic acknowledgment or automatic fetch. It is spoofable; authenticate catalog over the local link. If later approved, cap to four broadcasts per hub per day, no requested ACK or app-level relaying, existing duty budget and primary channel 0. Code-model airtime is about 2.564 s/day before firmware rebroadcasts. Port allocation, dedupe, relay settings and country legality remain a separate decision; this draft does not allocate a port.

### 5.9 Threat model and failure cases

| Actor/failure | What the design prevents | What remains possible and response |
|---|---|---|
| Outsider on Wi-Fi/BLE | Enrolled TLS pin rejects impersonation; publisher signatures and hashes reject substituted update bytes. | Jamming, traffic timing/size observation, blocked downloads. Pause and retain partial data. |
| Bad or malicious enrolled hub | Cannot forge C/R or APK signatures; cannot activate arbitrary content, old seen catalog versions, cross-ABI/package APKs or tampered ranges. | Can withhold new releases/revocations, lie about availability, deliver old still-acceptable releases to fresh phones, learn pack choices and waste bounded transfer work. Another hub/USB route is needed. |
| Malicious contact or USB supplier | Account friendship and USB possession confer no release authority. Same verifier, path rules and byte limits apply. | Can send rubbish or a large unwanted file. Explicit import, quota and cancellation limit harm. |
| Lost locked phone | No release private keys are present to steal; theft does not grant catalog-signing authority. | Local state protection depends on Android/lock state. Root/unlocked access can alter trust state, consent to installs or erase data. No hardware anti-rollback claim. |
| Catalog key C stolen | Does not sign an APK or rotate itself under R. Existing APK signer still gates app code. | Can sign malicious packs within format bounds, revoke packs or fast-forward catalog sequence. Treat signed models as potentially harmful parser input, not harmless data. R rotates to a higher epoch, resets sequence authority and revokes affected artifacts. Isolated phones remain exposed until recovery arrives. |
| R stolen | Still cannot sign APK code with the release key. | Can replace C. Stop accepting unreviewed update media; distribute same-signer app with replacement R and repaired baseline by an independent route. Recovery depends on learning of compromise. |
| APK key stolen | C still blocks unauthorized APKs within this updater. | Android may accept attacker-signed APKs installed outside it. Metadata cannot revoke an Android identity globally. Emergency signer-lineage or new-package/data-migration plan needs owner security review. No promise that loss/compromise is repaired by a catalog. |
| Signing keys lost but not stolen | Offline backups and recovery key cover C loss. | Losing APK key without an accepted lineage can prevent in-place updates. Losing R needs a same-signer APK to replace trust. Never suggest uninstalling as routine recovery. |
| Disk full, interrupted USB copy, power/process loss | Unverified staging never becomes active; old app/pack remains selected until commit. | Local storage can fail. Show precise space/connection/storage errors; resume or reimport. Corrupt trust state fails closed for updates. |
| Bad but authentic release | Publisher identity and compatibility are checked, not software quality. | Stage, test and roll forward. APK repair uses a higher versionCode with compatible data migrations. No app/DB downgrade or automatic old-pack activation after commit. |
| Missing intermediate app/runtime | Minimum version prevents incompatible pack/model loading or unsupported DB migration. | Show “Update the app first”; hub must carry the intermediate release. Continue existing messaging. |

A newly authenticated revocation prevents install or selection of that digest. For an installed revoked pack, stop loading it, keep text messaging available, explain that speech needs a replacement and retain files only for removal/diagnosis. Do not remotely disable the installed app or erase messages. A revocation delivered by a compromised C can deny speech until recovery; that is an explicit remaining risk. Never erase APK signing trust or user accounts from a hub message.

## 6. Compatibility and migration

| Phone/hub combination | Behavior |
|---|---|
| v0.1.0 phone, new hub | Existing mail routes remain unchanged. It cannot call the new updater or verify C/R. It can still import packs whose SHA256SUMS is already in its compiled catalog. |
| v0.1.1 phone, old hub | New endpoint absence means updates unavailable, not a failed mail hub. Existing messages/packs continue working. |
| v0.1.1 phone, new hub | Signed local update path; same wire v2 mail, no new LoRa behavior. |
| v0.1.0 phone receives a new unknown voice pack | Reject as unknown, as today. Do not lower verification or replace its catalog through a file trick. |
| Public release phone receives public v0.1.1 APK manually | Android verifies package/signer; user grants the actual installer source and confirms. After successful update, pinned C/R arrive through the trusted APK. |
| Development/demo phone receives public APK | Reject wrong signer/package line. Never uninstall or clear data. Demo has a separate app ID and debug signing policy. |

Bootstrap procedure: the operator brings the same-signer v0.1.1 APK to a phone through existing **Share the app** from a verified upgraded phone, Files/USB, or a trusted local transfer. The old app does not perform new-catalog checks; Android's existing signer is the bridge of trust. The operator verifies the release artifact before sharing. Keep a known-good upgrade source and printed illustrated instructions available. A brand-new phone with no installed Phonemesh has no installed signer to compare: trusted first-install distribution remains a separate bootstrap requirement, not solved by an arbitrary APK saying “Phonemesh”.

Ship v0.1.1 with a versionCode above 1, same release signer, preserved accounts/history, and the original compiled pack entries as signed baseline metadata. Do not rebuild the baseline from runtime untrusted downloads. Keep new pack metadata compatible with the accepted runtime, and do not claim local-language support merely from adding a catalog entry. Preserve legacy pack sharing with old receivers by exporting their existing format unchanged; sidecars for new receivers are separate files, not unexpected members inserted into old format-2 ZIPs.

## 7. Tests, release evidence and effort

These are future acceptance gates, **not tests run for this draft**. No Gradle, emulator, adb, hardware or signing operations are authorized here.

### JVM first

- Byte-exact catalog/recovery fixtures cross-checked with an independent P-256 verifier; malformed/truncated inputs, trailing bytes, unknown IDs/schema/kind, bad points, raw/DER conversion, non-low-S, signature stripping and altered headers.
- Limits before allocation; overflow; duplicate/conflicting entries; wrong channel/package/ABI/format/runtime; strict no signed-URL interpretation; max-size catalog; source-to-catalog canonical byte binding.
- Replay, same-sequence conflict, pack same-version conflict, removal/reinstall floors, corrupt state, fresh packaged bootstrap, epoch rotation with skipped intermediate epochs, compromised C fast-forward followed by R recovery, old-key rejection after restart.
- Inject process/disk failures around every state/object/pointer write. The result must be a recoverable old state or complete new state, never new active bytes with a lower floor. Verify root-state and release-state ordering together.
- Hub internet/USB/other-hub import use the same verifier; reject symlinks, traversal, oversize/missing/duplicate objects; no partially served file; quota never evicts mail; range/ETag/HTTP parser tests including 200-on-resume and late cancellation.
- Drop/reconnect repeatedly during multi-range copy; resume across two hubs by exact digest; bad final hash cannot activate. Pack archive hash and rebuilt-friend manifest verification must have separate tests.
- Network boundary test proves no DownloadManager, default/mobile socket, arbitrary redirect, radio fallback or `/send` reachable from update transfer. Count **zero update LoRa calls** in all failure paths.
- Scheduling tests demonstrate mail priority and the two-stream BLE ceiling under downloads, disconnects and 429 responses.

### Android/emulator and demo, after approval

- API 29, 30, 31, 33, 34 and 35: source grant denied/revoked/granted, Settings return, install/cancel, package replacement/process death, wrong signer/package/ABI/version, missing intermediate version, out-of-space and system-policy block. Explicit user consent on each install. Include API 32 in compatibility coverage if it differs; emulator screenshots are not OEM proof.
- Actual v0.1.0-to-v0.1.1 same-signer upgrade must retain account keys, contacts, messages, installed packs and local history. Do not substitute a fresh install or empty test database for migration evidence.
- Screens in English/French, large text, dark/light, TalkBack descriptions and read-free pictorial flow; no spoken prompts unless requested. First-run with no speech pack remains usable.
- Demo simulates a signed offer, missing file, corrupt signature, stale catalog, storage failure, slow BLE, pause/resume, recovery and consent cancellation. It runs the real verifier with **demo-only** fixture roots, never a release bypass. Production rejects demo metadata; demo never installs the public package. Demo screenshots and installer-state simulation do not prove Android installation or real radio/network behavior (`docs/specs/2026-10-25-demo-build-design.md:14-22,42-46`).

### Physical and release gates, separately authorized

- Measure real goodput and battery/temperature for 17-20 MB APK and 60-75 MB pack transfers on low-end Android 10 and newer pilot-class phones, both Wi-Fi and BLE; include MTU 23, weak signal, screen exit, incoming mail and hub restart. Publish measured minutes separately from this planning table.
- With mobile internet unavailable, prove source-bound hub transfer and the complete OS install flow. With mobile data enabled but hub lost, prove the app sends no update payload over mobile. Observe any OS verification traffic separately; do not hide it under the app's network claim.
- Freeze exact source candidate, build both ABI APKs, measure signed sizes, verify release signer/package/version, extract packaged baseline and public roots, and prove ordinary installed bootstrap. Keep private keys outside repository/CI/logs. No permissive debug result qualifies release.
- Owner offline signing binds APK and pack artifact digests to a source-bound catalog. Independent verification checks metadata and every artifact, then owner-authorized publication/import. If internet hosting is claimed, independently fetch those exact bytes and reverify them. A locally signed bundle is not proof of hosted publication or field readiness.
- Package/export inventory includes catalogs, recovery policy when applicable, notices, corresponding source and the approved binaries; contains no private keys, accounts, hub cards or operator logs. Maintain known-good offline recovery media separately from the newest payloads.

### Effort and APK-size budget

| Slice | Proposed deliverable | INFERRED effort |
|---|---|---:|
| 1 | Metadata codec, P-256 verifier, packaged baseline, durable floors and recovery tests | 4-5 days |
| 2 | Hub intake, quota, immutable objects, range serving, operator status and export | 3-4 days |
| 3 | Local-only phone streaming, resume, fairness, dynamic pack authorization | 4-6 days |
| 4 | Installer and EN/FR pictorial UI; migration; friend-sidecar path | 3-5 days |
| 5 | Demo coverage, API matrix, physical measurements and release evidence | 4-7 days |
| **Total** | Excludes unknown OEM blockers, language-model work and new hub hardware | **18-27 days** |

A provisional **200,000-byte compressed APK growth cap per ABI** covers code, strings, small vector/picture guides and signed baseline, with no models or third-party crypto/network library. Allocation target: metadata/trust 50,000; transfer/storage 70,000; installer/UI 50,000; baseline/guides 30,000 bytes. This is an engineering budget, **not a size measurement**. With about 1.6 KB prior arm64 headroom, owner ceiling approval is required. If approved as a maximum delta, proposed ceilings are 16,775,000 bytes arm64 and 14,915,000 bytes armeabi-v7a; reconcile with all other v0.1.1 features before committing any increase. Update both Gradle and the paired release-growth gate only after measuring the signed candidate. Do not silently round the budget to the task's 20 MB transfer scenario.

## 8. Decisions for the owner

### Blocking batch

**UP1. Trust chain and new packs**
- **A (recommended):** Existing APK signer plus offline P-256 catalog C and separate pinned recovery R; new supported packs need no APK release, but key custody and recovery become release responsibilities.
- **B:** Keep pack pins compiled into every APK; simpler trust, but each new pack needs an app update first.

**UP2. APK installation path and sensitive permission**
- **A (recommended):** In-app PackageInstaller with `REQUEST_INSTALL_PACKAGES`, just-in-time source grant and explicit consent every time; simpler pilot flow, extra security prompt and future Play-distribution constraint.
- **B:** Verify then export to Files, without this permission in Phonemesh; less app privilege, more user navigation and trust in the external installer handoff.
- **C:** Hub supplies packs only; APKs stay operator-assisted manual installs, reducing scope but leaving app updates outside this feature.

**UP3. Local transport and costly internet**
- **A (recommended):** Wi-Fi first, explicitly accepted BLE fallback, foreground/pause-resume only, never LoRa or phone internet fallback; protects data cost but slow BLE may need several visits.
- **B:** Wi-Fi-only bulk updates in v0.1.1; faster to qualify, but Bluetooth-only hubs cannot distribute payloads.

**UP4. Availability announcements**
- **A (recommended):** Verified badge on local contact plus hub operator status; zero new LoRa packets, people must visit or open the app nearby.
- **B:** Add the separately specified 30-byte LoRa hint later; wider notice, spoofing/airtime cost and a new port to approve.

**UP5. Downgrades, recovery and offline freshness**
- **A (recommended):** Persistent epoch/sequence/install floors, advisory review date, no committed rollback, repair by higher-version releases; preserves long-offline use but cannot detect a withholding hub's newest release.
- **B:** Hard catalog expiry for new installations only; stronger clock-dependent freshness policy, but offline users can be unable to update until fresh media arrives.

**UP6. Hub supply and storage scope**
- **A (recommended):** One verifier for explicit internet, USB and local hub-to-hub imports; 2 GB proposed object cap and separate mail reserve; complete offline circulation with operator work.
- **B:** USB imports first, defer hub internet/sync; smallest network surface, but every refresh needs a courier.

**UP7. APK budget and acceptance**
- **A (recommended):** Approve a provisional 200,000-byte growth budget per ABI, subject to measured signed sizes and combined v0.1.1 accounting; enough room to build without pretending current headroom suffices.
- **B:** Keep current ceilings and approve only a metadata/storage spike later; full feature cannot be promised inside 1.6 KB.

**UP8. v0.1.0 bootstrap and friend sharing**
- **A (recommended):** Operator-assisted same-signer APK upgrade first, preserve compiled pins, and add signed sidecars for new friend-shared packs; explicit trust bridge without weakening old phones.
- **B:** Bootstrap only via operator USB/Files and defer dynamic friend sidecars; less sharing work, new packs travel only through the hub until a later release.

### Deferred batch, not blockers for the recommended version

**UP9. Background transfers**
- **A (recommended):** Defer until measured BLE transfer times and battery costs justify it; no new long-lived service in this version.
- **B:** Design an opt-in foreground-service transfer with visible Stop action now; more uninterrupted copying, more platform/battery qualification.

**UP10. APK signer rotation and broader recovery authority**
- **A (recommended):** Keep the APK key and R unchanged; prepare a separate lineage/recovery drill before changing either, preserving Android 10-15 and supported older-device constraints.
- **B:** Add APK lineage rotation and threshold recovery roots now; stronger custody options, substantially larger security and migration review.

**UP11. Delta payloads and chunk hashes**
- **A (recommended):** Full immutable objects with bounded ranges and final whole-file hash; simpler implementation, corrupt late bytes can waste a transfer.
- **B:** Add signed per-chunk hashes or binary deltas after goodput measurements; can reduce retry/download cost, adds metadata and patch-parser attack surface.

## 9. Sources and evidence limits

### Repository sources opened

All file citations in sections 2-7 refer to the read-only tree named above. Key authorities are `docs/publication.md:14-29`, `docs/releases/v0.1.0.md:3-17`, the accounts decisions and Later list, `docs/design.md:37-56`, the demo spec, `GeneratedPackCatalog.kt`, `PackFormat.kt`, `PackStore.kt`, `PackDownloads.kt`, `PackSharing.kt`, `ShareProvider.kt`, `HubServer.kt`, `HubClient.kt`, `HubGatt.kt`, `HubLink.kt`, `android/hub/README.md`, `Airtime.kt`, `LoraAirtime.kt`, strings, manifest and Gradle size properties. Citations describe the code read, not a claim that a release/build was rerun.

### Primary web sources opened on 2026-10-26

- **W1:** Android, [Sign your app](https://developer.android.com/studio/publish/app-signing?hl=en). APK signing and release key custody. Initial plain-URL fetch failed; the language-qualified page opened successfully.
- **W2:** Android, [PackageManager.canRequestPackageInstalls](https://developer.android.com/reference/android/content/pm/PackageManager?hl=en#canRequestPackageInstalls()). The actual method section was fetched and read: API 26+, per-source trust, permission declaration and Settings flow.
- **W3:** Android, [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES). Per-package Settings destination.
- **W4:** AOSP, [APK signature scheme v3](https://source.android.com/docs/security/features/apksigning/v3). Android 9+ rotation and proof-of-rotation structure; not evidence of a Phonemesh rotation test.
- **W5:** AOSP Android 10, [PackageInstaller strings](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/packages/PackageInstaller/res/values/strings.xml). Source warning, update confirmation and Install action.
- **W6:** Android, [PackageInstaller.SessionParams](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)). Explicit required-user-action setting; modern APIs do not justify assuming all update paths always prompt by default.
- **W7:** AOSP Android 15, [PackageInstaller strings](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/packages/PackageInstaller/res/values/strings.xml). Update wording, source warning and possible ownership reminder. Source strings are not screenshots of pilot devices.
- **W8:** Google Play, [Use of REQUEST_INSTALL_PACKAGES](https://support.google.com/googleplay/android-developer/answer/12085295?hl=en). Sensitive-permission restrictions, core-use requirement and declaration obligations.

**Draft verification:** repository and primary-source inspection plus Python arithmetic only. Calculations used decimal transfer units and the hub's existing airtime formula. Browser fallback was unavailable because the configured default browser was not supported; successful direct primary-source fetches supplied the cited Android evidence. No production code or source-tree file was changed. No key was generated, update signed/published, installer exercised, throughput measured or permission flow visually qualified. These remain explicit acceptance gates, not implied outcomes of this draft.
