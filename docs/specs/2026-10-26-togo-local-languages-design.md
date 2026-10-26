# Local languages for the Togo pilot

Draft for the owner's decisions, 2026-10-26. No code before the picks.

## 1. Goal and recommendation

Let people use the language they actually speak. Keep messages as text over radio. Give each language separate support for the screen, spoken instructions, dictation and reading messages aloud. This helps everyone messaging without signal, including people who cannot read French. A translated screen is not proof that speech works.

**Recommendation, INFERRED:** confirm the village and language varieties first. If Ewe is useful there, make **Ewe the first complete language trial**. It has several ASR candidates, a commercially reusable BibleTTS voice candidate, and new WAXAL data. Prepare Kabiyè next, or first if the village uses it. Do not ship both merely because they are national languages. For a Mina-speaking village, test **Gen/Mina as Gen**, not as Ewe. For Centrale or Savanes, Tem or Moba may matter more.

Start with reviewed text and recorded instructions. Try **Meta Omnilingual CTC 300M int8** as the first broad ASR baseline. It is Apache-2.0, covers the five main candidates, and has an actual sherpa-onnx path. Compare a language-specific model where available. Keep **MMS ASR as a desktop research baseline**, not the default phone pack. Trial Ewe BibleTTS before accepting the non-commercial restriction of MMS-TTS. No language gets a working microphone or message-reading button until its particular model, dialect and phone tests pass.

**UNKNOWN:** the village, residents' preferred languages, local orthography, suitable reviewers, available storage and measured low-end-phone quality. No model was downloaded or run for this report. No recording, playback, Gradle, emulator, phone or radio action was performed. The public source tree was read only.

### Evidence labels

- **VERIFIED:** read in the cited source or calculated from read source code. For a model card, this means the publisher reports it, not that Phonemesh reproduced it.
- **INFERRED:** a proposal, estimate or conclusion drawn from that evidence.
- **UNKNOWN:** not established. A missing search result does not prove nonexistence.

Repository citations below are relative to the repository root. Web sources were opened on 2026-10-26. File sizes from hosting APIs are advertised asset sizes, not measured phone downloads or RAM.

## 2. Which languages matter

**VERIFIED:** Ewe and Kabiyè have national-language status and broad southern and northern use. That does not make either universal. The regional sources describe a varied northern language landscape. Gen/Mina is important in the southeast and Maritime region; Tem around Sokodé and Bafilo; Moba in Savanes [S1, S2, S3]. The exact village language remains **UNKNOWN**.

| Region or setting | First languages to ask about | Other communities to ask about, not assumptions about residents | Consequence |
|---|---|---|---|
| Grand Lomé and Maritime | Gen/Mina `gej`, Ewe `ee` | Waci/Ouatchi varieties, Aja; migrants speaking northern languages | Do not label a Gen voice Ewe. Confirm what residents mean by “Mina”. |
| Plateaux | Ewe; Ifè/Ana `ife` around Atakpamé; Ikposo/Akposso `kpo` west of Atakpamé | Akebu, Aja, Kabiyè-speaking communities | A single southern pack will not cover every village. Ifè and Ikposo are separate candidates [S4]. |
| Centrale | Tem/Kotokoli `kdh` | Kabiyè, Tchamba and other local varieties; Fula-speaking households | Confirm local Tem variety and French code-switching. |
| Kara | Kabiyè `kbp` | Nawdm, Lama/Lamba, Ntcham/Bassar, Konkomba, Tammari | Kabiyè is not a substitute for every northern language. |
| Savanes | Moba `mfq` | Gourmanchéma, Gangam, Anufo/Chokossi, Konkomba, Fula varieties | Start with the village's own language, not automatically Kabiyè. |
| Across regions | French for people who choose it | Trade languages, migrants, mixed households, deaf users | Keep French and English. Do not infer literacy or language from residence. |

The first-language geography above is **VERIFIED** at regional level in [S1–S4]. The “other communities to ask about” column is an **INFERRED interview checklist**, not a census or a verified village roster. Broad regional lists cannot choose the pilot language.

### Ask the owner before LG1

1. Which village, prefecture and region? A private location answer is enough. Do not publish household locations.
2. What do people call their home language? What do they use with neighbours and traders? Ask women, older people and minority households too.
3. Which variety is understood when spoken? Which spelling do local readers use? Does “Mina” mean Gen here, or a wider informal label?
4. Which people want text, dictation, spoken help, or read-aloud? Which can review written messages before sending?
5. Can the owner nominate and pay two native reviewers per selected language, including one familiar with local literacy practice? Seek a local teacher or language association, not only a distant translator.
6. What are the smallest phones, free storage, Android versions and keyboards? How will packs arrive once: internet, friend, or future local-hub delivery?
7. Is use strictly non-commercial, or might shops, paid services, employers or commercial redistributors use it? “Free app” alone does not answer this.
8. Does the owner authorise a small recording/listening study? Each participant must also consent. Permission to research models is not permission to record people.

## 3. How it works today

### Verified app constraints

| Area | What the code actually does |
|---|---|
| Language registry | Only `fr` and `en-US`. Each entry requires a voice pack, one name clip, six caption clips and two bootstrap how-to clips. The current “no screen changes” comment assumes that full bundle. It does not support a text-only new language (`Languages.kt:13–44`, under `android/app/src/main/java/org/phonemesh/app/`). |
| UI scope | XML parsing found **491** `<string>` elements in English and **489** in French. The two English-only entries, `app_name` and `plain`, have `translatable="false"`. The translation baseline is therefore **489 translatable entries**, not 491 missing French translations. Neither file contains plurals or string arrays. Recount at implementation freeze. Sources: `android/app/src/main/res/values/strings.xml:1–505` and `values-fr/strings.xml:1–501`. |
| Current packs | Whisper Tiny and Base recognition packs, English LJ Speech medium-int8 and French SIWIS low-int8 Piper voices. See `GeneratedPackCatalog.kt:5–10` and `android/scripts/build_packs.py:22–84`. The source archives and final Phonemesh ZIPs are different sizes. |
| Runtime | sherpa-onnx v1.13.8, with ONNX Runtime and Piper/eSpeak integration (`docs/dependencies.md:10–27`). `LocalSpeech.kt:18–39,56–79` constructs **both** Whisper and Piper at once. `DeviceSpeech.kt:131–147` prepares shared eSpeak data for every voice. These are app restrictions, not all the runtime's capabilities. |
| Pack rejection | Format 2 accepts only `whisper-tiny` and `whisper-base`. A voice's `whisperLanguage` must be in the fixed Whisper list and match the base of its tag. None of `ee`, `kbp`, `gej`, `kdh`, `mfq` is there. Required voice files include eSpeak data (`PackStore.kt:40–45,86–123`). Merely adding catalog entries will fail. |
| Readiness | `Readiness.Ready` requires an engine and voice together, with Whisper compatibility (`PackStore.kt:307–328`). TTS-only and ASR-only operation need a real separation. |
| Pack security and size | A pinned manifest, exact file hashes, path checks, version checks and staged installation are already present. Defaults: 512 MiB total unpacked, 256 MiB per file. The archive cap is the largest catalog ZIP plus one eighth (`PackStore.kt:19–36,152–219,232–268`). Preserve these protections. |
| Reading aloud | Explicit contact voice, then message-language voice, then the user's voice with a note (`AppCore.kt:2202–2225`). That last fallback can pronounce unknown-language text with French or English. It is not translation. |
| Spoken help | `sayHowTo` uses a bundled clip if present, otherwise the downloaded voice when fully ready (`AppCore.kt:2191–2199`). Two bootstrap clips do not cover every later tip. |
| Wire | `MessageCodec.canonical` accepts two or three letters, optionally a two-letter region. `ee` and `kbp` already fit. It does not validate that a model supports them (`MessageCodec.kt:16–24`). Legacy text including `[pm1:tag] ` is capped at 200 UTF-8 bytes (`:39–58`). |
| Private mail | Port 301 carries an encrypted body with a length-prefixed tag. `WireV2.MAX_PACKET` is 228 bytes (`wire/WireV2.kt:7–22,48–59`; `wire/Bodies.kt:59–60,112–147`). The private text allowance can be smaller than 200 bytes. |
| No voice over radio | The binding text contract forbids hiding, compressing or fragmenting audio into text (`docs/design.md:15–18,48–55`). This draft adds no audio transport. |
| Radio and crypto | All traffic stays on the primary channel, as illustrated by `AppCore.kt:563–584`. P-256 remains the account scheme. Quantum-safe changes remain shelved (accounts design decisions 3 and 30, `docs/specs/2026-10-14-accounts-design.md:17–20,69–74`). |
| APK ceiling | `android/gradle.properties:8–9`: 16,575,000 bytes arm64; 14,715,000 bytes armeabi-v7a. The brief reports about 1.6 KB historical signed arm64 headroom. A new signed APK was not measured here. |

