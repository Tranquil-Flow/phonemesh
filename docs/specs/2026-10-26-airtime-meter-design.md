# Airtime meter (AM)

Draft for the owner's decisions, 2026-10-26. No code before the picks.

**Scope:** design only. Review the companion `2026-10-26-airtime-meter-mockup.html` before porting. Six phone screens show placement, not a working Android feature. No app sources were changed. No radios, phones, Gradle or emulator were used for this draft.

**Recommendation:** show estimated radio time before Send. Keep actual progress separate from estimated time. Explain confirmed waits without inventing a delivery countdown. Add passive local-radio observations and an optional, read-only hub status response. Never change modem settings, sending limits, encryption or retries to improve the meter.

## 1. Goal and people

Help someone decide whether to send words or a picture. Show why a larger picture takes longer, what is happening after Send, and why a busy radio sometimes waits. This helps everyone sharing a radio, including people who cannot read the numbers. Pictures, a spoken explanation on request, and familiar message ticks carry the same meaning as the text. The pilot's French UI must work without English technical words.

**Core:** draft estimate, picture comparison, honest bubble progress, confirmed duty waits, EN/FR copy and accessibility. **Adjacent:** the optional radio detail panel and hub observations. **Deferred:** measured arrival-time prediction, personal quotas, scheduling changes and modem controls. **Distraction:** changing transport stacks or weakening encryption.

The bottleneck is not calculating seconds. It is knowing whether a radio actually transmitted, and what other people already used. The next action belongs to the owner: review the six screens and choose AM1–AM7. Then prove observations with fake events before touching hardware.

## 2. How it works today

The source checkout was read at `e705adfc2e118c0d0e95a389eb057432021d5f21`, branch `v0.1.1`. Existing edits in `android/app/build.gradle.kts` and the accounts spec were left alone. References below are checkout-relative. This is a moving working tree, not a sealed release audit.

| Label | VERIFIED observation | Source |
|---|---|---|
| S1 | Core provides seven preset shapes, a 16-symbol preamble, and `seconds()`. Its picture helper adds 33 bytes per payload and one 36-byte routing acknowledgement per packet. It explicitly excludes relays and retries. | `android/core/src/main/kotlin/org/phonemesh/core/LoraAirtime.kt:9-48` |
| S2 | The hub uses a different overhead: payload + 22 bytes. Its default is LongFast. `DutyCycle` tracks a rolling hour, not a reset at each clock hour. It has `used`, `allows`, `record`, but no next-allowed-time method. | `android/hub/src/main/kotlin/org/phonemesh/hub/Airtime.kt:10-38` |
| S3 | The hub reserves airtime before submitting. Its persisted reservations survive restart. It uses a 500 ms gap and round-robin phone queues. The percentage defaults to 10 and is configurable, not inferred from a region handshake. | `android/hub/src/main/kotlin/org/phonemesh/hub/Hub.kt:24-38,112-167,180-207`; `android/hub/src/main/kotlin/org/phonemesh/hub/HubConfig.kt:8-37` |
| S4 | `WireV2.MAX_PACKET` is 228. An ordinary encrypted envelope is 9-byte header plus 16-byte GCM tag. A fitting ratchet announcement adds 35 bytes. Receipt data is 10 bytes plus its kind byte. A piggyback receipt adds 10 bytes. | `android/core/src/main/kotlin/org/phonemesh/core/wire/WireV2.kt:6-22,48-60`; `android/core/src/main/kotlin/org/phonemesh/core/wire/Bodies.kt:9-13,36-41,58-75,153-181` |
| S5 | A v2 picture has a HEAD and 198-byte PART bodies. A full part fills 228 bytes after wrapping. There can be 17 data parts, plus HEAD, for a 3,200-byte image. | `android/core/src/main/kotlin/org/phonemesh/core/PictureWire.kt:25-42,61-84,88-104` |
| S6 | Current quality choices are Medium 96 px / 500 B, Large 128 / 1,000 B, Very large 192 / 2,000 B, Maximum 256 / 3,000 B. These are target budgets, not guaranteed encoded lengths. Compression may shrink dimensions; the final fallback can exceed a target but never the wire ceiling. Sketches are normally 20–25 B. | `android/app/src/main/java/org/phonemesh/app/PictureMaker.kt:22-36,73-103,133-142` |
| S7 | `PictureSender` waits for the other radio's acknowledgement and permits three attempts. That is the older port-300 path. Current person pictures use `Postman.sendPicture`, independent encrypted outbox rows, and `pumpMail`. Do not apply the legacy per-part routing ACK model to current broadcast mail. | `android/core/src/main/kotlin/org/phonemesh/core/PictureSender.kt:3-6,25-49,59-60`; `android/core/src/main/kotlin/org/phonemesh/core/mail/Postman.kt:47-74`; `android/app/src/main/java/org/phonemesh/app/AppCore.kt:1758-1778,1899-1909` |
| S8 | Draft fit already blocks Send when too long. Legacy text is capped at 200 UTF-8 bytes including its language prefix. Person drafts still use that fit rule; shared posts instead use their real 228-byte envelope room. | `android/core/src/main/kotlin/org/phonemesh/core/MessageCodec.kt:3-4,39-43`; `android/core/src/main/kotlin/org/phonemesh/core/DraftRules.kt:3-26`; `android/app/src/main/java/org/phonemesh/app/AppCore.kt:664-682`; `android/app/src/main/java/org/phonemesh/app/ConversationActivity.kt:692-707` |
| S9 | The queued-send code records that the radio's Bluetooth queue holds three writes and silently drops a fourth. `mailFlight` and queued draining submit one at a time. `QUEUED` frees the mail flight, meaning accepted by the radio, not aired or delivered. | `android/app/src/main/java/org/phonemesh/app/AppCore.kt:852-914,1647-1723`; `android/radio-ble/src/main/kotlin/org/phonemesh/ble/BleMeshTransport.kt:779-789` |
| S10 | A hub is preferred over a stock radio: Wi-Fi, then pinned TLS over a Bluetooth hub bridge. The hub stores exact ciphertext and later airs it. Phone-to-hub Wi-Fi is not a LoRa hop. Existing `/send` returns stored/already/busy, not airtime status. | `android/app/src/main/java/org/phonemesh/app/AppCore.kt:944-950,1026-1038`; `android/hub/src/main/kotlin/org/phonemesh/hub/HubServer.kt:34-36,49-59`; `android/hub/src/main/kotlin/org/phonemesh/hub/Hub.kt:81-85,136-144` |
| S11 | `radio.modemPreset()` is nullable for custom/unknown settings. The existing picture estimate silently falls back to LongFast. Picture preview already says approximate seconds. | `android/radio-ble/src/main/kotlin/org/phonemesh/ble/MeshRadio.kt:81-82`; `android/radio-ble/src/main/kotlin/org/phonemesh/ble/BleMeshTransport.kt:744`; `android/app/src/main/java/org/phonemesh/app/AppCore.kt:1765-1773`; `android/app/src/main/java/org/phonemesh/app/PictureActivity.kt:275-287` |
| S12 | Picture progress counts recipient-delivered rows. A bubble is Delivered only when HEAD and every part are delivered; radio acceptance and hub custody are weaker states. | `android/app/src/main/java/org/phonemesh/app/AppMailStore.kt:132-158` |
| S13 | All app sends use primary channel 0. The core country file has sourced DE and TG rows for 869.4–869.65 MHz, 10%. A helper choice for an unknown country is not legal verification. | `android/app/src/main/java/org/phonemesh/app/AppCore.kt:876,1909,2396`; `android/core/src/main/resources/countries.txt:10-13`; `docs/specs/2026-10-07-radio-region-design.md:48-57,138-147` |
| S14 | Vendored telemetry distinguishes current-channel TX/RX/noise utilization from this transmitter's last-hour TX percentage. The radio interface does not yet expose these metrics. The routing proto defines `DUTY_CYCLE_LIMIT = 9`. | `android/mesh-protocol/src/main/proto/meshtastic/telemetry.proto:25-33`; `android/radio-ble/src/main/kotlin/org/phonemesh/ble/MeshRadio.kt:13-99`; `android/mesh-protocol/src/main/proto/meshtastic/mesh.proto:1161` |

