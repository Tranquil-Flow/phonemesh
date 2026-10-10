# Radio region setup

**Date:** 2026-10-07
**Status:** approved by the owner, 2026-10-07 (unlisted countries: helper path N2)
**Read with:** `docs/specs/2026-10-07-visual-guide-design.md` (setup step 2, problem cards, how-to pictures) and `docs/android-design.md`
**Changes an earlier rule:** `docs/android-design.md` says Phonemesh never changes a radio's region and that the stock Meshtastic app sets it first. From this spec on, Phonemesh may change the region, and only the region, after a tap from the person. Channels, keys, power and every other setting stay untouched.

## Goal

An ordinary person can set up a new radio without the Meshtastic app. Phonemesh sees that the radio has no region, guesses the country, asks with a flag picture, and sets the region the country's rules allow.

## Background

- A new or factory-reset Meshtastic radio has region `UNSET` and does not transmit until a region is set.
- A radio stores a region (for example `EU_868`), not a country. Many countries share one region.
- Phonemesh already speaks the Meshtastic radio protocol directly over Bluetooth (`android/radio-ble`). It already receives the radio's LoRa settings during the connection handshake and keeps the hop limit from them. The bundled protobufs (`admin.proto`, `config.proto`) already hold everything needed to change the region.
- The owner's test radios are two Heltec WiFi LoRa 32 V4, set for Germany (`EU_868`).

## Decisions (owner, 2026-10-07)

1. Guess the country, then confirm with one tap. Never change the region silently.
2. If the radio already has a region that does not match, offer to change it. "Keep" is remembered per radio.
3. The country list holds only countries with a checked source. Adding a country is one line.
4. Channels are out of scope. Group chat stays on the default public channel. Private groups (join by QR code) get their own spec later.
5. Test on both the Pixel 8 Pro and the Fairphone 3.
6. For a country not in the list, a helper may choose the region inside Phonemesh after confirming they checked that country's rules (how-to option N2). Phonemesh never picks a region for an unlisted country by itself.

## What the person sees

The region check runs every time a radio connects. During setup it is the fourth part of step 2, after "Find radio" connects. After setup it shows as a problem card on the chats screen.

| Radio region | Guessed country | Card |
|---|---|---|
| matches the country's region | supported | none, radio ready |
| `UNSET` | supported | **Set up:** the country's flag appears on the radio's screen, then the light turns green and rings spread (how-to option R2). "Set up this radio for Togo?" Big **Yes**, small **Other country**. |
| a different region | supported, no earlier Keep | **Change:** the radio's screen shows the flag it is set for, which turns into the new country's flag. "This radio is set for the USA. Change it to Togo?" **Change** / **Keep**. See "Which flag a radio shows". |
| a different region | supported, Keep chosen for this radio and country | none |
| `UNSET` | unknown (no SIM, no network) | **Country list** opens straight away. |
| `UNSET` | not in the list | **Ask a helper:** the radio's screen shows the country's flag with a "?", and its waves are crossed out. "Phonemesh does not know the radio rules for Benin yet. Ask someone who knows them." Small **For helpers** button. Phonemesh sends nothing until a helper chooses. See "For helpers". |
| set | unknown or not in the list | none. Phonemesh cannot judge the region, so it leaves a working radio alone. |

- **Other country** opens the list of supported countries. Its last row is **My country is not here**, which leads to the Ask a helper card (with a globe instead of a flag when no country is known).
- **Other country** list rows: each row has the flag, the country name in the app's language and a speaker button. Choosing a country re-runs the table above with that country.
- After **Yes** or **Change**: a "Setting up…" picture (the radio light goes off and on), then a green check and "Radio ready for Togo."
- While the radio has region `UNSET`, the send button shows the Set up card instead of sending. The draft is kept.
- Every card has one short sentence that the speaker button reads aloud, and a moving picture in the how-to style (wax-print colours, drawn finger, loops while visible, still frame when animations are off).

## For helpers

The **For helpers** button opens one screen:

- Title "Choose the radio region". One sentence: "Only choose if you know this country's radio rules."
- A list of every region in the bundled proto except `UNSET`, `UNRECOGNIZED` and the seven licensed amateur bands (`ITU1_2M`, `ITU2_2M`, `ITU3_2M`, `ITU1_70CM`, `ITU2_70CM`, `ITU3_70CM`, `ITU2_125CM`), each with its flag from the region block below and its name with the underscore as a space ("EU 868").
- A tick box: "I checked the radio rules for Benin." **Set** stays disabled until a region is chosen and the box is ticked.
- **Set** runs the same write and the same "Setting up…" and "Radio ready" pictures as **Yes**. The ready sentence names the region, not the country: "Radio set to EU 868."
- Settings records `radioCountry` for the radio as usual, so later cards show Benin's flag, and records `helperSet: Set<radioAddress>`. A helper-set radio never gets a Change card from a guess; it only changes through **For helpers** again (reachable from the radio's row in Settings).
- The log records the region, the country and that a helper chose it. The tick is not proof; it moves the choice to someone who can judge it.