**VERIFIED, limited evidence:** the existing speech comparison has three samples per language and model, including a quieter variant of an existing sentence. French WER is 0.4167 for Tiny and 0.25 for Base; English is 0 on this tiny set. It is not a Togo accent study, a population estimate, or evidence for any local language (`docs/speech-comparison-summary.csv:1–7`; `docs/speech-comparison-samples.csv:1–13`).

## 4. Model and data survey

### 4.1 Main candidates

“Reported accuracy unknown” means no usable language-specific result was established from the opened sources. TTS requires human comprehension and pronunciation tests; an ASR WER is not a TTS naturalness score.

| Language and identity | Speech-to-text candidates | Text-to-speech candidates | Evidence limits and phone route |
|---|---|---|---|
| **Ewe**: wire `ee`; model code `ewe` | MMS-1B + `adapter.ewe`; Omnilingual CTC; `asr-africa/wav2vec2-xls-r-ewe-100-hours`; Ewe Whisper fine-tunes; SunflowerASR | `facebook/mms-tts-ewe`; **Coqui/BibleTTS Ewe VITS**; `FarmerlineML/Ewe-tts-2025_v3` | Best resource choice, not proven best local quality. BibleTTS has a CC-BY-SA model entry. MMS is NC. Farmerline's card does not establish a licence. Omnilingual has a sherpa export; other ASR candidates need separate conversion checks. |
| **Kabiyè**, `kbp` | MMS-1B + `adapter.kbp`; Omnilingual CTC; `Devsyril/whisper-small-kbp` | `facebook/mms-tts-kbp`; `Devsyril/mms-tts-kbp-sherpa-onnx` | Actual Whisper and ONNX TTS repositories exist. Whisper card's validation and test WER cells are placeholders. No native-speaker quality evidence established. **Kabyle `kab` is not Kabiyè `kbp`.** |
| **Gen/Mina**, `gej` | MMS-1B + `adapter.gej`; Omnilingual CTC; Umbaji MMS fine-tunes | `facebook/mms-tts-gej`; Umbaji derivatives; `LEsprit/vits-finetuned-gej` lead | Common Voice Mina now provides a useful small corpus. Umbaji weights exist, but the inspected ASR repository lacks a readable root model card. No reported WER/CER established. Related Ewe speech is not a validated substitute. |
| **Tem/Kotokoli**, `kdh` | MMS-1B + `adapter.kdh`; Omnilingual CTC; Umbaji MMS fine-tunes | `facebook/mms-tts-kdh`; `Devsyril/mms-tts-kdh-sherpa-onnx`; `Umbaji/cey-25-mms-tts-mozilla-kdh` | The two inspected Tem TTS derivatives retain CC-BY-NC 4.0. No local WER/CER or TTS listening result established. Do not use the unrelated ISO code `tem` for Tem. |
| **Moba**, `mfq` | MMS-1B + `adapter.mfq`; Omnilingual CTC | `facebook/mms-tts-mfq` | Both MMS assets exist. No independent language-specific fine-tune or useful accuracy result established. **Moba `mfq` and Bimoba `bim` are separate entries** in Meta's coverage list. Confirm local variety. |
| **Ifè/Ana**, `ife`, if the village requires it | MMS-1B + `adapter.ife`; Omnilingual CTC | `facebook/mms-tts-ife` | No useful local accuracy result established. Do not substitute standard Yoruba just because the languages are related. |
| **Ikposo/Akposso**, `kpo`, if required | Omnilingual CTC; SunflowerASR; WAXAL data for future fine-tuning | No usable released voice established in this survey | MMS coverage lists it for pretraining, not an MMS ASR adapter or MMS-TTS voice. Sunflower reports poor relative results for Ikposo. Text and recorded help are the safe initial scope. |

**VERIFIED:** MMS adapter presence was checked against the actual `facebook/mms-1b-all` file list, not inferred from “1,000 languages”. Its ASR backbone is Wav2Vec2, one billion parameters, with language adapters; licence CC-BY-NC 4.0 [S5, S6]. Omnilingual's source list includes `ewe_Latn`, `kbp_Latn`, `gej_Latn`, `kdh_Latn`, `mfq_Latn`, `ife_Latn` and `kpo_Latn` [S10]. A language-list entry establishes coverage intent, not acceptable quality.

Other region-dependent leads: Meta's coverage list includes MMS ASR/TTS for Nawdm `nmz`, Lama `las`, Ntcham `bud`, Konkomba `xon`, Gangam `gng`, Gourmanchéma `gux` and Anufo `ann`. Published TTS safetensors are roughly 145 MB each, CC-BY-NC 4.0. No local WER/CER or MOS was established. These inherit the same runtime and licence gates as the main MMS candidates. Aja is `ajg`, not `adj`; the opened MMS coverage lists Aja only for pretraining. Do not turn every pretraining row into a supported speech pack [S6]. Ask for the precise Fula variety before choosing an asset or tag.

### 4.2 Reported accuracy, without mixing benchmarks

| Candidate | Reported measurement, VERIFIED as a source claim | What it does not prove |
|---|---|---|
| Ewe Whisper research, Wiafe et al., ICNLSP 2025 | At step 2,000, Yoruba-initialised model WER **37.12%**, CER **12.43%**; Shona-initialised model WER **37.17%**, CER **12.50%**. Table 3 reports checkpoint evaluation. Corpus roughly 106 hours transcribed [S7]. | Not stock Whisper Ewe support. Not a measured Phonemesh export. Do not attach these scores to an unrelated HF repository. The paper notes dialect and segmentation errors. |
| `asr-africa/wav2vec2-xls-r-ewe-100-hours` | Training table's step 4,000 validation WER **0.3128**, CER **0.0918**. A later final row is worse. Card says more dataset information is needed [S8]. | Not an independent held-out village test. Pin which checkpoint is actually exported. |
| Sunbird SunflowerASR, Whisper large-v3 adaptation | Its Sunbird benchmark reports Ewe WER **0.291**, CER **0.095**, 400 test clips; Ikposo WER **0.670**, CER **0.223**, 200 clips. Greedy decoding, forced language token, BasicTextNormalizer [S9]. | This benchmark is in-domain for Sunflower. Not a like-for-like comparison to the preceding rows. Model is much too large for the first low-end-phone pack. |
| Devsyril Kabiyè Whisper Small | WER validation and test are explicitly “à compléter” [S11]. | No numeric accuracy claim is justified. Training-data and rights review are still needed. |
| MMS for Ewe, Kabiyè, Gen, Tem, Moba, Ifè | Adapter/checkpoint availability verified. Relevant local WER/CER **UNKNOWN** [S5, S6]. | Global MMS results do not certify each village variety. |
| Omnilingual CTC 300M for the same languages and Ikposo | Coverage and sherpa support verified. Relevant local WER/CER **UNKNOWN** in the inspected card [S10]. | A100 speed figures are not Android latency or RAM results. |
| All listed TTS voices | Relevant local human comprehension, tone accuracy and MOS **UNKNOWN**. | “VITS”, a sample link, or a successful export is not a listening evaluation. |

