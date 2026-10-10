# Sub-project 3a-2: visual guide

**Date:** 2026-10-07
**Status:** approved by the project owner (2026-10-07)
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

The owner chose each design from mockups (artifact "Phonemesh Visual Guide Options", rounds 1 to 6).

### Welcome and language (A1-c, style S3 "wax-print")

Top to bottom:

- **"Phonemesh"** and one line: "Parler · envoyer par radio · entendre la réponse" / "Speak · send by radio · hear the reply".
- **The story picture**, an animated drawing about 16:9, full width, rounded corners.
- **The caption row:** a numbered circle, one short sentence, and a speaker button that reads the sentence aloud. Five dots under it mark the five moments.
- **One row per language:** a large yellow speaker button (at least 64 dp square) that only plays the language's name, and beside it the language name with a chevron, which chooses it. The two are separate touch targets with a gap between them.

**The story** has 10 seconds of action. One timeline drives every part; the times below are action time:

| Time | Moment | What moves | Caption (EN) |
|---|---|---|---|
| 0–0.6 s | opening | whole scene in view | "No internet. No phone signal. Only radio." (crossed-out signal bars icon) |
| 0.6–3 s | 1 Speak | camera moves in on her; she raises the phone; her mouth opens and closes in syllables; sound bars come from her mouth | "She speaks into her phone" |
| 3–4.6 s | 2 Text | the sound bars fade as "Bonjour" types out letter by letter in a bubble | "Her voice becomes text" |
| 4.6–6.2 s | 3 Radio | the bubble shrinks into her radio; her radio's light blinks and rings spread out from its antenna; the bubble travels along a dashed arc; rings close in on his radio and its light blinks | "The radio sends the text far away" |
| 6.2–7.2 s | 4 Text | camera moves to him; the bubble rises to his phone, which he holds to his ear | "His phone turns the text back into voice" |
| 7.2–9.5 s | 5 Listen | the bubble fades out by 7.6 s; sound waves come from his phone until 9.5 s | "He hears her message" |
| 9.5–10 s | closing | camera pulls back out | opening caption |

**Pacing (owner request, 2026-10-10).** The action keeps the speed above, but every numbered step stays on screen for 4 seconds so that its caption can be read: the step's action plays, then the picture rests for what is left of the 4 seconds, then the next action begins. The opening caption gets the same 4 seconds: it is shown from the closing (0.5 s) through to the end of the opening (0.6 s), and the picture rests between the two for the 2.9 seconds that are left. The loop on screen therefore lasts 24 seconds, six captions of 4 seconds each. The rest comes first in the loop, so the first launch also starts with it: the whole scene rests for 2.9 seconds, the opening plays, and step 1 begins at 3.5 seconds. 4 seconds is one constant (`StoryTimeline.STEP_MS`).