## Which flag a radio shows

A radio stores a region, not a country. To show the flag the radio was set for, Phonemesh uses the first of these that exists:

1. The country this phone set the radio up for. Settings keeps `radioCountry: Map<radioAddress, country>` after every successful set.
2. The region's own country, when the region belongs to one country (`US` → United States, `JP` → Japan).
3. The shared flag of the region (`EU_868`, `EU_433` → the European flag).
4. A plain globe, with the region name under it, for regions with neither.

The country is named in the sentence for cases 1 and 2 ("set for the USA"), the area for case 3 ("set for Europe"), and "another part of the world" for case 4.

## Country list

One file, `core/src/main/resources/countries.txt`. One line per country, fields separated by ` | `:

```
# code | region | flag | source
DE | EU_868 | h:000000,dd0000,ffce00 | Bundesnetzagentur Vfg. 91/2025 (November 2025), Tabelle 2, Band Nr. 54: 869.4-869.65 MHz, 500 mW ERP, frequency access and interference mitigation techniques [7] or duty cycle <= 10 %
TG | EU_868 | h:006a4e,ffce00,006a4e,ffce00,006a4e; canton:d21034; star:ffffff | ARCEP Togo Décision N°226/ARCEP/DG/22 (30 Dec 2022), Annex 2: 869.4-869.65 MHz, 500 mW ERP, 10 % duty cycle
```

- **code:** ISO 3166-1 alpha-2. The display name comes from `java.util.Locale`, so English and French need no new strings.
- **region:** a `Config.LoRaConfig.RegionCode` name. A test checks that it exists in the bundled proto.
- **flag:** a tiny drawing description, drawn on Canvas, no image files. Version 1 supports `h:` horizontal stripes and `v:` vertical stripes (colours as hex, equal widths), `canton:` a top-left square in one colour, and `star:` a five-pointed star, in the canton if there is one, else centred. Flags that need more shapes extend this format when their country is added.
- **source:** required. A test fails if it is empty. It names the regulator's document that allows the band.

A second block in the same file gives each Meshtastic region its flag for the Change card. These lines only choose a picture, so they need no legal source:

```
# region | flag-of | name
US | country:US | 
EU_868 | flag:v:003399; stars:12:ffcc00 | Europe
EU_433 | flag:v:003399; stars:12:ffcc00 | Europe
```

`country:XX` reuses a country's flag and name (the country does not need to be in the supported list). `flag:` draws one directly; version 1 adds `stars:N:colour`, a ring of N stars, for the European flag. Regions with no line get the globe.

Germany's line carries its exact Bundesnetzagentur reference, copied from the published text. The Togo line comes from the ARCEP decision, read on 2026-10-07.

## Architecture

Three parts, each with one job.

### `core`: `CountryRegions` and `RegionCheck` (pure Kotlin)

- `CountryRegions.load(text)` parses the file and rejects bad lines with the line number.
- `RegionCheck.decide(radioRegion: String?, country: String?, kept: Set<String>, helperSet: Boolean) -> RegionStep` returns one of `Ready`, `AskSet(country)`, `AskChange(country, radioRegion)`, `ChooseCountry`, `AskHelper(country: String?)` (null when the person chose "My country is not here" with no known country).
- `kept` holds the countries this radio's owner chose Keep for. `helperSet` true always gives `Ready` for a set region.

### `radio-ble`: read and set the region

- `MeshtasticProtocol.parseFromRadio` keeps the whole `LoRaConfig` from the handshake (a `LoraSettings` event), not only the hop limit.
- `MeshRadio` gains `region(): String?` and `setRegion(region: String, done: (RegionResult) -> Unit)`.
- `setRegion`:
  1. Sends `AdminMessage.get_config_request(LORA_CONFIG)` to the local node on `ADMIN_APP` and waits up to 10 s for the reply. If there is no reply, it uses the settings from the handshake. If it has neither, it returns `NoSettings` and writes nothing.
  2. Copies that `LoRaConfig`, changes only `region` and sends `set_config` wrapped in `begin_edit_settings` / `commit_edit_settings`. If the reply carried a `session_passkey`, it is included.
  3. Expects the link to drop while the radio restarts. The caller reconnects to the same address.
- `RegionResult`: `Sent`, `Dropped` (link lost before the write was sent), `NoSettings`.

### `app`: `RegionSetup`

- **Country guess:** `TelephonyManager.networkCountryIso`, else `simCountryIso`. Neither needs a permission.
- **The flow after a write:** reconnect, wait up to 60 s for the handshake, read the region again. Only a matching read counts as success. If the region did not change, it retries the write once.
- **Cards:** shows the cards and plays the pictures (`HowToView` scenes from the visual guide).
- **Settings:** `keptRegions: Map<radioAddress, Set<country>>`, `radioCountry: Map<radioAddress, country>`, `helperSet: Set<radioAddress>`.
- **Helper screen:** region list from the proto, tick box, **Set**.