### 4.3 Data and community routes

- **WAXAL, VERIFIED:** the current Google dataset card lists University of Ghana **Ewe ASR and TTS**, plus **Ikposo ASR**, under **CC-BY 4.0**. Other providers in that same collection use **CC-BY-SA 4.0**. The paper calls the collection CC-BY 4.0 and gives different language totals from the evolving card. Preserve this discrepancy. Use the exact subset's terms and revision, not a blanket licence copied from the abstract [S13]. WAXAL is training/evaluation data, not an installable voice pack. The paper's research-purpose consent wording also deserves review before commercial voice work.
- **Common Voice Mina 27.0, VERIFIED:** release 2026-09-17, `gej`, 16,773 clips, 11.38 hours recorded, 11.14 validated, 20 speakers, archive advertised as 216.28 MB. Labelled **CC0-1.0**, but the download page separately forbids speaker reidentification and rehosting/resharing the dataset. Treat access terms as a separate constraint; do not redistribute its raw recordings in packs. Small speaker diversity and repeated read sentences limit generalisation [S3]. The inspected Common Voice catalogue did not establish equivalent releases for the other main candidates. That is not proof that none exist.
- **BibleTTS/Masakhane, VERIFIED:** Ewe has 86.8 aligned hours, professional single-speaker speech; dataset CC-BY-SA 4.0. The Coqui model manifest separately lists Ewe OpenBible VITS as **CC-BY-SA 4.0**. This is a real alternative to an NC voice, not merely a corpus lead. The project page's Ewe config link points to an Asante-Twi path, so obtain the Ewe configuration from the correct release rather than copying that link [S14]. Verify notices, conversion and training provenance before redistribution.
- **Masakhane and Lanfrica, VERIFIED:** useful routes to language researchers, speech resources and community partners. Masakhane's community directory links BibleTTS. Lanfrica maps resources, models and organisations. Neither directory entry grants a licence or guarantees a ready Android model [S15]. No outreach was sent.
- **NVIDIA and Microsoft, VERIFIED but not direct Togo coverage:** NVIDIA documents Kinyarwanda NeMo CTC and transducer training; its reported errors concern Kinyarwanda, not Kabiyè. Microsoft's Paza models target six Kenyan languages, not the five principal Togo candidates. Their tools and field-testing methods are useful leads, not evidence that an NVIDIA or Paza Togo model is ready [S16]. Do not copy the Kinyarwanda recipe's diacritic stripping into these languages.

### 4.4 Pack-size and licence table

MB here means decimal bytes divided by 1,000,000. MiB is used only for explicit installer limits. The table intentionally separates **upstream weights**, **upstream archives**, and **final Phonemesh packs**. No new Phonemesh ZIP size or peak phone RAM has been measured.

| Asset | Verified bytes and kind | Licence evidence | Deployment consequence |
|---|---:|---|---|
| Current Whisper Tiny | **60,136,101**, Phonemesh ZIP | Whisper MIT; current intake records | Existing baseline. |
| Current Whisper Base | **93,630,372**, Phonemesh ZIP | Whisper MIT | Existing baseline, not local-language support. |
| Current English LJ Speech voice | **13,979,221**, Phonemesh ZIP | Upstream voice card recorded as public domain | Existing Piper path. |
| Current French SIWIS voice | **6,334,594**, Phonemesh ZIP | CC-BY 4.0, provenance caveat in intake | Existing Piper path. |
| MMS-1B ASR backbone | **3,859,521,128**, safetensors | CC-BY-NC 4.0 | Far beyond existing pack limits. Adapters alone cannot run. |
| MMS Ewe / Kabiyè / Gen / Tem / Moba adapters | **8,921,516 / 8,875,400 / 8,885,648 / 8,875,400 / 8,798,532**, safetensors each | CC-BY-NC 4.0 | Add the backbone and tokenizer. Do not advertise an “8 MB ASR pack”. |
| MMS-TTS Ewe | **145,237,496**, safetensors | CC-BY-NC 4.0 | Exported graph and final ZIP sizes unknown. |
| MMS-TTS Kabiyè | **145,231,352**, safetensors | CC-BY-NC 4.0 | Same. |
| MMS-TTS Gen | **145,232,888**, safetensors | CC-BY-NC 4.0 | Same. |
| MMS-TTS Tem | **145,231,352**, safetensors | CC-BY-NC 4.0 | Same. |
| MMS-TTS Moba | **145,219,832**, safetensors | CC-BY-NC 4.0 | Same. |
| MMS-TTS Ifè | **145,235,960**, safetensors | CC-BY-NC 4.0 | Same. |
| Devsyril Kabiyè / Tem MMS-TTS exports | **114,026,736 each**, `model.onnx` | CC-BY-NC 4.0 | Actual published sherpa-shaped exports, not qualified Phonemesh packs. |
| Omnilingual CTC 300M upstream | **1,302,113,892**, `.pt` weights | Apache-2.0 | Do not ship this FP32 checkpoint. |
| Official sherpa Omnilingual 300M int8, 2025-11-12 | **292,571,207**, tar.bz2; **365,352,120**, `model.int8.onnx` | Apache-2.0 | Known file exceeds current 268,435,456-byte per-file cap. Total can fit 512 MiB without bundling FP32. |
| Official sherpa Omnilingual 300M CTC v2 int8, 2026-02-05 | **292,313,120**, tar.bz2 | Apache-2.0 family; pin release notices | Preferred newer comparison candidate. Exact unpacked int8 file and Phonemesh ZIP still need inspection. The similarly named HF int8 mirror inspected here is empty except `.gitattributes`. Use the official release asset, not that mirror. |
| Ewe XLS-R 300M fine-tune | **1,262,069,880**, safetensors | HF card metadata Apache-2.0; access gated | Converter and smaller quantised artifact needed; no drop-in app support. |
| `abiyo27/whisper-small-ewe` | **967,102,729**, PyTorch weights | No model card/licence established | Hold for rights, tokenizer and quality evidence. |
| `Devsyril/whisper-small-kbp` | **483,525,200**, safetensors | HF metadata Apache-2.0; base Whisper MIT | Candidate merged LoRA model. Audit Wilderness-derived training rights. Quantised sherpa files unknown. |
| SunflowerASR | **6,174,112,552**, safetensors | HF metadata Apache-2.0; access gated | Desktop comparator, not first phone pack. Uses reassigned Whisper language-token IDs. |
| Ewe BibleTTS VITS | **921,567,491**, Coqui release ZIP | Coqui model manifest CC-BY-SA 4.0 | Training-style release size is not minimal inference size. Extract/export/measure later. Not acceptable as-is under current limits. |
| Farmerline Ewe TTS | **145,240,696**, safetensors | **UNKNOWN**, placeholder card | Do not distribute without permission/provenance. |
| Recorded bootstrap instructions | **INFERRED:** 135,000 payload bytes for nine five-second clips at 24 kbit/s; 450,000 for thirty | Obtain performer consent and an explicit reusable recording licence | Plus container/resource overhead. Not arbitrary-text TTS. |

Current-pack evidence: `GeneratedPackCatalog.kt:6–9`, `android/scripts/build_packs.py:74–84`. New asset metadata: [S5, S8–S12, S14, S17]. These bytes are not RAM estimates. Quantising MMS-1B does not make an 8 MB recogniser: even a rough one-byte-per-parameter estimate is about 1 GB before other costs.