**Important corrections to shorthand:** a full packet is not about one second on LongFast under either checked estimator. Payload 228 + hub overhead 22 produces **2.116 seconds**. A short private message is about one second. Also, the 200-byte limit is not universal across current shared-chat frames: `SharedPost.room()` can allow 207 text bytes with a three-byte name and `fr`. This meter must reuse the actual draft rule, not silently change it. See `android/core/src/main/kotlin/org/phonemesh/core/wire/SharedPost.kt:13-21,29,53-56,76-78`. Both corrections are **INFERRED calculations from VERIFIED code**.

## 3. Regional limits and what “busy” means

**VERIFIED:** Meshtastic's opened documentation describes EU_868 as 869.4–869.65 MHz with a 10% duty limit. It says the firmware checks a rolling hour each minute and stops transmissions at the limit.[1] Its overview agrees on the sub-band and LongFast parameters.[2]

**VERIFIED historical standard, not fresh country approval:** ETSI EN 300 220-2 V3.2.1, Annex B, table B.1, page 22, lists 868.000–868.600 MHz at ≤1%, and 869.400–869.650 MHz at ≤10%, with a polite-spectrum-access alternative.[4] These are different sub-bands. A “868 MHz” sticker does not establish which rule applies. Do not substitute 1% for Meshtastic's ordinary EU_868 setting, and do not assume all 868 MHz operation gets 10%. The 1% calculations below are comparison scenarios, not instructions to retune.

The opened Meshtastic pages are labelled version 2.8. Core's preset comment names firmware 2.7.26. Newer regional plans and narrowband presets must not be imported silently. Our table covers exactly the seven shapes already in core. Unknown or custom presets give “Time unknown”, not a fictitious LongFast estimate. No preset-changing button is proposed.

**VERIFIED documentation distinction:** channel utilization is local channel occupation; TX utilization is this radio's own transmissions.[3] **INFERRED design consequence:** channel occupation is not a personal remaining budget. Twenty people sharing one transmitter share its allowance. Twenty separate transmitters still compete on the channel, but do not legally pool their allowances. High channel occupation can delay a send even if this transmitter has budget left. A single aggregate percentage cannot tell when old transmissions will age out.

**UNKNOWN:** the exact frequency, firmware, actual airtime, traffic from other clients, legal settings and type approval of a future deployment. No hardware was inspected. The country file records the project's checked sources, not a new legal opinion. Preserve the region setup gate. Never offer a duty override, “unlimited” label, or a reconnect-to-reset workaround. For non-EU regions, do not invent a 1% or 10% rule; a missing duty cap does not mean unconstrained operation.

## 4. Calculated cost tables

All numbers here are **INFERRED**, calculated in Python from the checked source formula. They are not measured delivery times. The script is [`calc/2026-10-26-airtime-meter-calc.py`](calc/2026-10-26-airtime-meter-calc.py) and its tables are in [`calc/2026-10-26-airtime-meter-tables.md`](calc/2026-10-26-airtime-meter-tables.md). The formulas and inputs below also make the tables reproducible by hand.

### Assumptions and byte layouts

One initial transmission per packet, one hop, no collision, relay, retransmission, busy-channel deferral, receipt quiet period, Bluetooth delay or duty wait. All textual examples use canonical `fr`. Counts are bytes, never character counts.

- **Tagged text:** `[pm1:fr] ` adds 9 B. A 40 B body yields 49 B; a full legacy envelope is 200 B. Old direct chats are read-only; this row remains relevant to compatible legacy-format shared replies.
- **Current shared text, name `Ama`:** version 1 B + kind 1 + account id 8 + time 4 + name length 1 + name 3 + language length 1 + language 2 + text. Fixed cost 21 B. No signature is implied. A reply adds 4 B.
- **Private text:** first byte 1 + recipient code 8 + optional masked ratchet field 35 + encrypted body + GCM tag 16. Body is kind 1 + language length 1 + `fr` 2 + text. Thus text + 29 B, or text + 64 B when keyed. A piggyback receipt adds 10 B only when it fits. A reply adds 4 B. No additional fragment is created for a key that does not fit.
- **Private receipt:** 9 + kind 1 + acknowledgement 10 + tag 16 = 36 B, or 71 B with the ratchet field. One receipt can acknowledge multiple parts. It is not one receipt per picture part. `Postman` schedules receipts after 15–60 seconds of quiet unless one rides on a reply (`Postman.kt:18-26,58-64`).
- **Hub receipt on port 302:** version 1 + count 1 + 8 B per code. Table covers one code, and the hub's current batching threshold of 25 codes (202 B). Wire capacity is 28 codes, not the configured batching threshold (`wire/HubAck.kt:4-15`; `hub/Hub.kt:191-201`).
- **Picture:** assume a 25 B sketch. HEAD payload is 16 + 25 + kind 1 + envelope 25 = 67 B. Each part is data up to 198 + part header 4 + kind 1 + envelope 25 = up to 228 B. Total count is `1 + ceil(imageBytes / 198)`. For 500 B, payloads are `[67, 228, 228, 134]`.
- **Radio overhead for the main table:** add 22 B per application payload, matching the checked hub broadcast estimator. This is appropriate as an explicit shared model for ports 301–303, not a claim of byte-perfect RF serialization. Actual protobuf fields, encryption framing and firmware may change the over-air length. Use real serialized payload lengths and versioned overhead assumptions; validate on hardware later.

