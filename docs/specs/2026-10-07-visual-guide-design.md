# Sub-project 3a-2: visual guide

**Date:** 2026-10-07
**Status:** design agreed with the project owner from mockups; awaiting written-spec review
**Read with:** `docs/specs/2026-10-06-ui-core-loop-design.md` (3a) and `docs/specs/2026-10-06-phase3-decisions.md`
**Follows:** 3a (core loop). The owner tried 3a on the Pixel and found it "not nearly visual enough". This spec reworks the first launch, the setup on the chats screen and the settings screen, and fixes the defects found in that session. It pulls two items forward from 3b: the dark theme with Auto, and the microphone hint.

## Goal

A person who cannot read well understands what the app does, and sets it up, from pictures. Every step is numbered and drawn. Nothing on screen looks like something it is not.

## Owner feedback this answers

1. The language screen is boring; the globe says nothing.
2. The speaker icons are small, and tapping one picked the language and left the screen.
3. The settings icon looks like a light/dark switch. A light/dark option is wanted, but the settings icon must be a gear.
4. Settings gives no way to tell "Reconnaissance vocale (Tiny)" from "(Base)".
5. Settings has no back arrow.
6. The app is not visual enough: wanted pictures and "Step 1", "Step 2".

Root causes found in the code: `ic_gear.xml` draws a sun (a circle with eight rays); on the language screen the speaker is a decoration inside the language button, so any tap calls `choose()`; the radio's "not connected" icon `ic_radio_off.xml` is a plain diamond; there is no `values-night` theme and no theme setting.

## Screens

The owner chose each design from mockups (artifact "Phonemesh Visual Guide Options", rounds 1 to 5).

### Welcome and language (A1-c, style S3 "wax-print")

Top to bottom:

- **"Phonemesh"** and one line: "Parler · envoyer par radio · entendre la réponse" / "Speak · send by radio · hear the reply".
- **The story picture**, an animated drawing about 16:9, full width, rounded corners.
- **The caption row:** a numbered circle, one short sentence, and a speaker button that reads the sentence aloud. Five dots under it mark the five moments.
- **One row per language:** a large yellow speaker button (at least 64 dp square) that only plays the language's name, and beside it the language name with a chevron, which chooses it. The two are separate touch targets with a gap between them.

**The story** loops every 10 seconds. One timeline drives every part:

| Time | Moment | What moves | Caption (EN) |
|---|---|---|---|
| 0–0.6 s | opening | whole scene in view | "No internet. No phone signal. Only radio." (crossed-out signal bars icon) |
| 0.6–3 s | 1 Speak | camera moves in on her; she raises the phone; her mouth opens and closes in syllables; sound bars come from her mouth | "She speaks into her phone" |
| 3–4.6 s | 2 Text | the sound bars fade as "Bonjour" types out letter by letter in a bubble | "Her voice becomes text" |
| 4.6–6.2 s | 3 Radio | the bubble shrinks into her radio; her radio's light blinks and rings spread out from its antenna; the bubble travels along a dashed arc; rings close in on his radio and its light blinks | "The radio sends the text far away" |
| 6.2–7.2 s | 4 Text | camera moves to him; the bubble rises to his phone, which he holds to his ear | "His phone turns the text back into voice" |
| 7.2–9.5 s | 5 Listen | the letters disappear as sound waves come from his phone | "He hears her message" |
| 9.5–10 s | closing | camera pulls back out | opening caption |

The scene, in the wax-print style: a warm sky fading from orange to pale yellow, a sun drawn as three rings (yellow, bright yellow, orange) high in the middle, two green hills, two small houses with brown roofs, terracotta ground with a zigzag band (dark brown, yellow and red triangles) along the bottom edge. Outlines are dark brown rather than black. She wears an indigo dress printed with orange and yellow rings and an orange-red headwrap; he wears a kente-striped shirt (yellow, green, red and dark brown) with a yellow collar and a dark cap. Each holds the phone with a drawn arm. The radios are amber with a small screen, a knob and a status light that blinks bright green. There are no palm trees, no phone mast and no distance marker. Nobody blinks.

The rings are small: at most about a third of the radio-to-radio distance.

**Caption language.** The screen comes before the language choice, so captions follow the phone's language: French if the phone's language is French, otherwise English. Once a language is chosen (or when this screen is opened from Settings), the chosen language is used. Each caption has a short recorded clip per language, built into the APK like the language-name clips (six captions × two languages, about 70 KB in total). The speaker button plays the clip for the caption on screen.