**Piper, VERIFIED bounded negative:** the opened `rhasspy/piper-voices/voices.json` has no Ewe, Kabiyè, Gen, Tem or Moba entry [S18]. Piper voices have per-voice licences. The engine licence is not a licence for every voice. This catalogue check does not exclude independent community voices.

### 4.5 Can sherpa-onnx run them?

| Family | Runtime evidence | Work still required |
|---|---|---|
| MMS VITS TTS | Official sherpa conversion recipe outputs `model.onnx` and `tokens.txt`, with `model_type=vits`, `frontend=characters`, language and sample-rate metadata. It rejects uroman-dependent inputs. All five principal MMS tokenizer configs inspected here have `is_uroman=false` and `phonemize=false` [S12]. | Reproduce or audit export, compare tokenizer behaviour and audio outputs, test native speakers. Current Phonemesh requires Piper/eSpeak and Whisper pairing, so it still needs code changes. Do not feed character tokens through eSpeak. |
| BibleTTS VITS | Coqui VITS architecture and model exist [S14]. | **UNKNOWN** whether this exact graph can use the pinned sherpa VITS interface without changes. Export and metadata/frontend parity are a blocking spike. VITS architecture alone is insufficient. |
| Omnilingual CTC | Official sherpa documentation provides FP32/int8 models. Pinned v1.13.8 Kotlin source exposes `OfflineOmnilingualAsrCtcModelConfig` and `OfflineModelConfig.omnilingual` [S10, S12]. | App selection, pack schema and size-limit changes; prove the shipped AAR contains the corresponding working native path. No runtime upgrade is assumed necessary, but no binary qualification occurred. |
| MMS ASR / generic Wav2Vec2 CTC | MMS uses Wav2Vec2 CTC. sherpa has several specific CTC backends [S5, S12]. | **No verified drop-in MMS/Wav2Vec2 adapter path** in the pinned app. Export shape, frontend, blank ID, vocabulary and native backend must match. “CTC supported” does not mean every CTC graph works. |
| Ewe/Kabiyè Whisper fine-tunes | sherpa already runs Whisper. | Convert each checkpoint, preserve tokenizer and forced-token behaviour, then compare against source inference. The current whitelist and language assumptions must change. Sunflower reuses token IDs, so standard Whisper language-name selection can select the wrong language [S9, S11]. |

## 5. Licence decision: AGPL does not erase NC

**VERIFIED legal text:** CC-BY-NC 4.0 permits sharing and adaptations only for NonCommercial purposes. Section 1 defines NonCommercial as not primarily intended for commercial advantage or monetary compensation. Section 3 requires attribution, licence identification, retained notices and identification of modifications. It does not grant all privacy or personality rights. AGPL section 5 discusses separate independent works in an aggregate; sections 7 and 10 do not allow adding non-commercial restrictions to the covered program [S19].

**INFERRED legal analysis, not legal advice:**

- A genuinely free, non-commercial village trial can fit the MMS licence. Keep attribution, source and revision, CC licence text, conversion/quantisation notices and no-endorsement wording. The licence does not require a fee merely because the app is AGPL.
- Non-profit status and a zero purchase price are **not sufficient**. A free tool used to advance a business, a sponsored commercial service, a paid bundle, or resale can raise NC issues. Residents may use messaging for trade. Do not promise that every use in a village is non-commercial.
- The AGPL app must remain commercially reusable under AGPL. Do not relabel MMS weights AGPL, add NC to the app, or claim an NC-containing distribution is entirely free software.
- A separately downloadable, clearly labelled model can plausibly remain a separate work with its own licence. Whether this particular integration is an aggregate rather than a combined work needs qualified legal review. “Separate ZIP” is not an automatic legal exemption.
- Commercial users of MMS weights or derivatives may need separate permission from the relevant rights holder, or replacement weights with suitable rights. Conversion to ONNX, quantisation, an adapter, or an Apache-labelled wrapper does not remove the underlying NC condition. Output rights require their own analysis; do not claim every generated waveform automatically inherits NC.
- Ewe BibleTTS's CC-BY-SA model and WAXAL's Ewe CC-BY subset are more promising for commercial reuse. Preserve their own attribution/share-alike duties where applicable. Training-data terms, performer consent and model rights are distinct checks.

**Recommended release policy:** no MMS weights in the default public pack catalogue for this slice. Authorise a separate non-commercial evaluation only if the owner accepts the restriction. Require legal review before any public NC-pack offer. This respects the current project's exclusion of NC model dependencies (`docs/dependencies.md:68–74,105–107`) rather than quietly changing it. If the BibleTTS export fails or no suitable voice exists, ship reviewed text and fixed spoken help, not a legally uncertain voice.

## 6. Options and costs

All message options retain the same cryptography and text transport. A local-language tag adds no capability negotiation and no additional radio event. Packs and clips never travel over LoRa.

| Option | Bytes, packets and airtime | Security/privacy | Phone and app cost | Effort and v0.1.0 compatibility |
|---|---|---|---|---|
| **A. Reviewed UI/text + spoken help, capability-gated speech trials (recommended)** | 0 new protocol bytes; 2- or 3-byte tag replaces the existing tag. One existing message frame if it fits; no extra airtime events. Download/import is local provisioning, zero LoRa packets. | Local text and optional explicit playback. No automatic mic or cloud. | Text alone has no model cost. New resource/code bytes must be measured; recorded help budget below. | Smallest safe start. Old phones can decode tags/text but lack the UI and voice. |
| B. Full MMS ASR + MMS-TTS bundle | Same message cost; several GB provisioning before conversion | Local inference, but NC restrictions and model supply-chain risks | Large storage/RAM and likely slow on small phones. Cannot fit current installer. | High integration cost, poor default. Old phones reject new pack format. |
| C. Omnilingual int8 + a legally cleared TTS voice | Same message cost; roughly 292 MB upstream ASR archive plus voice | Local inference; Apache ASR, voice licence separate | About 365 MB known v1 ASR graph plus runtime memory. No model bytes in APK. | Moderate app work; phone and voice-conversion gates remain. Natural next stage of A. |
| D. Cloud recognition or translation | No new LoRa framing, but requires internet and sends audio externally | Changes privacy and offline promise | Service/data costs; smaller local weights | Reject for this pilot path. Not a silent fallback. |
| E. Record and play on this phone only | Zero radio bytes or packets | Voice retained only temporarily by default; audible privacy risk | Small clip storage, no ASR/TTS model | Useful for nearby assistance, **not remote messaging**. Needs owner-approved UX. |

### Wire budget, calculated from source

**VERIFIED calculation:** legacy prefix `[pm1:ee] ` is 9 UTF-8 bytes, leaving 191 text bytes. `[pm1:kbp] `, `gej`, `kdh`, `mfq` each cost 10, leaving 190. An Ewe letter or combining accent may require multiple UTF-8 bytes. Never count Java characters as wire bytes.

Private TEXT plaintext stays:

`kind:u8 | languageLength:u8 | language:ASCII[languageLength] | text:UTF-8[remaining]`

No new fields. For a two-letter tag, language plus kind/length overhead is 4 bytes; for three letters, 5. With the existing 9-byte unkeyed header and 16-byte GCM tag, maximum text is 199 / 198 bytes. With the 44-byte keyed header, it is 164 / 163. Replies, expiry and other fields reduce room further. Use the existing real encoder budget, not one universal text count (`WireV2.kt:19–22,49`; `Bodies.kt:59–60,112–147`). Oversized content stays editable; no automatic split or accent removal.