Use the core formula with explicit header, CRC and preamble 16:

```
Ts = 2^SF / (bandwidth_kHz * 1000)
DE = 1 when Ts >= 0.016 seconds, otherwise 0
payloadSymbols = 8 + max(ceil((8*onAirBytes - 4*SF + 44)
                             / (4*(SF - 2*DE))) * codingDenominator, 0)
seconds = (16 + 4.25 + payloadSymbols) * Ts
messageSeconds = sum(seconds(payloadLength + 22))
wholeEventsPerHour = floor(3600 * dutyFraction / messageSeconds)
```

The hub uses `Ts > 0.016`; core uses `>=`. No preset here sits at the equality boundary. Consolidate the formula in future code, with an explicit overhead strategy rather than keeping two drifting copies.

Preset columns: **SF** ShortFast (7/250/4:5), **SS** ShortSlow (8/250/4:5), **MF** MediumFast (9/250/4:5), **MS** MediumSlow (10/250/4:5), **LF** LongFast (11/250/4:5), **LM** LongModerate (11/125/4:8), **LS** LongSlow (12/125/4:8). Triples are spreading factor / kHz / coding rate. LS is retained because core supports it; current upstream calls it deprecated.[2]

### Initial transmitter airtime, seconds

The “Payload bytes” column is the sum of application envelopes, excluding the per-send 22 B radio estimate. The final 3,200 B picture is a wire-limit boundary case, not a quality choice.

| Event | Payload bytes | Sends | SF | SS | MF | MS | LF | LM | LS |
|---|---|---|---|---|---|---|---|---|---|
| Tagged text, 40 B French | 49 | 1 | 0.068 | 0.126 | 0.222 | 0.423 | 0.764 | 2.560 | 4.858 |
| Tagged text, 200 B envelope | 200 | 1 | 0.178 | 0.316 | 0.570 | 1.037 | 1.911 | 7.016 | 12.722 |
| Shared text, 40 B; 3 B name | 61 | 1 | 0.078 | 0.142 | 0.252 | 0.464 | 0.887 | 2.953 | 5.382 |
| Shared text, 200 B; 3 B name | 221 | 1 | 0.194 | 0.346 | 0.621 | 1.119 | 2.075 | 7.541 | 13.771 |
| Private text, 40 B; plain | 69 | 1 | 0.084 | 0.152 | 0.273 | 0.505 | 0.928 | 3.215 | 5.906 |
| Private text, 40 B; keyed | 104 | 1 | 0.109 | 0.193 | 0.355 | 0.648 | 1.174 | 4.133 | 7.741 |
| Private text, 191 B; plain | 220 | 1 | 0.194 | 0.341 | 0.611 | 1.119 | 2.034 | 7.541 | 13.771 |
| Receipt, plain | 36 | 1 | 0.061 | 0.106 | 0.201 | 0.361 | 0.682 | 2.167 | 4.071 |
| Receipt, keyed | 71 | 1 | 0.086 | 0.152 | 0.273 | 0.505 | 0.928 | 3.215 | 5.906 |
| Hub receipt, 1 code | 10 | 1 | 0.040 | 0.075 | 0.140 | 0.259 | 0.477 | 1.511 | 2.761 |
| Hub receipt, 25 codes | 202 | 1 | 0.181 | 0.321 | 0.570 | 1.037 | 1.911 | 7.016 | 12.722 |
| Picture 500 B | 657 | 4 | 0.611 | 1.083 | 1.942 | 3.576 | 6.578 | 23.740 | 43.024 |
| Picture 1000 B | 1247 | 7 | 1.138 | 2.015 | 3.620 | 6.667 | 12.229 | 44.397 | 80.142 |
| Picture 2000 B | 2397 | 12 | 2.140 | 3.788 | 6.808 | 12.509 | 22.888 | 83.673 | 150.831 |
| Picture 3000 B | 3547 | 17 | 3.139 | 5.555 | 9.985 | 18.351 | 33.507 | 123.081 | 221.520 |
| Picture 3200 B | 3777 | 18 | 3.340 | 5.912 | 10.616 | 19.511 | 35.664 | 130.884 | 235.553 |

### Whole events per transmitter per hour

Each cell is **1% / 10%**, using unrounded airtime. These are isolated airtime-budget ceilings, not throughput guarantees. Deduct background traffic, retries and all transmissions made by this radio for others. Return receipts on a different radio consume that radio's budget. A hub that both forwards messages and airs receipts pays both. Zero means a whole event does not fit one hour's allowance; parts may span hours.

| Event | SF | SS | MF | MS | LF | LM | LS |
|---|---|---|---|---|---|---|---|
| Tagged text, 40 B French | 527 / 5276 | 285 / 2852 | 162 / 1623 | 85 / 851 | 47 / 471 | 14 / 140 | 7 / 74 |
| Tagged text, 200 B envelope | 201 / 2019 | 114 / 1140 | 63 / 631 | 34 / 347 | 18 / 188 | 5 / 51 | 2 / 28 |
| Shared text, 40 B; 3 B name | 458 / 4588 | 254 / 2542 | 142 / 1426 | 77 / 776 | 40 / 405 | 12 / 121 | 6 / 66 |
| Shared text, 200 B; 3 B name | 185 / 1858 | 103 / 1039 | 57 / 579 | 32 / 321 | 17 / 173 | 4 / 47 | 2 / 26 |
| Private text, 40 B; plain | 430 / 4307 | 237 / 2371 | 131 / 1319 | 71 / 713 | 38 / 388 | 11 / 111 | 6 / 60 |
| Private text, 40 B; keyed | 329 / 3297 | 186 / 1867 | 101 / 1014 | 55 / 555 | 30 / 306 | 8 / 87 | 4 / 46 |
| Private text, 191 B; plain | 185 / 1858 | 105 / 1054 | 58 / 589 | 32 / 321 | 17 / 177 | 4 / 47 | 2 / 26 |
| Receipt, plain | 594 / 5946 | 340 / 3404 | 178 / 1789 | 99 / 995 | 52 / 527 | 16 / 166 | 8 / 88 |
| Receipt, keyed | 417 / 4179 | 237 / 2371 | 131 / 1319 | 71 / 713 | 38 / 388 | 11 / 111 | 6 / 60 |
| Hub receipt, 1 code | 898 / 8985 | 479 / 4799 | 257 / 2575 | 138 / 1389 | 75 / 754 | 23 / 238 | 13 / 130 |
| Hub receipt, 25 codes | 199 / 1990 | 112 / 1122 | 63 / 631 | 34 / 347 | 18 / 188 | 5 / 51 | 2 / 28 |
| Picture 500 B | 58 / 589 | 33 / 332 | 18 / 185 | 10 / 100 | 5 / 54 | 1 / 15 | 0 / 8 |
| Picture 1000 B | 31 / 316 | 17 / 178 | 9 / 99 | 5 / 53 | 2 / 29 | 0 / 8 | 0 / 4 |
| Picture 2000 B | 16 / 168 | 9 / 95 | 5 / 52 | 2 / 28 | 1 / 15 | 0 / 4 | 0 / 2 |
| Picture 3000 B | 11 / 114 | 6 / 64 | 3 / 36 | 1 / 19 | 1 / 10 | 0 / 2 | 0 / 1 |
| Picture 3200 B | 10 / 107 | 6 / 60 | 3 / 33 | 1 / 18 | 1 / 10 | 0 / 2 | 0 / 1 |