| Step | Action before the rest | Rest | Action after the rest | The picture it rests on (action time) |
|---|---|---|---|---|
| opening caption | 0.5 s (the closing) | 2.9 s | 0.6 s (the opening) | 0 s: the whole scene in view, both phones lowered, nothing lit; the closing ends on the same picture |
| 1 Speak | 2.2 s | 1.6 s | 0.2 s | 2.8 s: phone raised, she is speaking, sound bars full, just before they fade and the typing starts |
| 2 Text | 0.7 s | 2.4 s | 0.9 s | 3.7 s: "Bonjour" complete in its bubble, before the bubble leaves for the radio (the camera's pull-back begins at 3.6 s and has moved 2 % of its way) |
| 3 Radio | 1.4 s | 2.4 s | 0.2 s | 6.0 s: both radios in view, the bubble at his radio, rings closing in, both lights blinking, just before the camera leaves for him |
| 4 Text | 0.8 s | 3.0 s | 0.2 s | 7.0 s: camera on him, "Bonjour" at his raised phone, just before the letters go |
| 5 Listen | 1.7 s | 1.7 s | 0.6 s | 8.9 s: sound waves at full strength, just before they fade and the camera pulls back |

A resting picture is not frozen. It breathes: a raised phone lifts by at most 1.5 scene units and the bubble by at most 2 and come back; her open mouth, the sound bars and his sound waves ease off by at most 30 % and come back; the radio rings draw in by at most 10 % and come back; a radio light that is on keeps blinking. One breath lasts 1.2 to 1.7 seconds, and each rest holds one or two whole breaths, so the picture is exactly the held one when the rest starts and when it ends. Under the opening caption nothing is raised and nothing is lit, so there the two lowered phones, with the hands that hold them, lift by at most 1.5 scene units and come back, twice; no light, ring, bubble or sound mark is shown. Nothing else moves, and the caption does not change during a rest. The reduced-motion frame is unchanged.

The scene, in the wax-print style: a warm sky fading from orange to pale yellow, a sun drawn as three rings (yellow, bright yellow, orange) high in the middle, two green hills, two small houses with brown roofs, terracotta ground with a zigzag band (dark brown, yellow and red triangles) along the bottom edge. Outlines are dark brown rather than black. She wears an indigo dress printed with orange and yellow rings and an orange-red headwrap; he wears a kente-striped shirt (yellow, green, red and dark brown) with a yellow collar and a dark cap. Each holds the phone with a drawn arm. The radios are amber with a small screen, a knob and a status light that blinks bright green. There are no palm trees, no phone mast and no distance marker. Nobody blinks.

The rings are small: at most about a third of the radio-to-radio distance.

**Caption language.** The screen comes before the language choice, so captions follow the phone's language: French if the phone's language is French, otherwise English. Once a language is chosen (or when this screen is opened from Settings), the chosen language is used. Each caption has a short recorded clip per language, built into the APK like the language-name clips (six captions × two languages, about 70 KB in total). The speaker button plays the clip for the caption on screen.

**Reduced motion.** When the system animator scale is 0 (Android's "Remove animations"), the picture shows one still frame with the bubble on the arc between the radios, and the caption row shows "3 · The radio sends the text far away". The rest of the screen is unchanged.

**Battery.** The animation runs only while the screen is visible (started in `onStart`, stopped in `onStop`).

The same screen opens from Settings → Language, with the chosen language's row marked.

### Chats: guided setup (M1)

Setup becomes three numbered steps:

1. **Download the voice:** the current voice card's content (size, progress) and two buttons numbered like the two parts of the step's picture: "1 · Download" and "2 · From a friend". The second opens a short guide in the card (the friend shares the app from Settings; accept the file here, then tap it), with a secondary "Choose the file" button for the file picker and "Back". Nothing opens the file picker by itself. When a pack download could not reach the internet, "From a friend" is the filled button and Download is outlined; otherwise Download is filled. While a download runs, its progress bar takes the place of the Download button and "From a friend" stays under it.
2. **Connect your radio:** turn the radio on, keep it close, tap "Find radio".
3. **Say hello (new):** hold the microphone and say something. Tapping it opens the group chat; the microphone there shows a one-time hint ("Hold to talk") pointing at it.

Layout while setup is not finished:

- **The current step**, the first step not yet done, sits large at the top in a card: a numbered circle, "STEP 2 OF 3" above the step title, a progress bar for steps done, a drawing for the step, one sentence, and the step's main button.
- **The other steps** are slim rows under the card: done steps show a green circle with ✓, a title and a one-line detail (e.g. "French · 66 MB"); later steps show a grey numbered circle, a title and a one-line hint.
- **Nothing else.** While the steps show, the chat list, the "No messages yet" hint and the ➕ button are hidden, and the steps use the whole height under the header (they scroll only if they are taller than that), so the current step's buttons are not cut. Step 3's button opens the group chat, so the list is not needed during setup. A message that arrives during setup is kept and appears in the list afterwards.

**When a step counts as done.** Steps 1 and 2 use the existing live checks (voice ready, radio connected). Step 3 is done once the user has sent one message; this is stored, so it never comes back. When all three are done at the same time, the card shows the existing green "done" state for two seconds and the whole guide folds away. The chat list, its hint and ➕ appear together with that green state.

**After setup.** Once the guide has folded away, it never returns; a stored flag records this. If the voice or the radio is lost later, a single slim card for that problem appears on its own, without step numbers, using the same drawing as its step, as the 3a cards do today. The chat list, its hint and ➕ stay on screen under such a card, which takes at most three quarters of the height.

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

- **`StoryTimeline`** (pure Kotlin, no Android types): given a time from 0 to 1, returns every value the picture needs (phone positions, mouth opening, bubble position, scale and visible letters, ring radii and opacities, light states, camera rectangle, current moment). It also maps a time in the paced loop to the action time and the rest it falls in, and returns the resting picture. It holds all the timing in one place and is unit-tested.
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

- Unit tests: `StoryTimeline` (each moment starts and ends at the listed times; the bubble is hidden only outside 2.8–7.6 s; letters never exceed "Bonjour"; ring radii stay within their limit; the camera ends where it starts; each numbered step and the opening caption are shown for 4 seconds; the loop starts with the opening caption's rest on the first picture; the action plays at its original speed; a rest keeps its step and moves only within the limits above, starting and ending on the held picture), `SetupSteps` (every combination of the four inputs, including loss of voice or radio after setup), and the `Settings` additions.
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
- Animations other than the welcome picture and the how-to pictures below.

## Addendum: how-to pictures (owner, 2026-10-07)

The owner asked that every step and every problem shows, with a moving picture, what to do. The choices come from the artifact "Phonemesh How-To Pictures" (rounds 7 and 8). The region cards are specified in `docs/specs/2026-10-07-radio-region-design.md` and use the same pictures.

### Rules for every picture

- **Same world as the story:** wax-print colours, dark-brown outlines, the story's phone, radio and bubble.
- **Drawn finger (H1)** shows every press, hold or slide.
- **Loops while visible (M2):** each picture repeats every 5 to 8 seconds while it is on screen and stops when the screen stops. When animations are off, it shows one still frame chosen per scene, the one that shows the action.
- **One sentence** beside each picture, read aloud by its speaker button. No technical names (no "Whisper", no "BLE").
- The pictures replace the three still step drawings.

### Scenes

| Scene | Where | Sentence (EN) | What moves |
|---|---|---|---|
| download | step 1 | Download the French voice. One time only. | A phone mast sends Wi-Fi waves; dots travel into the phone, which fills; green check. |
| friend | step 1, part 2 ("From a friend"); also the friend guide's first picture | No internet? A friend can send you the voice by Bluetooth. | The voice (orange box with sound bars) hops from the friend's phone to yours, with the Bluetooth sign between them. |
| power | step 2, part 1 | Turn on the radio. | A finger presses the button; the light blinks. |
| close | step 2, part 2 | Keep the radio close to the phone. | The radio moves next to the phone. |
| search | step 2, part 3 | Tap "Find radio". | The phone sends rings until it finds the radio, then they link. |
| pair | step 2, only if asked | Type the number shown on the radio. | The code on the radio's screen is typed into the phone. Kept only if the real-radio test shows the V4 asks for a code. |
| region cards | step 2, part 4 | see the region spec | The flag appears on the radio's screen (R2). |
| hello | step 3 | Hold the yellow button and say hello. | Hold, talk, let go; words appear as text. |
| cancel | tip, first recording | Changed your mind? Slide left. | Finger slides to the bin while holding. |
| lock | tip, first recording | Long message? Slide up to lock. | Finger slides up to the lock, lets go; tap the square to stop. |
| listen | tip, first draft | Listen to it, then send. | Listen plays; finger taps the green arrow. |
| delivery | tip, first message sent | Watch the small sign. | Clock, then arrow, then green check. |
| failed | tip, first failed message | Not sent? Tap "Send again". | Red sign, tap, clock, green check. |
| arrive | tip, first message received | A message came by radio. Tap to hear it. | The radio pulses, the message slides in, the phone reads it. |
| lost | problem card | Turn on the radio and come closer. | Finger presses the radio button; the radio comes close. |
| bt | problem card | Turn on Bluetooth. | The switch turns blue. |
| short | problem card | Hold the button while you talk. | A quick tap does nothing; holding records. |
| notheard | problem card | Hold the phone close and speak again. | The phone comes close to the mouth; the voice bars grow. |
| mic | problem card | You can still type. | The keyboard is shown as the other way to write. |
| allow | Bluetooth or microphone refused | Tap "Allow" so the phone can find the radio. | The phone's own question with the right button lit. |
| dlfail | download failed | No signal. Try again. | The mast's waves break; finger taps the round arrow; the download goes on. |
| damaged | damaged file | Download it again. | The broken box shakes away; a new one comes in. |
| refused | wrong, unknown or unsafe file | This file was refused. | The grey box goes from the sender's phone and bounces off the receiver's phone; a green shield. |
| older | a newer version is installed | You already have a newer one. | Box 1 from the sender's phone bounces off because box 2 is already inside. |
| space | not enough space | Remove something to make room. | One thing goes to the bin, then the voice fits. |
| wait | another task running | Wait until this is done. | The bar fills, then the button wakes up. |
| toolong | message reached the limit | The message stops at the time limit. | A clock runs while the person talks; at the red line, scissors cut the rest off. |
| playfail | cannot play | The text is still there to read. | The speaker fails; an arrow points at the words. |

### Tips and the Help page (T1)

- Each tip shows once, in the chat, above the microphone, the first time its moment happens (first recording, first draft, first send, first failure, first received message). It closes with a tap or after the person does the action. Shown tips are stored and never come back on their own.
- A **"?"** button in the chats top bar, beside the gear, opens a **Help** page with every scene above, grouped as Setup, Talking, Problems and Rare problems. Each row plays its picture and its sentence.

### Too long: cut off, not condensed (behaviour change)

- While recording, a clock ring fills around the microphone button. At the limit (15 s, which fits one radio part for normal French speech) the recording stops by itself, as if the person let go.
- If the transcribed text still does not fit, the draft keeps the longest run of whole words that fits (`DraftRules.trimToFit(text, languageTag)`) and shows the toolong picture with "The end was cut off." The draft can still be listened to, cleared or sent.
- Typed text cannot go past the limit: the text box stops taking characters once `DraftRules.fit` would be `TOO_LONG`.
- The 15 s limit is a constant in one place; a later wire format with bigger messages raises it.

### Architecture additions

- **`HowToScene`** (pure Kotlin): a scene's name, length, still-frame time and its parts' timings, like `StoryTimeline`. Unit-tested: every scene loops back to its first frame and its still frame shows the action.
- **`HowToView`** (a `View`): draws one scene from shared drawing parts (phone, radio with screen, finger, mast, box, flags, clock), using the same frame callback and visibility rules as `StoryView`.
- **`Tips`** (pure Kotlin): which tip to show from the moments seen and tips stored. Unit-tested.
- **`HelpActivity`**: the Help page.
- **`Settings`** gains `tipsShown`.
- **Speaking the sentences:** the two step 1 scenes (download, friend) get recorded clips per language, since the voice is not downloaded yet. The friend guide reuses the friend and allow pictures with its own two sentences, which have no clips: their speaker shows only when a voice is ready. Every other sentence is read by the app's own downloaded voice, the one that reads messages, so it costs no APK space.

### Size and testing

- The 150 KB budget now covers the how-to pictures, the four extra clips and the Help page too.
- The screenshot build adds every scene's still frame, in light and dark, and the Help page.
- On the Fairphone 3, `dumpsys gfxinfo` shows the Help page holds its frame rate with several pictures visible.