**Airtime discrepancy to retain:** the brief's “about one second” is a planning shorthand. Direct evaluation of `LoraAirtime.seconds`, LongFast SF11/BW250/CR4/5, 16-symbol preamble and its 33-byte overhead gives **2.197504 seconds** for a 228-byte application payload, one hop, excluding ACKs, relays and retries (`LoraAirtime.kt:14–18,29,38–43`). A 100-byte private text with an unkeyed `ee` or `kbp` tag gives the same **1.460224 seconds** due to symbol rounding. These are code-formula estimates, not RF measurements. This feature adds no packets, so **incremental airtime is zero when the frame length stays in the same symbol bucket**. A one-byte tag difference can cross a bucket boundary. Preserve the existing duty-cycle budget; do not assume literal 1-second messages.

## 7. Recommended design

Everything in this section is **INFERRED, proposed**, unless it cites current code.

### 7.1 Separate four capabilities

A language record should state independently:

- Reviewed UI resources and endonym.
- Fixed name/help clips, available before downloads.
- Recogniser candidates and verified language mapping.
- Voices able to read that language.

Keep app language, outgoing message language and per-contact reading voice separate. Existing users retain their choices. Use explicit adapters such as `ee -> ewe` and `ee -> ewe_Latn` inside model metadata. Do not put `ewe_Latn` on the wire, and do not change `canonical()` to guess aliases from model names. `kbp`, `gej`, `kdh`, `mfq`, `ife`, `kpo` remain exact canonical wire codes. A human label can contain diacritics without changing the code.

Use text-only, ASR-only, TTS-only and full-speech readiness states. A voice must load without constructing an ASR model. An ASR model must work without a voice. Changing language must release old models safely and cancel previous work. No implicit network fetch in speech calls.

### 7.2 Pack and local-storage changes

Keep reading existing format-2 packs without modifying their files. Introduce a separately reviewed **format 3** for non-Whisper/Piper capabilities rather than pretending an MMS voice is a Whisper language.

Proposed `pack.properties` keys, UTF-8 text, with strict bounded parsing:

- Existing `format`, `id`, `version`, `kind`, `label`.
- For engines: `recognizerModel=omnilingual-ctc-300m`, model path and a pinned supported-language mapping.
- For voices: `languageTag=ee` or another canonical tag, `synthesizerModel=vits-characters` or `piper`, and `frontend=characters` or `espeak`.
- Schema-specific sample rate, token path and model path. No executable script or arbitrary native library in a pack.
- Provenance in `SOURCES.json`, notices in `NOTICE.txt`, all covered by the pinned `SHA256SUMS` manifest. Include source revision, licence, conversion tool/version and checksums. A licence label is not runtime authority.

Allowlisted layout: root metadata/notices, `asr/model.int8.onnx`, `asr/tokens.txt` for the selected CTC path; `tts/model.onnx`, `tts/tokens.txt` for character VITS; eSpeak data only for a Piper frontend that requires it. Bound the number and lengths of tags and metadata fields. Reject unknown architecture/frontends and extraneous paths before writing files. Exact schema and its tests are part of implementation slice 2, not authority to accept arbitrary HF models.

For the selected Omnilingual artifact only, propose an explicit **512 MiB per-file ceiling** while keeping **512 MiB total per pack**; other pack families retain tighter caps. The known 365,352,120-byte graph exceeds today's 256 MiB per-file limit. Do not raise limits globally to accommodate full MMS-1B. Pin catalog-specific download/unpacked sizes, account for existing pack plus staging plus archive storage, fail cleanly on no space, and keep the old working pack until validation completes. Rebuilt “From a friend” ZIPs must retain the exact manifest and licence notices.

UI translations remain Android resources in this slice, for example `values-ee` and `values-b+kbp`. Do not invent unsigned executable UI packs. Store new preferences as canonical text tags in additive optional settings. Existing rows keep their exact text and tags. **No chat-history, key, contact-card or message-body migration is required.** Do not normalise old stored ciphertext or alter bytes pending retry.

### 7.3 Screens and words

Use current entry points: **“Choose your language”**, **“Settings” → “Language”**, **“Languages (packs)”**, **“Understanding speech”**, **“Voice for this person”**, **“Listen”**, **“Help”** and **“Problems”** (`values/strings.xml:6,20,62,95–101,234–245,443–448`). The hub screen still says “Village hub” today; this feature does not reframe it as a speech server.

Proposed new English sentences, each no more than eight whitespace words:

- “You can type in this language.”
- “Speech is not ready for this language.”
- “This voice cannot read this language.”
- “Choose a voice for this language.”
- “Check the words before sending.”
- “This recording stays on this phone.”
- “Delete this recording?”

Translate meanings, not literal word order. Native reviewers approve final local-language copy; this report does not manufacture Ewe or Kabiyè translations. Run the real `CopyRules.words` check on every sentence and teach reviewers its whitespace-token counting (`CopyRules.kt:3–12`). Its English/French banned-word list needs reviewed local equivalents. The eight-word rule is a ceiling, not evidence of comprehension.

When speech is unsupported, show text entry and an explanation beside the microphone or Listen control. Let users hear the fixed explanation where a clip exists. Do not send a silent empty transcript. Do not force a large model download to finish setup. For someone unable to use text, say plainly that remote speech messaging is not yet supported in that language. A trusted nearby helper is an explicit choice, not a privacy-neutral substitute.

### 7.4 Orthography, fonts and keyboard

Preserve letters and tone marks. Ewe review samples must include **Ɖ Ɛ Ƒ Ɣ Ŋ Ɔ Ʋ** and **ɖ ɛ ƒ ɣ ŋ ɔ ʋ**, plus acute, grave, nasal and combining sequences used by the local orthography. Kabiyè and the other languages need their own reviewed alphabet and tone examples. Gen's Common Voice datasheet supplies a distinct symbol table and tone conventions [S3]. Do not impose one language's orthography on another.

Use Android's system font and fallback first, **0 bundled font bytes**. Glyph coverage and combining-mark placement on actual pilot OEM phones are **UNKNOWN**. Test missing-glyph boxes, clipped marks, upper/lowercase, large text, bold, wrapping, selection and deletion. Test `Paint.hasGlyph` where useful, but do not substitute it for visual review. If a font is needed, propose a separately licensed subset and measure signed APK growth before adding it.

Android keyboard availability is separate from font rendering. Check that local letters and tone marks can actually be entered offline. Offer a reviewed keyboard setup guide or a small accessible character aid only after field feedback. Never silently replace Ɛ with E, Ɔ with O, or remove accents to fit the wire. Keep display and transport text intact. A model's private text normalisation may differ, but must not rewrite the user's message without approval. Test NFC and decomposed forms. Do not normalise existing queued messages after encryption.

### 7.5 Spoken tips, help pictures and read-aloud voice

The bootstrap needs the nine clip slots the current registry expects. Later tips and help screens also need spoken coverage without a working TTS voice. Audit every dynamic `sayHowTo` call; either provide a reviewed fixed recording for a fixed sentence or mark the dynamic speech unavailable. Keep important failure instructions available offline before a download.

Every new gesture needs a matching help picture and one short spoken sentence. Pictures must show the real UI controls and local labels. First review existing pictures with native speakers: a universally recognisable-looking picture may still be misunderstood. Do not record anyone or synthesise/play examples until the owner authorises the study. Obtain performer consent for distribution and separate rights for any later training or cloning. This scope does not include personal voice imprints.

Reading aloud should choose a voice that explicitly supports the **message's language**. A per-contact preference may choose a voice within that language, not force French pronunciation on Ewe text. If none exists, disable speech for that message with a local-language explanation and keep text available. Do not automatically translate. Fixed recorded tips can explain this but cannot read arbitrary incoming messages.

### 7.6 No-model fallback