### Picture choices, old helper and encryption variation

| Choice | Target bytes / side | HEAD + parts | LF sender airtime | LF with fitting key + piggyback extras* | Existing `pictureSeconds` |
|---|---|---|---|---|---|
| Medium / Moyenne | 500 / 96 px | 1 + 3 | 6.578 s | 7.234 s | 8.978 s |
| Large / Grande | 1,000 / 128 px | 1 + 6 | 12.229 s | 12.884 s | 16.429 s |
| Very large / Très grande | 2,000 / 192 px | 1 + 11 | 22.888 s | 23.544 s | 30.089 s |
| Maximum / Maximale | 3,000 / 256 px | 1 + 16 | 33.507 s | 34.204 s | 43.708 s |
| Wire ceiling, not a choice | 3,200 / up to 512 px | 1 + 17 | 35.664 s | 36.319 s | 46.465 s |

*Illustrative upper variant for the same payloads: add 35 key bytes and 10 receipt bytes wherever both fit; otherwise add the fitting receipt alone. Full parts cannot carry either. Session state decides which extras actually occur. This is not an end-to-end upper bound. No photo was compressed for these tables. Colour and grey use the same size targets; actual `PreparedPicture.bytes` and sketch length must replace targets in the UI. Do not confuse display-only AI upscaling with transmitted quality. Sketch-only remains a wire capability, not a current `PictureSize` choice.

**Recommendation:** replace the current picture estimate's legacy ACK-per-part assumption for person chats. Do not quietly reuse it under a new “arrival” label. It currently adds PKI-direct overhead and routing acknowledgements even though v2 pictures use account broadcasts. Keep a legacy strategy where the old wire path is actually used. The new meter adds **zero LoRa bytes**.

### Ten or twenty people sharing one radio

For a 40 B private text, LF initial TX is 0.928 s. With the hub's 0.5 s gap, ten one-text senders consume 9.277 s on air; the last waits about 12.850 s before its turn. Twenty consume 18.555 s on air; the last waits about 27.127 s. Assumes one ready message each, one phone queue each, no other work and available budget. This is a queue illustration, not a prediction from channel utilization.

For a 3,000 B picture, LF initial TX is 33.507 s across 17 sends. Ten such pictures consume **335.073 s** out of 360 s, before receipts or retries. Including the hub gaps, processing that batch costs about **420.073 s** elapsed even without duty waiting. Twenty need **670.147 s** on air, so cannot all fit a fresh 10% hourly budget. A 1% allowance has only 36 s per hour: one large picture uses nearly all of it.

The isolated 10% ceiling is ten whole 3,000 B pictures per hour. Equal long-run sharing gives about 1.074 pictures per person-hour for ten people, or 0.537 for twenty, before other work. This is arithmetic, not a quota. On round-robin full-packet turns, ten active queues take about 26.156 s for a round; twenty take 52.312 s. A picture needs many rounds. A message behind a picture on the same phone queue can wait longer. The hub groups by phone address, not authenticated person (`HubServer.kt:54`; `Hub.kt:180-187`); these examples require distinct queues and do not prove fair sharing by person.

Receipts can add 15–60 s of quiet time before Delivered appears, even after all forward airtime is over. Lost contact or a missing key can make delivery indefinite. Do not multiply initial airtime by people or hops and call the result an ETA.

## 5. Options compared

All options preserve v0.1.0 wire formats, private encryption and explicit Send. None sends audio or changes radio settings.

| Option | Extra bytes / packets / airtime per message | Security | Phone cost | APK growth estimate | Effort and compatibility |
|---|---|---|---|---|---|
| A. Local estimates only | 0 / 0 / 0 | No new observations; cannot diagnose every wait | Pure length/math, one UI refresh after edits | 4–10 KiB, unmeasured | Smallest slice; old peers/hubs unchanged; cannot distinguish hub duty pause |
| B. Estimates + passive radio facts + optional hub status (recommended) | 0 LoRa B / 0 LoRa packets / 0 LoRa s; optional ≤256 B hub response before HTTP/TLS overhead | Local aggregates only; pinned hub advisory is not proof of RF use | Same math; existing events; foreground status at most once per 30 s | 12–24 KiB, unmeasured, no library | Medium cross-layer work; old hub gets honest unknown state |
| C. Calibrated arrival predictor and quotas | No wire cost if local only; active probes would add traffic and are rejected | Timing history increases metadata and tracking | Retained model/history; more failure cases | 24–64 KiB planning range, unmeasured | High effort; still cannot promise offline-recipient arrival; defer |

**Size gate:** current ceilings are 16,575,000 B arm64 and 14,715,000 B armeabi-v7a (`android/gradle.properties:8-9`). The brief reports only about 1.6 KB signed arm64 headroom, not remeasured here. B likely needs an owner-approved increase. Reserve at most **24,576 B per ABI** for planning; measure signed before/after against the same candidate and raise only by the measured, approved amount. No bundled images, models, audio or new dependencies. The HTML is review-only and never bundled.

Choose B, in slices. It tells the truth before sending without requiring a new peer. It can explain observed waits but refuses an exact arrival timer. If hub status misses the release window, A remains a useful explicit fallback, not a reason to falsify a countdown.

## 6. Recommended design

### 6.1 A small estimate model, not another sender

Proposed pure-core value `AirtimeEstimate` contains: route kind; preset and provenance; ordered payload length bounds; initial TX seconds bounds; remaining-to-submit seconds bounds; accepted-part count; total-part count; recipient-confirmed count; wait reason; optional wait-until bound; sample age. Values can be unknown. This is a proposed internal seam, not an existing API.

Before Send, compute lengths without sealing, generating keys, consuming counters or saving ciphertext. Read only the existing session facts needed to decide a fitting ratchet field and piggyback receipt. If incoming traffic can change those facts, show a rounded range until actual sealing. After Send, use durable encoded row lengths, never re-encrypt for the meter. Include held rows in the total; they are not zero-length completed work.