## When something fails

| What happens | Card |
|---|---|
| No reconnect within 60 s | "The radio did not come back. Turn it off and on again." Step 2 "turn on" picture, **Try again**. Reconnect attempts continue in the background. |
| Reconnects, region unchanged after one retry | "The radio did not take the setting. Ask a helper." Helper picture. Detail goes to the log. |
| `Dropped` | **Try again**. The radio was not changed. |
| `NoSettings` | **Try again**. Nothing was written. |
| Any of the four cards above | **Back** returns to the question (Yes / Other country, Change / Keep, or For helpers). It writes nothing and stops the reconnect attempts. The "did not take the setting" card also has **For helpers**. |
| Person leaves the screen during setup | Setup carries on. The result shows when they return, or as a problem card on the chats screen. |
| Country line found wrong later | Fixed in the file and shipped in an app update. Radios already set get the Change card on their next connection. |

## Legal notes (Togo)

These come from ARCEP Décision N°226/ARCEP/DG/22. They are outside what Phonemesh controls, so they are recorded here and in `docs/android-verification.md`, not built:

- Short-range devices are licence-exempt but must be type-approved (Art. 5). Whether Heltec radios are approved in Togo is not known.
- Power amplifiers are forbidden (Art. 6).
- 500 mW is ERP, so it includes antenna gain. Meshtastic's 27 dBm `EU_868` limit is conducted power. A high-gain antenna at full power can exceed the limit. Phonemesh leaves `tx_power` alone. A later spec may cap it per country.
- There is no 915 MHz band in Togo.

The decision was read through OCR of a scanned PDF. The rows used here are clear and match CEPT ERC Rec 70-03.

## Size

The added code, the country file and the flag drawing come to a few KB. No new dependencies. Size is measured before and after, as in the visual guide.

## Testing

1. **Unit tests (Mac):**
   - **Country file:** every line parses; every region exists in the proto; every source is non-empty; no duplicate codes; `TG` and `DE` are `EU_868`.
   - **`RegionCheck`:** every combination in the "What the person sees" table.
   - **`setRegion`:** builds a full `LoRaConfig` with every field set, runs the command and checks that the sent `set_config` equals it field by field except `region`; checks the edit wrapper and the passkey.
   - **`RegionSetup` with a fake radio:** comes back, never comes back, comes back unchanged, drops before the write, no settings. Each case ends on its card.
2. **Emulator (fake radio):**
   - Each card appears at the right time.
   - The country list works.
   - Keep is remembered.
   - For helpers: **Set** stays disabled until a region and the tick are chosen; a helper-set radio gets no Change card later.
   - Sending is blocked while the region is `UNSET`.
   - Every new screen passes the existing checks (touch size, content descriptions, wording) in light and dark.
   - Screenshots of each card.
3. **Real radios, on both the Pixel 8 Pro and the Fairphone 3:**
   - The owner does every tap that changes a radio. The owner-approved build is installed, and logs are read or read-only screenshots taken. On the Pixel that means install and screenshots only.
   - FLAG_SECURE blacks out screenshots, so the owner photographs the cards or approves a test build without it.
   - **New radio:** the owner sets radio A to `UNSET` in the Meshtastic app. In Phonemesh: "Set up this radio for Germany?" → Yes → restart → "Radio ready". Then a message from A to B proves it transmits. Run once per phone, swapping which radio is reset.
   - **Different region:** a test build fakes the country as `US`. The Change card appears. The owner taps **Keep** only, because Change would set `US`, which is not legal in Germany. The Change write is the same path as the new-radio test.
   - **Helper path:** a test build fakes the country as `BJ`. The Ask a helper card appears; the owner opens For helpers, picks `EU_868` (legal in Germany), ticks and taps **Set**. Then a message from A to B. One phone is enough; the write is the same path as the new-radio test.
   - **Pairing:** record whether the V4 asks for a pairing code and where it shows. This settles the "type the number" how-to picture.

## Done when

- A radio with no region is set up from Phonemesh with one tap on both phones, and it then sends a message to the other radio.
- The Change card appears for a mismatched region, and Keep is remembered.
- A helper can set a radio for an unlisted country, only after the tick.
- No card, test or code path changes anything but the region.
- Unit, emulator and size checks pass, and the owner has seen each card.

## Non-goals

- Channels, keys, private groups, `tx_power`, modem presets, firmware updates.
- Checking rules for unlisted countries. A helper-chosen region is the helper's call, and Phonemesh does not add that country to the list.
- Changing a radio over the mesh (remote admin). Only the radio connected by Bluetooth is touched.