1. **Text-only language:** reviewed UI, text messages, correct tag, optional fixed help recordings. This is useful to readers, but is not a completed voice loop.
2. **Local record-and-play:** only after a separate owner pick. Record explicitly, play only on request, label it as staying on this phone, delete by default on dismissal, and offer a clear delete action. A nearby person can listen and help type if the user chooses. It does not reach a distant contact.
3. Do not turn recordings into LoRa attachments or send them to the hub/cloud. A future Wi-Fi audio messenger would be a different feature and wire/privacy design.

### 7.7 Hub and threat model

**No hub protocol or mail-format changes.** The hub stores and forwards the same encrypted private bytes. Future signed pack distribution through a hub belongs to the separate update spec named in accounts decision 31. This draft must not bypass pinned-pack admission in anticipation of that work.

| Actor or failure | What remains possible, and what must not be claimed |
|---|---|
| Outside radio listener | Can observe timing, size and shared-channel traffic. Cannot read private encrypted body tags/text through this feature. Shared chat is not made private by a local-language tag. |
| Local hub | Private mail remains opaque; it can read shared-chat traffic through its radio. If it later distributes packs, it can observe pack requests and infer language interest. Language support does not hide that metadata. |
| Lost or unlocked phone | Existing local text/settings and any retained voice clips may be exposed under the app's existing protection model. No new recovery or encryption guarantee is claimed. Delete temporary recordings; do not add them to logs or backups by accident. |
| Contact | Can send false or offensive text and mislabel its language. A tag is a sender claim, not language detection. Receiving a message must not auto-play speech or download a pack. |
| Malicious pack source | Model parsing is a native-code attack surface. Keep pins, hashes, bounded files and staging; reject unknown executable content. Prefer audited conversion from pinned sources. |
| Wrong dialect/model | Can change names, numbers, negation or tone while sounding fluent. Require review before Send and human error tests. Never present confidence as a guarantee. |
| Low memory or disk | Preserve typed draft and prior pack, release models, show a specific error and offer text mode. No automatic network fallback. |

## 8. Compatibility and migration

**VERIFIED at codec level:** v0.1.0's generic tag grammar can carry the proposed codes; private `Bodies.Reader.language` accepts canonical tags of length 2–6. Thus no new wire version is needed. **UNKNOWN until mixed-version tests:** every old UI path's behaviour on these messages.

New phones retain format-2 English/French packs and preferences. New pack IDs do not replace existing pack directories. v0.1.0 will reject format 3 or unknown catalogue packs; this is expected. Say that the app needs updating rather than asking people to disable verification.

Old phones can display valid UTF-8 but have no selected local-language voice. Their existing read-aloud fallback can still use the wrong language. New senders cannot repair that old code remotely. Include this limitation in pilot onboarding and test it. Do not add capability pings or duplicate French messages to every send. Users may explicitly choose French when both people prefer it; the app does not translate automatically.

The account design's “Later” items stay out of scope, including recovery, groups and quantum-safe work. Existing contact keys, receipts, retries, radio primary-channel use and hub retention remain unchanged.

## 9. Smallest quality experiment and tests

### 9.1 First experiment, not a release claim

**Proposed, requires LG1 and LG6:** one selected language and one local variety. Start with **four adult native speakers**, two independent native text reviewers, and twelve short utterances per speaker: **48 utterances**. Include different ages and voices where recruitment permits. Reading is not required: use picture or situation prompts. Use no real private messages.

Cover names and places invented for the test, numbers, time, negation, requests, corrections, local vocabulary, a quiet setting and ordinary background noise. Include French code-switching if participants normally use it. Keep utterances short enough for the app's existing recording limit and one text message. Compare the same recordings across Omnilingual and one justified language-specific ASR candidate. MMS is optional only under the approved NC evaluation policy.

For TTS, use twelve independently reviewed messages, not the ASR hypotheses alone. Four listeners each judge all twelve: **48 listener judgments per voice**. For Ewe, compare BibleTTS and, only if authorised, MMS-TTS. Ask listeners to repeat the meaning without seeing the text and flag wrong tone, name, number or negation. Do not use the intended text as a prompt that masks poor speech.

Report raw WER and CER with diacritics preserved, a separately documented normalised score, and human meaning/critical-word errors. Keep segmentation rules explicit. Do not compare arbitrary normalisers' WER as if identical. Include cold load, warm latency, p50/p95, peak process memory, free-space needs and cancellation behaviour on the smallest authorised target phone. Quantisation must be compared against the source checkpoint on the same clips.

**Proposed stop/go thresholds, not established standards:** continue only if at least 90% of short messages are understood without coaching, no unnoticed critical number/negation/name error remains in the final reviewed send flow, and the smallest phone completes reliably without memory kills. Target warm ASR real-time factor no worse than 1 and visible cancellable progress. A small sample can reject a bad candidate; it cannot certify rare errors or broad dialect coverage. Failures mean text/help-only release, not threshold relaxation. A larger held-out field evaluation is needed before advertising reliable dictation.

**Consent:** owner authorisation plus participant consent before recording or playback. Explain local storage, who hears clips, compensation, deletion date and whether data will ever leave the study. Default to encrypted local study storage, no cloud upload, no public dataset release and no reuse for training or voice cloning. Those require new explicit permission. Prefer existing appropriately licensed samples for preliminary conversion checks. No such study occurred in this draft.

### 9.2 Implementation verification, after approval

**JVM first:**

- Canonical tags and explicit model-code mapping; reject malformed identifiers. Verify `ee` and `kbp` without modifying canonical aliases.
- UTF-8 budgets for all five primary tags, keyed/unkeyed private frames, replies and expiry. Include decomposed marks, emoji and unsafe controls. Prove no truncation or splitting.
- Capability states: text-only, TTS-only, ASR-only, full, missing or corrupt pack. English/French regressions.
- Format-2 compatibility and format-3 strictness. Unknown models, wrong language, malformed metadata, duplicate entries, traversal, oversized files, wrong pins, downgrade, cancellation and disk-full preservation.
- Every translatable key present; format placeholders and escaping preserved; every sentence passes CopyRules; reviewers approve actual words.
- Voice selection never falls back across incompatible languages. No recording, playback, model fetch or network call merely from receiving a message.

**Emulator, only when authorised:** language selection, setup without speech, Help/Problems, pack import from a friend, interrupted downloads, large fonts, all special letters and combining marks, process recreation, missing-voice feedback and English/French return paths. Review screenshots and help pictures. A model mock is UI evidence only.

**Real native runtime and target phone, separately authorised:** model load/export parity, supported frontend, memory/latency, offline mode, cancellation, foreground/background lifecycle, synthesis correctness and consented native-speaker judgments. No approval to do that is implied by this research task.

**Demo build:** add explicitly simulated canned local-language exchanges reviewed by speakers, using the real encoders. Cover each capability state, mixed old/new tags, model-missing and failure flows. Never label canned transcripts a real ASR success. The demo remains in its own source set and uses its own data, as required by `docs/specs/2026-10-25-demo-build-design.md:14–46`.

**Mixed-version:** new ↔ v0.1.0 private and shared messages, retry/receipt preservation, old-phone wrong-language playback warning, format-2 sharing and format-3 refusal. No radio field test is claimed by an emulator run.

### 9.3 Effort and size in slices

Estimates below are **INFERRED engineering effort**, excluding reviewer availability, legal review and data collection. These are not delivery dates.

1. **Village and language intake:** one short owner session plus two reviewer sessions. Freeze dialect, spelling, 489-entry inventory and the first capability level. No code.
2. **Text/readiness separation and copy:** roughly 2–4 engineering days for one language, plus translation and review. Keep text usable without speech. Bundle only required bootstrap help.
3. **One ASR and one voice spike:** roughly 2–5 days if export paths work. Stop if licensing, frontend or quality fails. New model bytes remain out of the APK.
4. **Format 3 and lifecycle integration:** roughly 3–5 days, with bounded admission and migration tests. No arbitrary-model importer.
5. **Quality study and qualification:** schedule with speakers; roughly 2–4 engineering days for instrumentation and analysis. Then the owner's normal gates, demo and signed-size check.