Use the modulation of the radio that will air the bytes. A phone connected to an unrelated Bluetooth radio while using a hub must not borrow that radio's preset. A disconnected, custom or unsupported preset gives no seconds unless the user opens an explicitly labelled example comparison. If known custom SF/bandwidth/coding values are later exposed, they can be used only after validation, with no fallback.

Refresh on draft, language, reply, profile-name, session, route and modem changes. Debounce typing by about 200 ms. The final Send path must recheck fit and recompute from committed bytes. Estimates never decide whether ciphertext may be sent.

### 6.2 What the clock can and cannot say

There are three distinct quantities:

1. **Radio time:** estimated active transmitter seconds for the initial forward send. Rounded up to whole seconds below one minute; above it round up to whole minutes. If bounds round differently, show a range. Never show decimals in the main UI.
2. **Queue or limit wait:** a reason and, only when supported, a bound until the next eligible attempt. Do not add unknown channel contention and recipient absence into a fake total.
3. **Delivery:** existing state and authenticated receipts. Radio acceptance is not delivery. Hub storage is not delivery.

A known duty wait is a separate clock row. At zero, refresh facts and retain the wait state until acceptance becomes possible. Never change to Sent because a clock elapsed. Avoid invented “3–5 seconds to arrive” ranges: without field calibration there is no defensible upper bound. The minimum active-airtime estimate plus a separately labelled wait satisfies the useful part of the time question honestly.

### 6.3 Where the person sees it

1. **Conversation draft:** put a compact radio icon and time directly below the dashed draft bubble, above existing Clear / Listen / Send actions. Tapping it opens a short explanation. `TOO_LONG` keeps “Too long. Make it shorter.” and disabled Send. Hide time for EMPTY/INVALID. Do not suggest a long draft will split or eventually send. Do not crowd the region setup card or push Send below the viewport.
2. **Picture to send:** replace the existing seconds line with the same component. Keep Medium, Large, Very large, Maximum and the exact prepared preview. Put time beside each quality only after that actual encoding is available; otherwise show a labelled target example, not a measurement. Smaller is an offered choice, never an automatic downgrade after Send.
3. **Outgoing bubble:** show the strongest real state. During submission show a segmented bar with “2 of 4 with the radio”, not “half delivered”. When radio acceptance completes, keep existing Sent and “Waiting for their phone” for private mail. Fill recipient-delivery marks only on receipts. Retry can increase remaining work; show “Trying again” rather than reverse a fake stopwatch. Shared chat retains its existing Sent terminal semantics.
4. **Settings → Radio → Radio time:** proposed secondary detail panel, also reachable from the draft time. Show source and age, known modulation, separate nearby occupation and transmitter last-hour use, and the explanation picture. Keep the underlying existing Radio screen and back path. Not a new first-run step or persistent dashboard.
5. **Hub route:** show phone → Wi-Fi → hub → radio. State “Wi-Fi uses no radio time here.” beside the first segment, and “The hub sends by radio.” beside the second. This does not mean the whole message is airtime-free. Existing v0.1.0 hub screen is **Village hub / Relais du village**; the approved v0.1.1 label is **Local hub / Relais local**, not a new AM naming decision.

### 6.4 Pictures, progress and accessible interaction

Use dark outlined radio, clock, pause bars, small picture tiles and the app's existing delivery ticks. Yellow `#FFD54F` highlights the chosen control, not success. Do not reuse signal-strength bars or a battery gauge for airtime. These already mean something else.

In the explanation, one text tile passes through the radio, then four picture tiles pass one at a time. The still frame must explain the same thing. For up to six parts show discrete outlined tiles, filling only on the labelled event. For more parts use one proportional bar plus “n of m” and a picture icon. Ticks alone do not communicate duration. Do not draw one tick per second or animate a bar against an unmeasured ETA.

Big Talk mic remains when the composer is idle. As in the current draft UI, Clear / Listen / Send replace it during a draft. Minimum targets are 48 dp, main type at least 18 sp, visible focus, adequate contrast, no colour-only meaning. At 200% font scale, wrap the status above the actions and permit vertical scrolling. Reduced motion uses still tiles; no essential animation or flashing. Do not autoplay sound.

The meter is one accessible group. Exclude decorative icons and bar segments from focus. Give the group a short state description and a distinct “Explain radio time” action. Announce a meaningful phase change once, not every second or each repaint. Never announce during microphone capture; defer until capture ends. Spoken explanation uses existing on-device TTS only after Listen. Missing voice packs leave visible text and icons; never download silently. Accounts decision 25 records existing TalkBack recording interference. This feature does not claim to fix that wider problem, but its new live announcements must not worsen it.

### 6.5 EN/FR copy contract

All proposed user-facing sentences below must pass `CopyRules.words <= 8` and `bannedIn.isEmpty()` after representative interpolation. Dynamic counts use digits; never insert arbitrary names into a capped sentence. Each sentence is a separate string or separately validated sentence. No “packet”, “ack”, “node”, “paquet”, “nœud” or “gatt” in UI.