**Reduced motion.** When the system animator scale is 0 (Android's "Remove animations"), the picture shows one still frame with the bubble on the arc between the radios, and the caption row shows "3 · The radio sends the text far away". The rest of the screen is unchanged.

**Battery.** The animation runs only while the screen is visible (started in `onStart`, stopped in `onStop`).

The same screen opens from Settings → Language, with the chosen language's row marked.

### Chats: guided setup (M1)

Setup becomes three numbered steps:

1. **Download the voice:** the current voice card's content (size, Download / File, progress).
2. **Connect your radio:** turn the radio on, keep it close, tap "Find radio".
3. **Say hello (new):** hold the microphone and say something. Tapping it opens the group chat; the microphone there shows a one-time hint ("Hold to talk") pointing at it.

Layout while setup is not finished:

- **The current step**, the first step not yet done, sits large at the top in a card: a numbered circle, "STEP 2 OF 3" above the step title, a progress bar for steps done, a drawing for the step, one sentence, and the step's main button.
- **The other steps** are slim rows under the card: done steps show a green circle with ✓, a title and a one-line detail (e.g. "French · 66 MB"); later steps show a grey numbered circle, a title and a one-line hint.
- **The chats list** follows, as now.

**When a step counts as done.** Steps 1 and 2 use the existing live checks (voice ready, radio connected). Step 3 is done once the user has sent one message; this is stored, so it never comes back. When all three are done at the same time, the card shows the existing green "done" state for two seconds and the whole guide folds away.

**After setup.** Once the guide has folded away, it never returns; a stored flag records this. If the voice or the radio is lost later, a single slim card for that problem appears on its own, without step numbers, using the same drawing as its step, as the 3a cards do today.

**Step drawings** reuse the story's phone, radio and bubble so people recognise them: a phone with a download arrow (step 1), a phone, a dashed arrow and a radio with a green "ON" light (step 2), and a hand on a microphone with "Bonjour" (step 3).

**The top bar:** the radio status shape becomes a small radio drawing with a status dot (grey = not connected, green = connected), keeping the shape-plus-colour rule. The right-hand icon is a real gear.

### Settings

- **Top bar** with a back arrow (at least 48 dp touch target) and the title. The back arrow does the same as the system back.
- **Appearance:** three choices side by side, each with an icon: Auto (half-filled circle), Light (sun), Dark (moon). Auto follows the phone. The choice applies at once to every screen.
- **Understanding speech:** two cards instead of the Tiny/Base radio buttons and pack rows:
  - **Fast** (Whisper Tiny): speed 3 of 3, accuracy 2 of 3, "Best for older phones."
  - **Accurate** (Whisper Base): speed 1 of 3, accuracy 3 of 3, "Fewer mistakes. Slower and bigger download."
  - Each card shows its download size from the pack catalog and its state: in use (✓), installed ("Use"), or not installed ("Download · N MB"). A card that is installed and not in use also offers "Remove".
  - The voice packs stay listed under their language name, with Download or Remove, as now.
- **Language**, **Share the app** and **Licences** stay.

### Back arrows elsewhere

Radio and Conversation get the same back arrow in their top bar. The language screen gets one only when opened from Settings.

## Architecture

All of it stays framework-only (no AndroidX, no Compose) and adds no dependencies.

- **`StoryTimeline`** (pure Kotlin, no Android types): given a time from 0 to 1, returns every value the picture needs (phone positions, mouth opening, bubble position, scale and visible letters, ring radii and opacities, light states, camera rectangle, current moment). It holds all the timing in one place and is unit-tested.
- **`StoryView`** (a `View`): draws the scene on a `Canvas` from `Path`s and `Paint`s built once, reading positions from `StoryTimeline`. A `Choreographer` frame callback advances time while the view is attached and its screen is started. It draws the reduced-motion frame when `ValueAnimator.areAnimatorsEnabled()` is false. Its content description is the opening caption.
- **`StoryCaption`**: the caption row; it listens to `StoryView` for moment changes and plays the matching clip through the existing clip player.
- **`SetupSteps`** (pure Kotlin): from voice ready, radio connected, message sent and guide finished, returns the current step, the done steps and whether to show the guide, a single problem card or nothing. Unit-tested. `ChatsActivity` renders what it returns.
- **`StepCard`** builds the large step card and the slim rows; step drawings are vector drawables.
- **`Settings`** gains `theme` (auto, light or dark; default auto), `firstMessageSent` and `setupFinished`.
- **Theme:** a `values-night/themes.xml` defines the dark colours for every attribute in `attrs.xml`. Light and Dark are forced by setting the night bits of `uiMode` in the configuration context that `ScreenActivity.attachBaseContext` already builds for the language; Auto leaves the phone's setting alone. Changing the theme recreates open screens, as changing the language does.
- **Icons:** new or redrawn vector drawables: `ic_gear` (real gear), `ic_back`, `ic_sun`, `ic_moon`, `ic_auto`, `ic_radio_off` / `ic_radio_on` (radio with dot), `ic_no_signal`, plus the three step drawings.
- **Clips:** six caption clips per language in `res/raw`, made with the same tool as the name clips; a test checks each caption string has its clip for each language.

## Size

Budget: no more than 150 KB added to the APK for all drawings, clips and code together. The size is measured before and after and recorded with the verification results.

## Testing

- Unit tests: `StoryTimeline` (each moment starts and ends at the listed times; the bubble is hidden only outside 2.8–7.6 s; letters never exceed "Bonjour"; ring radii stay within their limit; the camera ends where it starts), `SetupSteps` (every combination of the four inputs, including loss of voice or radio after setup), and the `Settings` additions.
- The existing every-screen checks (touch size, content descriptions, wording) cover the new screens, in light and dark. Touch targets are at least 48 dp; the language speaker buttons at least 64 dp.
- The screenshot build captures: welcome at moments 1, 3 and 5 and in reduced motion; chats at each setup step and after setup with the radio lost; settings in light and dark; and the conversation with the microphone hint.
- On the Pixel 8 Pro (owner-approved installs and screenshots only), the owner checks that tapping a speaker plays the name without leaving the screen, and that the animation runs smoothly. On the Fairphone 3 (adb measurements allowed), `dumpsys gfxinfo` frame statistics for the welcome screen show the animation holds its frame rate; the result is recorded with the size numbers.

## Done when

- Every item in "Owner feedback this answers" is fixed and shows in the screenshots.
- The welcome animation matches the table above in both languages and in reduced motion.
- Setup shows as numbered steps with drawings, folds away when finished and does not come back except as a single problem card.
- Settings has the back arrow, Auto/Light/Dark and the Fast/Accurate cards; dark mode covers every screen.
- All tests pass and the APK size increase is within budget and recorded.

## Non-goals

- The other art styles shown in the mockups (sunny day, sunlight poster, soft flat, night village, chalkboard). The drawing code keeps its colours and patterns in one place so the style can be changed later.
- Long-press to hear any control, "stay listening", and message deletion stay in 3b.
- No animation anywhere other than the welcome picture.