**APK budget:** exact added code/resources are **UNKNOWN until built**. Plan for up to **512,000 extra APK bytes for the first language's resources and small control changes**, as an owner-approved provisional budget, not a measured requirement. The nine-clip estimate alone is 135,000 payload bytes before packaging. Thirty clips are 450,000 before packaging, so all tips may need a separate signed/pinned help pack or a larger measured allowance. No new font or native runtime is budgeted. Do not claim the historical headroom suffices. The owner must approve any ceiling rise after measured signed APK deltas for both ABIs. Do not bundle the 292 MB ASR archive or a TTS checkpoint in the APK.

## 10. Decisions for the owner

### Blocking batch, recommended pick first

**LG1. Which language goes first?**

- **A (recommended):** name the village and choose one local language with residents; Ewe first if locally useful, Kabiyè first if that is the village's language. Best relevance, avoids a national-language assumption.
- B: start Ewe and Kabiyè together. More reach, double review and qualification work before knowing local need.
- C: keep French only until a local partner is ready. Least work, leaves a known inclusion gap.

**LG2. What counts as initial support?**

- **A (recommended):** reviewed UI/text and spoken help first; enable ASR and TTS independently only after each passes. Honest partial support without blocking text.
- B: release a language only with the full speech loop. Simpler promise, likely delays all support.
- C: expose unqualified speech as an experiment in the public pilot. Fast feedback, unacceptable risk of misleading messages unless tightly supervised.

**LG3. Which ASR experiment?**

- **A (recommended):** Omnilingual 300M int8 plus one language-specific comparator where justified. Real sherpa path and Apache weights, but larger pack than today's engine.
- B: MMS-1B with adapters first. Broad coverage, much larger and NC-restricted.
- C: train a new small model immediately. Potentially smaller, but requires data, compute and a separate research budget before value is known.

**LG4. May non-commercial weights enter the release?**

- **A (recommended):** no NC weights in the default public catalogue; separately authorised NC evaluation only. Preserve commercial reuse and current intake policy.
- B: offer separately labelled MMS packs after qualified legal review and rights checks. Easier language breadth, restricted use and redistribution remain.
- C: seek a separate commercial licence before testing MMS for deployment. Clearer rights if obtainable, timing and availability unknown.

**LG5. Which voice and fallback?**

- **A (recommended):** trial Ewe BibleTTS when Ewe is selected; otherwise evaluate a legally cleared matching voice. Ship fixed spoken help and text if none qualifies. Never auto-read with another language's voice.
- B: accept MMS-TTS for a clearly bounded NC pilot under LG4. Broad availability, not an unrestricted public solution.
- C: commission a new voice from consented, appropriately licensed recordings. Local control, more cost and lead time.

**LG6. Approve the smallest speaker study?**

- **A (recommended):** approve the bounded 48-utterance study and native-listener tests, with individual consent, compensation and deletion terms agreed first. Smallest useful quality screen, not release certification.
- B: inspect public samples and model metadata only. No new recording, but cannot judge village fit.
- C: defer speech evaluation and ship only text/help. Lower risk, less voice functionality.

**LG7. Approve the architecture and size boundary?**

- **A (recommended):** separate UI/ASR/TTS capabilities, additive format 3, no wire change, model-specific size caps; measure both signed APKs before raising ceilings. More explicit work, preserves admission and compatibility.
- B: force new languages through format 2 and Whisper/Piper assumptions. Smaller apparent change, misleading capability model and repeated special cases.
- C: stay text/help-only in v0.1.1 and defer pack-format changes. Lowest integration risk, no new arbitrary-message speech yet.

**LG8. Who owns translation acceptance?**

- **A (recommended):** two paid native reviewers per selected language, one with local literacy expertise, plus observed comprehension with non-readers. Slower than machine translation, provides accountable review.
- B: one reviewer plus a second review before general release. Faster start, more chance of dialect or spelling errors surviving.
- C: machine translation without native sign-off. Do not release this as usable local-language support.

### Deferred batch

**LG9. Add local record-and-play when no model works?**

- **A (recommended):** defer a new recording feature; start with text and fixed help. Avoid confusion with remote audio messages.
- B: add explicit temporary on-phone record/play for nearby help, with consent and deletion. Useful in person, does not solve distant messaging.

**LG10. Expand beyond the first language?**

- **A (recommended):** choose the next language from village feedback, then repeat rights, native review and quality gates. Keeps work tied to need.
- B: add the five principal candidates in parallel. Broader catalogue, high review/storage cost and unproven quality.

**LG11. Build a reusable local speech corpus or voice?**

- **A (recommended):** defer until the initial study shows a model gap and residents choose governance and consent terms. Avoid extracting recordings without a useful purpose.
- B: commission a distinct dataset project now, with separate permissions for distribution, training and voice synthesis. Higher cost; could improve the long-term language ecosystem.

## 11. Sources and reproducibility notes

All links below were opened as pages, raw source files or public JSON metadata. Large model files and audio links were not downloaded. HF metadata queries used `/api/models/<exact-id>?blobs=true`; sizes refer to the named file, not the sum of duplicate serialization formats.