| Key / use | English | Français |
|---|---|---|
| am_title | Radio time | Temps radio |
| am_seconds | About {n} seconds on the radio. | Environ {n} secondes à la radio. |
| am_range | About {a} to {b} seconds. | Environ {a} à {b} secondes. |
| am_minutes | About {n} minutes on the radio. | Environ {n} minutes à la radio. |
| am_unknown | Time unknown. | Temps inconnu. |
| am_settings_unknown | Radio settings are unknown. | Les réglages radio sont inconnus. |
| am_more | Bigger pictures take more radio time. | Les grandes photos prennent plus de temps. |
| am_pieces | Pictures travel in small pieces. | Les photos partent en petits morceaux. |
| am_shared | Everyone shares this radio. | Tout le monde partage cette radio. |
| am_others | Other messages can make yours wait. | D'autres messages peuvent faire attendre le vôtre. |
| am_nearby | Nearby radios are busy. | Les radios proches sont occupées. |
| am_duty | The radio must wait. | La radio doit attendre. |
| am_duty_why | It used its allowed radio time. | Elle a utilisé son temps radio autorisé. |
| am_earliest | Can try again in {n} minutes. | Nouvel essai possible dans {n} minutes. |
| am_wait_unknown | We cannot time this wait. | Cette attente n'a pas de durée connue. |
| am_kept | Your message stays here. | Votre message reste ici. |
| am_resume | It will try when allowed. | L'envoi reprendra quand ce sera permis. |
| am_radio_count | {n} of {m} with the radio. | {n} sur {m} avec la radio. |
| am_hub_count | {n} of {m} with the hub. | {n} sur {m} avec le relais. |
| am_delivered_count | Their phone received {n} of {m}. | Leur téléphone a reçu {n} sur {m}. |
| am_phone_wait | Waiting for their phone. | En attente de leur téléphone. |
| am_trying | Trying again. | Nouvel essai. |
| am_more_time | This may take longer. | Cela peut prendre plus de temps. |
| am_remaining | About {n} seconds still to send. | Encore environ {n} secondes de temps radio. |
| am_no_reading | No recent reading. | Aucune mesure récente. |
| am_wifi | Wi-Fi uses no radio time here. | Ici, le Wi-Fi n'utilise aucun temps radio. |
| am_hub_radio | The hub sends by radio. | Le relais envoie par radio. |
| am_hub_wait | The hub radio must wait. | La radio du relais doit attendre. |
| am_hub_unknown | The hub's wait is unknown. | L'attente du relais est inconnue. |
| am_not_delivery | This is not a delivery time. | Ce n'est pas un délai de réception. |
| am_explain | Explain radio time | Expliquer le temps radio |
| am_choose | Choose a smaller picture | Choisir une photo plus petite |
| am_last_hour | Radio use during the last hour. | Utilisation radio pendant la dernière heure. |
| am_nearby_use | Nearby radio use | Utilisation des radios proches |
| am_old | These readings are old. | Ces mesures sont anciennes. |
| am_budget | {used} of {limit} seconds used. | {used} secondes utilisées sur {limit}. |
| am_model | Radio time is an estimate. | Le temps radio est une estimation. |
| am_no_radio | Waiting for a radio. | En attente d'une radio. |
| am_talkback_draft | Estimated radio time: {n} seconds. | Temps radio estimé : {n} secondes. |
| am_talkback_progress | With the radio: {n} of {m}. | Avec la radio : {n} sur {m}. |
| am_talkback_wait | Waiting for allowed radio time. | En attente de temps radio autorisé. |
| existing too_long | Too long. Make it shorter. | Trop long. Raccourcissez. |

Singular variants must use “second / seconde”, “minute / minute” with the same rule. Existing Listen, Send, Talk, Radio, Settings and quality labels come from EN/FR resources, not new synonyms. Local-language translations are a separate approved workstream; do not invent Ewe or Kabiyè copy.

TalkBack examples concatenate independently short sentences: “Estimated radio time: 7 seconds. This is not a delivery time.” Progress: “With the radio: 2 of 4. Waiting for their phone.” Limit: “Waiting for allowed radio time. Your message stays here.” French equivalents use the same table. Native-speaker and non-reader comprehension are **UNKNOWN**, not established by word counting.

### 6.6 Duty state and optional observations

**Local stock radio:** extend the event boundary to expose passive device metrics and a typed routing refusal reason. Parse only the connected radio's own telemetry for TX budget; remote broadcasts are not this radio's budget. Sample fields: monotonic receipt time, channel occupation %, TX last-hour %, current settings identity, and explicit duty-limit refusal if present. Validate finite 0–100 values; absence is unknown, never zero. Drop observations on disconnect, radio/config change or boot change. After 120 seconds without an update show old/unknown, not available allowance. Do not request extra telemetry over LoRa.

An explicit duty refusal supports “The radio must wait.” Generic queue rejection does not. TX-use percentage near a known limit supports a cautious warning, not an exact restart time. Radio telemetry has no historical expiry buckets, so show unknown wait duration. Do not build a second legal limiter from phone-only sends: it misses relays, firmware packets and other phones. Firmware remains the transmitting authority.

**Optional hub change, AM4:** add GET `/airtime`, served over the existing pinned local TLS path and the equivalent Bluetooth bridge route. Existing `/hello`, `/send`, `/mail`, wire ports, request bodies and statuses stay byte-for-byte compatible. No extra LoRa traffic. Poll only while a relevant screen is visible, at most once per 30 seconds; reuse the last response elsewhere. A failed status request must not mark the hub mail path failed.

Proposed response is UTF-8 ASCII lines, LF terminated, no identifiers, maximum 256 bytes:

```
phonemesh-airtime 1
preset LONG_FAST
basis configured
window_ms 3600000
budget_ms 360000
used_ms 359500
pause duty
next_ms 120000
valid_ms 60000
```

These are **illustrative fixture values**, not live measurements. Grammar:

- First line is exact version marker. `preset` is a supported name or `unknown`; `basis` is `observed`, `configured` or `unknown`. Configured means the hub estimator's setting, not verified hardware.
- `window_ms` is 3600000 for this version. `budget_ms` and `used_ms` are nonnegative integers bounded by 3600000, or `unknown` for budget. Used means hub reservations, **not all radio TX**.
- `pause` is `duty`, `none` or `unknown`. `next_ms` is a nonnegative duration ≤3600000, or `unknown`. It is the earliest current hub reservation expiry that permits the next scheduler candidate, not this caller's whole message or recipient arrival.
- `valid_ms` is 0–60000. The phone measures elapsed time since reception. After expiry, remove the countdown. Unknown version, duplicate/missing keys, oversize body, overflow, invalid enum or contradictory values makes the response unusable. Ignore extra fields only if total bounds still hold.
- Recompute the eligibility boundary by scanning current rolling reservations until enough cost expires. `DutyCycle` needs a read-only snapshot/next-eligible method. No `record()` call from a GET. Preserve conservative charges for uncertain writes and existing persisted reservations.
- If the hub's configured modem does not have verified hardware provenance, label the number as a configured estimate. Do not use it for an exact legal guarantee. Existing default LongFast is not evidence about an attached radio.
- Both HTTP and Bluetooth bridge dispatchers need the same optional response. Unsupported old hubs return absent/404/unsupported: no repeated retry loop, show “The hub's wait is unknown.”

No body references, recipient codes, phone addresses, queue owners or person counts leave the hub. Aggregate activity still reveals use; restrict this to the existing local pinned connection and do not expose it on a public endpoint. TLS pins authenticate the hub server, not the truth of its counters. The hub may omit or lie. Nothing in this response changes Delivered, ciphertext retention, retry cooldown or permissions.

**Storage and wire:** message byte layouts remain those in section 4, with zero new fields. No History schema migration. Estimate snapshots live in memory and are reconstructed from durable outbox state after restart. Do not persist utilization histories or per-person scores. Existing hub reservation journal remains authoritative; no new journal version is needed for a derived status view.

### 6.7 Threat model and failure cases

| Actor / failure | Boundary and behavior |
|---|---|
| Outsider listening | Can already observe packet timing/length. Meter sends no new RF probes and reveals no plaintext. Shared chat is still channel-readable; the meter does not make it private. |
| Malicious or curious hub | Sees ciphertext sizes and local connection activity as before. Cannot decrypt private mail or forge a recipient receipt just by reporting airtime. Can lie about congestion; cap/expire its response and never let it block a different authorized route permanently. |
| Lost unlocked phone | Can expose local drafts, progress and message history under existing protection. No new long-lived activity history. Meter does not solve account recovery or backup. |
| Contact sending repeatedly | Can consume traffic and receipts under existing bounds. Meter adds no auto-reply or immediate receipt loop, and does not reward “urgent” labels with extra budget. |
| No radio / unset region | Keep message durable and show the existing wait/setup path. No countdown. A hub route is judged by its own facts, not by an unrelated phone radio. |
| GATT write refused / ambiguous | Retain outbox state. Do not advance accepted marks on a synchronous return. Keep one flight and existing timeout/reconnect behavior. Never bulk-submit to make the progress bar move. |
| Duty pause | Keep the message and accepted progress. Show clock + pause + reason. No Try again loop while a known bound remains. Retrying uses the existing durable send path, not a meter timer. |
| Busy channel without duty proof | Show nearby-use advisory only from fresh observations. Never call a generic delay a legal limit. |
| Radio accepted all, no receipt | All-with-radio marks may be full; Delivered ticks are not. Show waiting for their phone. Never complete delivery on time elapsed. |
| Picture partially received | Preserve confirmed piece count, including HEAD. Loss/retry never earns a new delivery tick for the same piece. Recipient progress may be delayed by batched receipts. |
| Hub unavailable / old hub | Existing route selection and retry policy continue. Status absence does not count as message failure. Drop stale hub estimates when the route changes. |
| App restart / settings changed | Rebuild from rows, clear ephemeral estimates, read current settings. No old-radio remaining allowance follows the person. |
| Clock change / pause reaches zero | Use monotonic receipt age on the phone. Recheck hub ledger and real sending eligibility; never credit a send or reset budget merely from wall-clock movement. |

## 7. Compatibility and migration

v0.1.0 phones receive exactly the same bytes, receipts and pictures. New phones can show local estimates with an old hub; only its wait detail is missing. New hubs retain the old endpoints and respond normally to old phones. No database downgrade or migration is required for AM. No change to primary channel, private ports 300–303, P-256, ratchet policy, picture limits or retry schedules.

The accounts decisions and Later list were read (`docs/specs/2026-10-14-accounts-design.md:14-74,433-443`). Quantum-safe keys stay shelved. Recovery, backup, private groups and profiles keep their own decisions. For future group messages, the estimator must accept a list of actual envelopes; it must not assume one encrypted group message is already implemented.

A number changing from today's picture helper is a model correction, not evidence that transmission became faster. Explain this in release notes. Do not change historical message timestamps or statuses to fit the meter.

## 8. Verification and delivery slices

### Acceptance examples

- A fitting French 40 B private text shows about 1–2 seconds on known LongFast, depending on fitting envelope extras. Send behavior and wire bytes are unchanged.
- A target-size Medium picture shows about 7–8 seconds initial TX; Maximum shows about 34–35 seconds under the stated sketch assumption. Actual encoded bytes replace these examples.
- An oversized draft shows no misleading send timer and cannot send. Editing back to fit restores the estimate.
- During a four-send picture, two radio acceptances show 2 of 4 with radio, not 2 of 4 received. A timer alone cannot change either count.
- A confirmed local duty refusal shows a reason with unknown wait duration. A valid hub duty snapshot can show the next-try bound, never arrival time.
- Wi-Fi upload earns only hub custody. It uses no LoRa on the phone-to-hub leg, while the hub's later airing still counts.
- An unknown/custom preset, stale metric or old hub stays unknown. There is no hidden LongFast fallback in the new UI.

### Tests, JVM first

1. **Pure-core JVM:** golden airtime vectors for every table row and all seven presets; zero/small/max payload boundaries; 197/198/199-byte picture splits; 25/32-byte sketches; keyed and piggyback fit; UTF-8 French, emoji and canonical language tags; replies; maximum-name shared posts; exact equality at low-data-rate threshold. Verify integer ceilings and no rounding before capacity calculations.
2. **Pure estimate seam:** non-mutating draft calls leave ratchet/session/outbox unchanged. Repeated edits do not consume keys. Invalid/empty/too-long inputs hide seconds. Actual sealed rows replace estimates. Modem/route changes invalidate them. Count HEAD, held work and superseded rows correctly.
3. **Queue and delivery:** three-write fake transport refuses the fourth, and AM never increases concurrency. Exercise `mailFlight`, `sendQueued`, radio acceptance, lost queue report, disconnect, duplicate report, flow control, retries and late receipt. Wall time cannot complete progress. Stale events from old routes cannot advance the current route.
4. **Hub JVM:** read-only snapshot, empty/full budget, exact expiry boundary, rolling rather than clock-hour reset, heterogeneous packet costs, existing reservations across restart, uncertain writes, malformed values and many phone queues. `/airtime` cannot charge or dequeue anything; polling failures cannot break `/send` or `/mail`. Old/new endpoint compatibility in HTTP and Bluetooth bridge dispatch.
5. **Telemetry JVM:** only own-radio provenance; finite/range checks, missing versus zero, timestamp expiry, custom settings, firmware duty error versus queue busy, reconnect/config invalidation. A TX percentage does not yield a next-send timestamp.
6. **Copy JVM:** every EN/FR sentence and plural/interpolation sample passes actual `CopyRules`; banned vocabulary is absent. Snapshot only relevant identifiers, no real device identifiers.
7. **Emulator, after owner authorization:** light/dark, 320 dp width, 200% text, keyboard, rotation, region card overlap, accessibility focus and contrast, reduced motion, reading order and microphone announcement deferral. Capture all six mockup states plus empty, unknown preset, no radio, retries, receipt wait and old-hub fallback. No recording or playback without consent.
8. **Demo:** extend `src/demo` fake observations, not a second meter implementation. Exercise real AppCore/Postman/Hub with seeded 10/20 queues, explicit duty refusal, stale readings and old hub. Demo timings are marked simulated. Keep `src/main` free of demo imports; cover both ABIs in the existing release checks. The demo spec requires every release to ship it (`docs/specs/2026-10-25-demo-build-design.md:19-40`).
9. **Later authorized hardware gate:** compare predicted initial TX against firmware airtime observations for known presets, route and packet sizes. Do not deliberately exhaust a public mesh or change radio settings without consent. Record acceptable error before calibration, retain negative results, and qualify each firmware family. This draft supplies no field ETA accuracy claim.

### Slices and effort

Ranges are **INFERRED engineering estimates**, not measured project velocity. Heavy checks wait for explicit permission and the other agent's build window.