**S1. Regional context, secondary sources.** Jean Sovon, Global Voices, 2023-05-15, [Digital technology: Promoting the use of Togolese national languages](https://globalvoices.org/2023/05/15/digital-technology-promoting-the-use-of-togolese-national-languages/). Opened passages distinguish Ewe/Kabiyè from the wider regional language landscape. [Languages of Togo](https://en.wikipedia.org/wiki/Languages_of_Togo) was cross-checked, but its broad north/south reach statement is marked citation-needed. No village prevalence percentages are adopted.

**S2. Regional context, secondary.** [WorldAtlas: What Languages Are Spoken In Togo?](https://www.worldatlas.com/articles/what-languages-are-spoken-in-togo.html), Ewe, Kabiyè, Gen and Tem sections. Used only for broad location leads, not its sweeping claims about French use or old population figures.

**S3. Primary Gen/Mina dataset and orthography.** Mozilla, [Common Voice Scripted Speech 27.0 - Mina](https://mozilladatacollective.com/datasets/cmu5s953o0045nq074il2j052), released 2026-09-17. Opened licence, usage restrictions, hours, speakers, symbol table and language description. Also opened the [Common Voice organisation catalogue](https://mozilladatacollective.com/organization/cmfh0j9o10006ns07jq45h7xk). The catalogue is not an exhaustive claim about all community data.

**S4. Additional location leads.** [Ifè language](https://en.wikipedia.org/wiki/If%C3%A8_language), Atakpamé/Dadja/Kamina section; [Kposo language](https://en.wikipedia.org/wiki/Kposo_language), Plateaux west of Atakpamé. Secondary, to be confirmed by the village partner.

**S5. MMS primary model card and files.** [facebook/mms-1b-all](https://huggingface.co/facebook/mms-1b-all); [file metadata](https://huggingface.co/api/models/facebook/mms-1b-all?blobs=true). Observed revision `3d33597edbdaaba14a8e858e2c8caa76e3cec0cd`. Read backbone/adapters and CC-BY-NC text. Its card has inconsistent overall language totals; this report relies on exact adapter files. Its “16000 kHz” wording is a typo; examples use 16,000 Hz.

**S6. MMS per-language coverage and TTS.** [Meta coverage table](https://dl.fbaipublicfiles.com/mms/misc/language_coverage_mms.html). Read rows and cross-checked exact assets. [Ewe](https://huggingface.co/facebook/mms-tts-ewe), [Kabiyè API](https://huggingface.co/api/models/facebook/mms-tts-kbp?blobs=true), [Gen API](https://huggingface.co/api/models/facebook/mms-tts-gej?blobs=true), [Tem API](https://huggingface.co/api/models/facebook/mms-tts-kdh?blobs=true), [Moba](https://huggingface.co/facebook/mms-tts-mfq), [Ifè API](https://huggingface.co/api/models/facebook/mms-tts-ife?blobs=true). Also inspected APIs for `nmz`, `las`, `bud`, `xon`, `gng`, `gux`, `ann`. Ewe observed revision `601388056e80f1ea403a7785507145ae03444f01`; Kabiyè `c7c0940a3acedbabff549d3c4256f82865149268`. The five tokenizer configs were read at `/facebook/mms-tts-<code>/resolve/main/tokenizer_config.json`.

**S7. Ewe research.** Wiafe et al., ICNLSP 2025, [paper PDF](https://aclanthology.org/2025.icnlsp-1.32.pdf) and [publication page](https://aclanthology.org/2025.icnlsp-1.32/). Read Table 3, dialect/error analysis and consent statement. The paper is evidence for its experiment, not a licence or score for every Ewe checkpoint.

**S8. Ewe checkpoints.** [ASR Africa XLS-R Ewe 100 hours](https://huggingface.co/asr-africa/wav2vec2-xls-r-ewe-100-hours), public validation table and metadata. [abiyo27 Whisper Small Ewe](https://huggingface.co/abiyo27/whisper-small-ewe), no card. [Paywinful Whisper Ewe](https://huggingface.co/Paywinful/whisper-ewe-asr) and [MALEKSAHLIA Wav2Vec2 Ewe](https://huggingface.co/MALEKSAHLIA/wav2vec2-base-finetuned-ewe) had no usable model card or weight files in the inspected listing; treat them as leads only.

**S9. Broader African ASR.** [Sunbird/SunflowerASR-51-african-languages](https://huggingface.co/Sunbird/SunflowerASR-51-african-languages), observed revision `4583c9dd251c422a4325fb0a0a509a3828097be2`. Read language-token reassignment, benchmark caveats, Ewe/Ikposo rows and metadata licence. Download access is gated; no terms were accepted and no weights obtained.

**S10. Omnilingual primary sources.** [Meta omniASR-CTC-300M](https://huggingface.co/facebook/omniASR-CTC-300M), [model API](https://huggingface.co/api/models/facebook/omniASR-CTC-300M?blobs=true), [supported language source](https://raw.githubusercontent.com/facebookresearch/omnilingual-asr/main/src/omnilingual_asr/models/wav2vec2_llama/lang_ids.py). [Official sherpa model documentation](https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html); [official release metadata](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models). Exact v1 graph size from [converted-model API](https://huggingface.co/api/models/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12?blobs=true). Upstream model observed revision `8e35f0cc28fa6099e0c14d56db85ce0423baa691`.

**S11. Kabiyè and local derivatives.** [Devsyril Whisper Small Kabiyè](https://huggingface.co/Devsyril/whisper-small-kbp); [Kabiyè sherpa MMS-TTS](https://huggingface.co/Devsyril/mms-tts-kbp-sherpa-onnx); [Tem export metadata](https://huggingface.co/api/models/Devsyril/mms-tts-kdh-sherpa-onnx?blobs=true). [Umbaji ASR metadata](https://huggingface.co/api/models/Umbaji/asr-mms-gej-kdh-nmz-agj?blobs=true) establishes actual large nested MMS checkpoints, not quality or rights clearance. Its root README fetch failed. [Tem derivative metadata](https://huggingface.co/api/models/Umbaji/cey-25-mms-tts-mozilla-kdh?blobs=true) declares CC-BY-NC 4.0. [Farmerline Ewe TTS](https://huggingface.co/FarmerlineML/Ewe-tts-2025_v3) has a placeholder card. `LEsprit/vits-finetuned-gej` metadata did not establish a usable released weight file.

**S12. Runtime support.** [Official sherpa MMS-TTS conversion guide](https://k2-fsa.github.io/sherpa/onnx/tts/mms.html); [pinned v1.13.8 Kotlin OfflineRecognizer source](https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/OfflineRecognizer.kt). These prove documented interfaces, not the app binary's execution.

**S13. WAXAL.** [Google WaxalNLP dataset card](https://huggingface.co/datasets/google/WaxalNLP), provider-specific ASR/TTS licence tables; [WAXAL paper v2](https://arxiv.org/html/2602.02734v2), 2026. Note the licence and coverage differences between evolving card and paper. Pin a subset and resolve consent scope before reuse.

**S14. Ewe alternative TTS.** [BibleTTS project](https://masakhane-io.github.io/bibleTTS/), Ewe hours, model and data links; [Coqui model manifest](https://raw.githubusercontent.com/coqui-ai/TTS/dev/TTS/.models.json), `tts_models/ewe/openbible/vits`, CC-BY-SA 4.0; [release metadata](https://api.github.com/repos/coqui-ai/TTS/releases/tags/v0.6.2_models), `tts_models--ewe--openbible--vits.zip` size. Do not use the mislinked Asante-Twi config for Ewe.

**S15. Community discovery.** [Masakhane community](https://github.com/masakhane-io/masakhane-community), BibleTTS initiative; [Lanfrica](https://lanfrica.com/), resource and model mapping mission. Discovery and partnership routes, not licences.

**S16. Other African-language efforts.** [NVIDIA Kinyarwanda NeMo work](https://developer.nvidia.com/blog/building-an-automatic-speech-recognition-model-for-the-kinyarwanda-language/); [Microsoft Research Paza](https://www.microsoft.com/en-us/research/blog/paza-introducing-automatic-speech-recognition-benchmarks-and-models-for-low-resource-languages/). Read original article sections, not just search descriptions or generated summaries.

**S17. Asset-size method.** The HF APIs cited above expose `siblings[].size`, licence metadata and commit SHA. The official sherpa release API supplies archive sizes; no mirror's advertised rounded size overrides it. The observed int8 v2 HF mirror [metadata](https://huggingface.co/api/models/csukuangfj2/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-v2-2026-02-05?blobs=true) contained only `.gitattributes`. Hash-pin the actual approved artifact at implementation intake.

**S18. Piper inventory.** [Piper voices repository](https://huggingface.co/rhasspy/piper-voices) and [voices.json](https://huggingface.co/rhasspy/piper-voices/resolve/main/voices.json). Language-code inventory checked programmatically; none of the five main candidates listed. Per-voice terms remain distinct.

**S19. Legal text, not advice.** [CC-BY-NC 4.0 legal code](https://creativecommons.org/licenses/by-nc/4.0/legalcode.en), sections 1, 2 and 3; [GNU AGPLv3](https://www.gnu.org/licenses/agpl-3.0.en.html), aggregate and additional-restrictions provisions. Qualified counsel must decide a public NC-model distribution boundary.

### Remaining evidence gaps

- Exact village language, dialect, reviewers and consent have not been supplied.
- No local-language quality, Android rendering, model export, phone memory/latency, signed APK growth or mixed-version UI gate was executed.
- Several HF cards have missing licences or placeholder evaluations. Gated weights were not accessed.
- WAXAL source terms differ across provider subsets and between paper and current card.
- The v2 int8 archive's final Phonemesh ZIP and unpacked file sizes remain unmeasured.
- BibleTTS Ewe offers a promising reusable voice, but its exact sherpa export remains a blocking experiment.

These are release gates, not reasons to report that no models exist. Text and reviewed spoken help can proceed after the owner's picks without making an unverified speech claim.