| Slice | Likely files / seam | Acceptance | Active effort |
|---|---|---|---|
| 1. Owner review + model | Core LoraAirtime, new estimator tests; golden vectors | AM1/AM2/AM3 picks; golden lengths and no state mutation | 0.5–1 day |
| 2. Draft + picture + bubbles | AppCore, ConversationActivity, PictureActivity, resources; AppMailStore projection | Existing sends unchanged; separate acceptance/delivery progress; reviewed screenshots | 1–2 days |
| 3. Passive radio facts | MeshRadio, parser, BleMeshTransport, fake radio | Typed reasons; stale/unknown behavior; no probes or queue increase | 0.5–1 day |
| 4. Optional hub status | DutyCycle snapshot, Hub, HubServer, Bluetooth bridge, client parsing | Compatible read-only response; no identifiers or ledger mutations | 0.5–1.5 days |
| 5. Qualification | Core/hub/app tests, demo, sizes, copy, accessibility | Focused tests then existing release gates; owner review before hardware | 1–2 days plus access waits |

Suggested future commands from the source checkout's `android/`, only when authorized: `./gradlew --no-daemon --console plain :core:test :hub:test`; then focused app/demo tests and the repository's existing check/screenshot gates. No such build or product test was run for this draft. APK bytes and field comprehension remain unverified until implementation and review.

### Draft-only verification record

Python arithmetic was executed for 16 event rows across seven presets, with integer capacity calculations at both duty scenarios. Copy, document structure and HTML checks are recorded after artifact validation below. No JVM, emulator, hardware or RF claim follows from a browser mockup.

**VERIFIED, draft artifacts only:** 86 EN/FR spec-copy sentences passed a Python mirror of CopyRules, including numeric substitutions. The final HTML passed 176 static copy sentences and 8 dynamic picture-time variants in Chromium. All six screens rendered at a 2000 px desktop viewport in EN and FR without internal clipping; the 390 px layout kept horizontal scrolling inside the comparison gallery. Language switching, all four quality choices, reset, modal opening/Escape, keyboard focus and reduced-motion preference were exercised. Four screenshots were captured and the EN/FR desktop and French mobile picture views were visually inspected. There were no JavaScript page errors and no external asset requests. Citation verification passed for all four opened web sources with attached source quotations.

**Tool issue and recovery:** the managed browser refused the host's non-Chromium default profile. An isolated headless installed Chrome, driven by an already cached Playwright package, exercised the real local HTML instead. No browser setting or personal profile was changed. Early French card clipping was corrected and the final checks rerun. Scratch receipt: `v011-AM/mockup-check.json`. These checks are not Kotlin/JVM execution, Android accessibility qualification, translation review or field testing. The owner still needs to review the mockup before any port.

## 9. Decisions for the owner

### Blocking batch

**AM1. What does the headline mean?**
- **A (recommended):** initial radio time, with wait shown separately. Honest with current evidence; no promised arrival countdown.
- **B:** only “short / long / unknown”. Easier to read, but less useful for comparing picture choices.
- **C:** predicted arrival range. Requires field calibration and still has no bound for an offline recipient; defer release scope.

**AM2. Where does it appear?**
- **A (recommended):** compact draft row, picture preview, outgoing bubble, optional detail panel. Visible when useful; no permanent dashboard.
- **B:** picture preview and Radio screen only. Lower UI cost, but misses ordinary message waits.
- **C:** always-visible meter above Talk. More prominent, but crowds the small-screen composer and region help.

**AM3. Which calculation should replace the picture helper?**
- **A (recommended):** route-specific broadcast model with actual envelopes, separate receipt costs and explicit unknown settings. Corrects the legacy mismatch without changing packets.
- **B:** retain today's conservative helper, labelled as such. Smaller patch, but misleading ACK-per-part assumptions remain.

**AM4. How much wait information ships?**
- **A (recommended):** passive local metrics plus optional pinned hub `/airtime`; unknown on old hubs. Useful reasons without extra LoRa traffic.
- **B:** passive local metrics only; hub wait stays unknown. Smaller compatible release.
- **C:** local estimates only. Cheapest; generic waiting cannot name a confirmed duty cause.

**AM5. Picture-first explanation and accessibility?**
- **A (recommended):** tiles + labelled counts + short time; Listen on demand; still equivalent; no mic-time announcements. Comprehensible without relying only on colour or digits.
- **B:** plain text and numbers only. Less drawing/code, but excludes more people.
- **C:** ticks without time/count labels. Compact, but risks confusing accepted with delivered; not advised.

**AM6. Does the meter enforce new quotas?**
- **A (recommended):** no. Keep firmware/hub enforcement and existing queue semantics. Observations are advisory; partial phone knowledge cannot enforce a shared legal budget.
- **B:** add a phone-side pacing quota. Separate scheduler design and measured fairness proof required; do not fold it into this UI work.

**AM7. App-size allowance?**
- **A (recommended):** authorize measuring option B within a 24,576 B per-ABI planning reserve; approve the final measured ceiling change before release. No libraries, bundled audio or bitmaps.
- **B:** fit the current ceiling by shipping option A or removing optional detail. Do not assume the reported 1.6 KB headroom can hold B.

### Deferred, not release blockers

**AM8. Calibrated elapsed-time forecasts later?**
- **A (recommended):** collect consented local calibration during a field test, with no background upload; decide after comparing error by route and firmware.
- **B:** never add forecasts. Keep useful airtime and wait facts only.

**AM9. Personal fairness later?**
- **A (recommended):** defer quotas and sender ranking; evaluate existing per-phone round-robin first. Avoid new identity/activity metadata.
- **B:** commission a separate fairness/scheduling spec. It must account for shared addresses and relays, not just account counts.

## 10. Sources and provenance

Code citations in section 2 identify the inspected implementation. The brief and the accounts decisions govern scope. Existing UI labels were checked in `android/app/src/main/res/values/strings.xml:20,53,62-66,83,278-310,464` and `android/app/src/main/res/values-fr/strings.xml:274-279,305-308`. `android/core/src/main/kotlin/org/phonemesh/core/CopyRules.kt:3-12` defines the copy test. The binding text/no-audio contract is `docs/design.md:37-56,72-79`, with its account-protocol supersession at line 3.

Web pages below were opened during this draft. Meshtastic is documentation, not independent legal approval. ETSI is the dated primary standard used to explain the sub-band distinction. Togo's current deployment legality was not independently reapproved here.

## Sources

[1] https://meshtastic.org/docs/configuration/radio/lora: LoRa Configuration | Meshtastic
[2] https://meshtastic.org/docs/overview/radio-settings: Radio Settings | Meshtastic
[3] https://meshtastic.org/docs/software/android/user/telemetry-and-sensors: Telemetry & Sensors | Meshtastic
[4] https://www.etsi.org/deliver/etsi_en/300200_300299/30022002/03.02.01_60/en_30022002v030201p.pdf: ETSI EN 300 220-2 V3.2.1
